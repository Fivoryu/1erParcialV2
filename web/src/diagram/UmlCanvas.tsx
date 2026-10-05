import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  Background,
  BackgroundVariant,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  useViewport,
  type Connection,
  type EdgeTypes,
  type NodeTypes,
} from '@xyflow/react';
import type { ClassifierResource, ModelResource } from '../api/types';
import type { GeometryUpdate, ModelCommandRequest, ClassifierKind, RelationshipKind } from './commands';
import {
  buildGeometrySetRequest,
  buildRelationshipCreateRequest,
  RELATIONSHIP_KINDS,
} from './commands';
import { createGeometryCoalescer } from './geometry-coalescer';
import { UmlClassNode, type ClassFlowNode, type ClassNodeData } from './UmlClassNode';
import UmlRelationshipEdge, { type RelationshipFlowEdge } from './UmlRelationshipEdge';
import type { CanvasSelection } from './selection';
import ContextMenu from './ContextMenu';
import {
  CONTEXT_MENU_DEFAULT_END_ORDINAL,
  buildContextMenuItems,
  contextMenuStateFor,
  multiplicityUpdateRequest,
  selectionFromTarget,
  type ContextMenuState,
  type ContextMenuTarget,
} from './contextMenuBehavior';
import RemoteCursors from '../collaboration/RemoteCursors.tsx';
import type { RemoteCursorMarker } from '../collaboration/remoteCursors';
import type { TouchedElement } from './touch-highlight';

const DEFAULT_NODE_WIDTH = 260;
const GRID_SIZE = 24;
// Bounded re-check for the deferred fit: React Flow measures a node's DOM size with a
// ResizeObserver after commit, which lands within a frame or two on a healthy machine.
// The instrumented verification measured DOM entry at 157-389 ms, so 120 frames (~2 s at
// 60 Hz) covers it with a >4x margin even on a slow machine, while guaranteeing the
// loop can never spin forever: past the limit the fit is abandoned instead of yanking
// the viewport at an arbitrary time.
const MAX_FIT_FRAMES = 120;
const nodeTypes: NodeTypes = { umlClass: UmlClassNode };
const edgeTypes: EdgeTypes = { umlRelationship: UmlRelationshipEdge };

const RELATIONSHIP_KIND_LABELS: Record<RelationshipKind, string> = {
  ASSOCIATION: 'Association',
  AGGREGATION: 'Aggregation',
  COMPOSITION: 'Composition',
  GENERALIZATION: 'Generalization',
  INTERFACE_REALIZATION: 'Interface realization',
  DEPENDENCY: 'Dependency',
};

interface UmlCanvasProps {
  model: ModelResource;
  selection: CanvasSelection | null;
  onSelect: (selection: CanvasSelection | null) => void;
  onCommand: (request: ModelCommandRequest) => void;
  onAddClassifier: (kind: ClassifierKind) => void;
  onDelete: (selection: CanvasSelection) => void;
  touchedElements?: TouchedElement[];
  remoteCursors?: readonly RemoteCursorMarker[];
  onCursorMove?: (position: { x: number; y: number }) => void;
}

interface NodeRectangle {
  x: number;
  y: number;
  width: number;
  height: number;
}

function defaultHeight(classifier: ClassifierResource): number {
  if (classifier.kind === 'PACKAGE') {
    return 96;
  }
  const attributes = classifier.attributes.length ? 24 + classifier.attributes.length * 24 : 0;
  const operations = classifier.operations.length ? 24 + classifier.operations.length * 24 : 0;
  const constraints = classifier.constraints.length ? 24 + classifier.constraints.length * 22 : 0;
  const heading = classifier.kind === 'INTERFACE' ? 72 : 58;
  return Math.max(96, heading + attributes + operations + constraints);
}

function defaultView(model: ModelResource) {
  return model.views.find((view) => view.is_default) ?? model.views[0];
}

function geometryFor(model: ModelResource, classifierId: string) {
  return defaultView(model)?.element_geometry.find((geometry) =>
    geometry.targetType === 'CLASSIFIER' && geometry.targetId === classifierId);
}

/**
 * Ids of classifiers that have no saved geometry in the default view AND no node on
 * screen yet: exactly the elements that would land on the index-based fallback grid and
 * can therefore appear outside the current view. Drives when the canvas must re-fit.
 */
export function unplacedClassifierIds(
  model: ModelResource,
  existing: readonly { id: string }[],
): string[] {
  const onScreen = new Set(existing.map((node) => node.id));
  return model.classifiers
    .filter((candidate) => !geometryFor(model, candidate.id) && !onScreen.has(candidate.id))
    .map((candidate) => candidate.id);
}

/**
 * True when React Flow has measured the node's DOM size. In @xyflow/react v12 the
 * measurement is stored on `node.measured.width/height` (populated when a `dimensions`
 * change is applied after the ResizeObserver fires), NOT on the top-level `width`/
 * `height`, which this codebase sets from saved geometry or defaults. `fitView` drops
 * nodes without measured dimensions from its bounds (`getFitViewNodes` checks exactly
 * `n.measured.width && n.measured.height`), so this predicate is the precise condition
 * under which a fitView can actually include the arriving node.
 */
export function isNodeMeasured(
  node: { measured?: { width?: number; height?: number } | undefined } | undefined,
): boolean {
  return Boolean(node?.measured?.width && node.measured?.height);
}

function geometryPayload(model: ModelResource, classifierId: string, rectangle: NodeRectangle, zOrder = 0): GeometryUpdate | null {
  const view = defaultView(model);
  if (!view) {
    return null;
  }
  return {
    targetType: 'CLASSIFIER',
    viewId: view.id,
    targetId: classifierId,
    x: Math.round(rectangle.x),
    y: Math.round(rectangle.y),
    w: Math.max(1, Math.round(rectangle.width)),
    h: Math.max(1, Math.round(rectangle.height)),
    zOrder: Math.round(zOrder),
  };
}

export function buildFlowNodes(
  model: ModelResource,
  existing: ClassFlowNode[],
  onCommand: UmlCanvasProps['onCommand'],
  onSelect: UmlCanvasProps['onSelect'],
  onResizeGeometry?: (classifierId: string, rectangle: NodeRectangle, zOrder: number) => void,
  touchedElements: TouchedElement[] = [],
): ClassFlowNode[] {
  const existingById = new Map(existing.map((node) => [node.id, node]));
  const touchedByElementId = new Map<string, TouchedElement['touchedBy']>();
  for (const touchedElement of touchedElements) {
    touchedByElementId.set(touchedElement.elementId, touchedElement.touchedBy);
  }
  return model.classifiers.map((classifier, index) => {
    const previous = existingById.get(classifier.id);
    const geometry = geometryFor(model, classifier.id);
    // Only a touch whose target is a classifier (class, abstract class, interface, package) highlights a card.
    // A touch on a relationship or on a member element (attribute, operation) highlights nothing on the canvas,
    // because those live in other components and this unit does not touch them.
    const touchedBy = touchedByElementId.get(classifier.id);
    const data: ClassNodeData = {
      classifier,
      ...(touchedBy?.length ? { touchedBy } : {}),
      onSelectElement: onSelect,
      onRenameClassifier: (classifierId, name) => onCommand({
        type: 'classifier.rename',
        targets: [{ scope: 'classifier', id: classifierId }],
        payload: { name },
      }),
      onRenameMember: (kind, _classifierId, elementId, name) => onCommand({
        type: `${kind}.update`,
        targets: [{ scope: kind, id: elementId }],
        payload: { name },
      }),
      ...(onResizeGeometry ? {
        onResizeGeometry: (classifierId: string, rectangle: NodeRectangle) =>
          onResizeGeometry(classifierId, rectangle, previous?.zIndex ?? geometry?.zOrder ?? 0),
      } : {}),
    };
    const column = index % 3;
    const row = Math.floor(index / 3);
    const zIndex = geometry?.zOrder ?? previous?.zIndex;
    return {
      id: classifier.id,
      type: 'umlClass' as const,
      position: geometry ? { x: geometry.x, y: geometry.y } : previous?.position ?? {
        x: 56 + column * 340,
        y: 56 + row * 260,
      },
      width: geometry?.w ?? previous?.width ?? DEFAULT_NODE_WIDTH,
      height: geometry?.h ?? previous?.height ?? defaultHeight(classifier),
      ...(zIndex !== undefined ? { zIndex } : {}),
      data,
      ...(previous?.selected !== undefined ? { selected: previous.selected } : {}),
      draggable: true,
      deletable: false,
    };
  });
}

export function chooseHandles(
  source: NodeRectangle,
  target: NodeRectangle,
): { sourceHandle: string; targetHandle: string } {
  const sourceCenter = { x: source.x + source.width / 2, y: source.y + source.height / 2 };
  const targetCenter = { x: target.x + target.width / 2, y: target.y + target.height / 2 };
  const deltaX = targetCenter.x - sourceCenter.x;
  const deltaY = targetCenter.y - sourceCenter.y;
  if (Math.abs(deltaY) >= Math.abs(deltaX)) {
    return deltaY >= 0
      ? { sourceHandle: 'source-bottom', targetHandle: 'target-top' }
      : { sourceHandle: 'source-top', targetHandle: 'target-bottom' };
  }
  return deltaX >= 0
    ? { sourceHandle: 'source-right', targetHandle: 'target-left' }
    : { sourceHandle: 'source-left', targetHandle: 'target-right' };
}

function classifierRectangles(model: ModelResource): Map<string, NodeRectangle> {
  const rectangles = new Map<string, NodeRectangle>();
  model.classifiers.forEach((classifier, index) => {
    const geometry = geometryFor(model, classifier.id);
    const column = index % 3;
    const row = Math.floor(index / 3);
    rectangles.set(classifier.id, {
      x: geometry?.x ?? 56 + column * 340,
      y: geometry?.y ?? 56 + row * 260,
      width: geometry?.w ?? DEFAULT_NODE_WIDTH,
      height: geometry?.h ?? defaultHeight(classifier),
    });
  });
  return rectangles;
}

export function buildFlowEdges(
  model: ModelResource,
  onWaypointsChange?: NonNullable<NonNullable<RelationshipFlowEdge['data']>['onWaypointsChange']>,
): RelationshipFlowEdge[] {
  const view = defaultView(model);
  const rectangles = classifierRectangles(model);
  const viewStyles = model.view_style ?? [];
  return model.relationships.flatMap((relationship) => {
    const ends = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal);
    const sourceEnd = ends[0];
    const targetEnd = ends[1];
    if (!sourceEnd || !targetEnd) {
      return [];
    }
    const sourceRectangle = rectangles.get(sourceEnd.classifierId);
    const targetRectangle = rectangles.get(targetEnd.classifierId);
    const handles = sourceRectangle && targetRectangle
      ? chooseHandles(sourceRectangle, targetRectangle)
      : {};
    const waypoints = view?.connector_path
      .filter((path) => path.relationshipId === relationship.id)
      .sort((left, right) => left.ordinal - right.ordinal)
      .map(({ x, y }) => ({ x, y })) ?? [];
    const styleRow = viewStyles.find((row) => row.viewId === view?.id && row.targetId === relationship.id);
    const style = {
      routing: styleRow?.routing ?? 'STRAIGHT',
      cornerRadius: styleRow?.cornerRadius ?? 0,
      strokeWidth: styleRow?.strokeWidth ?? 2,
      dashed: styleRow?.dashed ?? false,
    } as const;
    return [{
      id: relationship.id,
      source: sourceEnd.classifierId,
      target: targetEnd.classifierId,
      ...handles,
      type: 'umlRelationship' as const,
      data: {
        relationship,
        sourceEnd,
        targetEnd,
        waypoints,
        style,
        ...(onWaypointsChange ? { onWaypointsChange } : {}),
      },
      selectable: true,
      deletable: false,
    }];
  });
}

function CanvasFrame(props: UmlCanvasProps) {
  const { fitView, screenToFlowPosition } = useReactFlow<ClassFlowNode>();
  const viewport = useViewport();
  const screenCursors = useMemo(() => (props.remoteCursors ?? []).map((marker) => ({
    ...marker,
    // Local = flow * zoom + viewport offset; subtracting the transformed origin cancelled the pan.
    x: marker.x * viewport.zoom + viewport.x,
    y: marker.y * viewport.zoom + viewport.y,
  })), [props.remoteCursors, viewport]);
  const viewId = defaultView(props.model)?.id;
  const coalescer = useMemo(() => createGeometryCoalescer<GeometryUpdate>((_elementId, payload) => {
    props.onCommand(buildGeometrySetRequest(payload));
  }), [props.onCommand]);
  const [connectionDraft, setConnectionDraft] = useState<{ sourceId: string; targetId: string } | null>(null);
  const [connectionKind, setConnectionKind] = useState<RelationshipKind>('ASSOCIATION');
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const initialNodes = useMemo(
    () => buildFlowNodes(props.model, [], props.onCommand, props.onSelect, undefined, props.touchedElements),
    [],
  );
  const [nodes, setNodes, onNodesChange] = useNodesState<ClassFlowNode>(initialNodes);
  const initialEdges = useMemo(() => buildFlowEdges(props.model), []);
  const [edges, setEdges, onEdgesChange] = useEdgesState<RelationshipFlowEdge>(initialEdges);
  // Latest committed nodes, read by the model-sync and fit effects. Neither effect takes
  // `nodes` itself as a dependency: buildFlowNodes always returns a fresh array, so every
  // setNodes inside the sync effect would change the `nodes` identity, re-run the effect,
  // and loop forever. The fit effect must still observe measurement, which it does through
  // this ref: React Flow reports the DOM measurement as a `dimensions` change routed
  // through onNodesChange -> setNodes -> render, and nodesRef.current is refreshed on every
  // render, so the next animation frame of the fit loop sees it.
  const nodesRef = useRef(nodes);
  nodesRef.current = nodes;
  // Pending fit intent, kept in state so a dedicated effect can react to it, plus a ref
  // mirror used as the one-per-arrival guard and readable from the frame loop without
  // re-arming the effect. Repeated runs of the sync effect for one arrival (e.g. when
  // props.touchedElements changes with the same SSE event) cannot queue a second fit.
  const [pendingFitId, setPendingFitId] = useState<string | null>(null);
  const pendingFitRef = useRef<string | null>(null);
  const fitFrameRef = useRef<number | null>(null);

  const persistResize = useCallback((classifierId: string, rectangle: NodeRectangle, zOrder: number) => {
    const payload = geometryPayload(props.model, classifierId, rectangle, zOrder);
    const key = `classifier:${classifierId}`;
    if (payload) {
      coalescer.schedule(key, payload);
      coalescer.flush(key);
    }
  }, [coalescer, props.model]);

  const updateWaypoints = useCallback((relationshipId: string, waypoints: Array<{ x: number; y: number }>, settled: boolean) => {
    if (!viewId) {
      return;
    }
    const key = `relationship:${relationshipId}`;
    setEdges((current) => current.map((edge) => edge.id === relationshipId && edge.data
      ? { ...edge, data: { ...edge.data, waypoints } }
      : edge));
    if (settled) {
      coalescer.schedule(key, { targetType: 'RELATIONSHIP', viewId, targetId: relationshipId, waypoints });
      coalescer.flush(key);
    }
  }, [coalescer, setEdges, viewId]);

  useEffect(() => {
    // nodesRef holds the nodes committed before this model update, i.e. exactly the
    // elements already on screen; the new classifier is not among them.
    const unplaced = unplacedClassifierIds(props.model, nodesRef.current);
    setNodes((current) => buildFlowNodes(
      props.model,
      current,
      props.onCommand,
      props.onSelect,
      persistResize,
      props.touchedElements,
    ));
    setEdges(buildFlowEdges(props.model, updateWaypoints));
    if (unplaced.length > 0 && pendingFitRef.current === null) {
      // A node handed over in this render is not yet measured by React Flow, so a
      // synchronous fitView would silently drop it from the fit bounds. Record the
      // intent instead of guessing when the measurement lands: the fit effect below
      // fires only once the node is actually measurable.
      pendingFitRef.current = unplaced[0]!;
      setPendingFitId(pendingFitRef.current);
    }
  }, [props.model, props.onCommand, props.onSelect, props.touchedElements, persistResize, updateWaypoints, setNodes, setEdges, fitView]);

  useEffect(() => {
    if (pendingFitId === null) {
      return;
    }
    let attempts = 0;
    const tick = () => {
      fitFrameRef.current = null;
      const pending = pendingFitRef.current;
      if (pending === null) {
        return;
      }
      const target = nodesRef.current.find((node) => node.id === pending);
      if (isNodeMeasured(target)) {
        // Clear the intent BEFORE fitting so this can only ever fire once per arrival,
        // then move the camera. fitView reads React Flow's internal store and only
        // changes the viewport; it never touches node/edge state.
        pendingFitRef.current = null;
        setPendingFitId(null);
        void fitView({ padding: 0.2 });
        return;
      }
      attempts += 1;
      if (attempts >= MAX_FIT_FRAMES) {
        // Bounded: give up rather than re-check forever. A fit this late would be more
        // surprising than none, and the bound guarantees termination.
        pendingFitRef.current = null;
        setPendingFitId(null);
        return;
      }
      fitFrameRef.current = requestAnimationFrame(tick);
    };
    fitFrameRef.current = requestAnimationFrame(tick);
  }, [pendingFitId, fitView]);

  // A queued fit is cancelled only on unmount, never when the sync effect merely re-runs.
  // Cancelling it there silently dropped the fit: that effect re-runs on dependency changes
  // belonging to the SAME arrival (`props.touchedElements` is driven by the very SSE event
  // that changed the model), and by the time it re-runs the new node is already on screen,
  // so `unplacedClassifierIds` returns nothing and nothing re-queues the fit. The
  // `pendingFitRef` guard, not a cleanup, is what keeps one arrival from queueing two fits.
  // The fit effect above deliberately has no cleanup of its own: its only dependencies are
  // `pendingFitId` (which this component alone transitions) and the stable `fitView` helper.
  useEffect(() => () => {
    if (fitFrameRef.current !== null) {
      cancelAnimationFrame(fitFrameRef.current);
      fitFrameRef.current = null;
    }
    pendingFitRef.current = null;
  }, []);

  useEffect(() => () => coalescer.cancelAll(), [coalescer]);

  function rectangleForNode(node: ClassFlowNode): NodeRectangle {
    return {
      x: node.position.x,
      y: node.position.y,
      width: node.measured?.width ?? node.width ?? DEFAULT_NODE_WIDTH,
      height: node.measured?.height ?? node.height ?? defaultHeight(node.data.classifier),
    };
  }

  function settleNodeGeometry(node: ClassFlowNode): void {
    const key = `classifier:${node.id}`;
    const payload = geometryPayload(props.model, node.id, rectangleForNode(node), node.zIndex ?? 0);
    if (payload) {
      coalescer.schedule(key, payload);
      coalescer.flush(key);
    }
  }

  function openContextMenu(event: { clientX: number; clientY: number; preventDefault: () => void }, target: ContextMenuTarget): void {
    // The canvas menu replaces the browser menu, and the anchor is the cursor.
    event.preventDefault();
    // Right-clicking an element also selects it, so the properties panel follows the menu.
    const selection = selectionFromTarget(target);
    if (selection) {
      props.onSelect(selection);
    }
    setContextMenu(contextMenuStateFor(target, { x: event.clientX, y: event.clientY }));
  }

  function handleConnect(connection: Connection): void {
    if (connection.source && connection.target) {
      setConnectionDraft({ sourceId: connection.source, targetId: connection.target });
      setConnectionKind('ASSOCIATION');
    }
  }

  function createRelationship(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    if (!connectionDraft) {
      return;
    }
    props.onCommand(buildRelationshipCreateRequest(
      connectionDraft.sourceId,
      connectionDraft.targetId,
      connectionKind,
    ));
    setConnectionDraft(null);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const target = event.target;
    if (target instanceof HTMLElement && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) {
      return;
    }
    if (event.key === 'Escape' && contextMenu) {
      event.preventDefault();
      setContextMenu(null);
      return;
    }
    if ((event.key === 'Delete' || event.key === 'Backspace') && props.selection) {
      event.preventDefault();
      props.onDelete(props.selection);
    }
  }

  function contextMenuItemsFor(state: ContextMenuState) {
    // The Multiplicidad submenu is derived from the relationship's current ends;
    // until T3 adds preselection it always edits the source end (ordinal 1).
    const target = state.target;
    const relationship = target.kind === 'relationship'
      ? props.model.relationships.find((candidate) => candidate.id === target.relationshipId)
      : undefined;
    return buildContextMenuItems(state.target, {
      onEdit: () => {
        const selection = selectionFromTarget(state.target);
        if (selection) {
          props.onSelect(selection);
        }
      },
      onDelete: () => {
        const selection = selectionFromTarget(state.target);
        if (selection) {
          props.onDelete(selection);
        }
      },
      onAddClassifier: props.onAddClassifier,
      onSetMultiplicity: (multiplicity) => {
        if (target.kind !== 'relationship') {
          return;
        }
        const relationshipId = target.relationshipId;
        const current = props.model.relationships.find((candidate) => candidate.id === relationshipId);
        const request = current
          ? multiplicityUpdateRequest(current, CONTEXT_MENU_DEFAULT_END_ORDINAL, multiplicity)
          : null;
        if (!request) {
          return;
        }
        // Same dispatch path as the properties panel: full positional payload
        // through props.onCommand, so the optimistic apply case covers it.
        props.onCommand(request);
      },
    }, relationship
      ? {
        relationship,
        classifierName: (classifierId: string) => props.model.classifiers.find((candidate) => candidate.id === classifierId)?.name,
      }
      : undefined);
  }

  const sourceName = connectionDraft
    ? props.model.classifiers.find((classifier) => classifier.id === connectionDraft.sourceId)?.name
    : null;
  const targetName = connectionDraft
    ? props.model.classifiers.find((classifier) => classifier.id === connectionDraft.targetId)?.name
    : null;

  return (
    <section className="surface diagram-panel" aria-label="Class diagram editor">
      <div className="diagram-toolbar">
        <div className="diagram-toolbar-title">
          <p className="eyebrow">Class diagram</p>
          <span className="mode-chip">UML 2.5</span>
        </div>
        <div className="diagram-toolbar-actions" aria-label="Diagram tools">
          <p className="diagram-connection-hint">Drag from one class dot to another to connect them.</p>
          <button className="button button-secondary button-compact" onClick={() => props.onAddClassifier('CLASS')} type="button">
            + Class
          </button>
          <button className="button button-secondary button-compact" onClick={() => props.onAddClassifier('ABSTRACT_CLASS')} type="button">
            + Abstract class
          </button>
          <button className="button button-secondary button-compact" onClick={() => props.onAddClassifier('INTERFACE')} type="button">
            + Interface
          </button>
          <button className="button button-secondary button-compact" onClick={() => props.onAddClassifier('PACKAGE')} type="button">
            + Package
          </button>
          <button className="button button-secondary button-compact" onClick={() => void fitView({ padding: 0.2 })} type="button">
            Fit to view
          </button>
        </div>
      </div>
      {connectionDraft && (
        <form className="connection-kind-picker" aria-label="Choose relationship kind" onSubmit={createRelationship}>
          <label className="field-label" htmlFor="relationship-kind">
            Connect {sourceName ?? 'class'} to {targetName ?? 'class'}
          </label>
          <select
            id="relationship-kind"
            onChange={(event) => setConnectionKind(event.target.value as RelationshipKind)}
            value={connectionKind}
          >
            {RELATIONSHIP_KINDS.map((kind) => (
              <option key={kind} value={kind}>{RELATIONSHIP_KIND_LABELS[kind]}</option>
            ))}
          </select>
          <p className="property-hint">
            {connectionKind === 'GENERALIZATION'
              ? 'Start at the subclass and end at its parent.'
              : connectionKind === 'INTERFACE_REALIZATION'
                ? 'Start at the implementing class and end at the interface.'
                : connectionKind === 'AGGREGATION' || connectionKind === 'COMPOSITION'
                  ? 'Start at the whole; the diamond marks this end.'
                  : connectionKind === 'DEPENDENCY'
                    ? 'The open arrow points toward the target.'
                    : 'Open arrows appear on navigable ends.'}
          </p>
          <button className="button button-primary button-compact" type="submit">Create relationship</button>
          <button className="text-button" onClick={() => setConnectionDraft(null)} type="button">Cancel</button>
        </form>
      )}
      <div
        className="diagram-canvas"
        onKeyDown={handleKeyDown}
        onPointerMove={(event) => {
          // Presence coordinates belong to the diagram, not the window, whose size, scroll, and panel widths differ by client.
          props.onCursorMove?.(screenToFlowPosition(
            { x: event.clientX, y: event.clientY },
            { snapToGrid: false },
          ));
        }}
        tabIndex={0}
      >
        <ReactFlow<ClassFlowNode, RelationshipFlowEdge>
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={(_, node) => props.onSelect({ kind: 'classifier', classifierId: node.id })}
          onNodeContextMenu={(event, node) => openContextMenu(event, { kind: 'classifier', classifierId: node.id })}
          onNodeDragStop={(_, node) => settleNodeGeometry(node)}
          onConnect={handleConnect}
          onEdgeClick={(_, edge) => props.onSelect({ kind: 'relationship', relationshipId: edge.id })}
          onEdgeContextMenu={(event, edge) => openContextMenu(event, { kind: 'relationship', relationshipId: edge.id })}
          onPaneClick={() => props.onSelect(null)}
          onPaneContextMenu={(event) => openContextMenu(event, { kind: 'pane' })}
          snapToGrid
          snapGrid={[GRID_SIZE, GRID_SIZE]}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.25}
          maxZoom={2}
          panOnDrag
          zoomOnScroll
          zoomOnPinch
          nodesConnectable
          deleteKeyCode={null}
          proOptions={{ hideAttribution: false }}
          aria-label="UML class diagram canvas"
        >
          <Background variant={BackgroundVariant.Dots} gap={GRID_SIZE} size={1.2} color="#cbd8d3" />
          <Controls position="bottom-left" showInteractive={false} />
          <RemoteCursors markers={screenCursors} />
        </ReactFlow>
      </div>
      {contextMenu && (
        <ContextMenu
          x={contextMenu.position.x}
          y={contextMenu.position.y}
          items={contextMenuItemsFor(contextMenu)}
          onClose={() => setContextMenu(null)}
        />
      )}
    </section>
  );
}

export default function UmlCanvas(props: UmlCanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasFrame {...props} />
    </ReactFlowProvider>
  );
}
