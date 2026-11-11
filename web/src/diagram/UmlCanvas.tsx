import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import {
  Background,
  BackgroundVariant,
  Controls,
  Panel,
  ReactFlow,
  ReactFlowProvider,
  SelectionMode,
  useEdgesState,
  useNodesState,
  useReactFlow,
  useViewport,
  type Connection,
  type Edge,
  type EdgeTypes,
  type NodeTypes,
} from '@xyflow/react';
import type { ClassifierResource, ModelResource, NoteResource, NoteTargetType, OperationEnvelope } from '../api/types';
import type { GeometryUpdate, ModelCommandRequest, ClassifierKind, ViewStyleUpdate } from './commands';
import {
  buildAnchorStyleRequest,
  buildGeometrySetRequest,
  buildRelationshipCreateRequest,
  buildStyleSetRequest,
  DEFAULT_ROUTING,
  mergeViewStyleUpdate,
} from './commands';
import { createGeometryCoalescer } from './geometry-coalescer';
import { buildUnplacedGeometryRequests, type UnplacedGeometryAttempt } from './unplaced-geometry';
import { autoAnchorSide, sideLanes, type AnchorSide, type RelationshipAnchor } from './orthogonal-routing';
import { UmlClassNode, type ClassFlowNode, type ClassNodeData } from './UmlClassNode';
import {
  UmlNoteAnchorNode,
  UmlNoteNode,
  type NoteAnchorFlowNode,
  type NoteFlowNode,
} from './UmlNoteNode';
import UmlRelationshipEdge, { type EdgePoint, type RelationshipEdgeData, type RelationshipFlowEdge } from './UmlRelationshipEdge';
import type { CanvasSelection } from './selection';
import ContextMenu from './ContextMenu';
import {
  CONTEXT_MENU_DEFAULT_END_ORDINAL,
  buildContextMenuItems,
  contextMenuEndOrdinal,
  contextMenuStateFor,
  multiplicityUpdateRequest,
  nearestRelationshipEndOrdinal,
  selectionFromTarget,
  type ContextMenuState,
  type ContextMenuTarget,
} from './contextMenuBehavior';
import RemoteCursors from '../collaboration/RemoteCursors.tsx';
import type { RemoteCursorMarker } from '../collaboration/remoteCursors';
import type { TouchedElement } from './touch-highlight';
import type { RelationToolKind } from './relationTool';
import {
  PASTE_OFFSET,
  copySelection,
  deleteClipboardClassifiers,
  pasteClipboard,
  relationshipsLostByCut,
  type DiagramClipboard,
} from './clipboard';

/**
 * Shared by every canvas of the tab, so a group copied in one project can be pasted in another.
 * `pastes` drives the offset: each paste lands one step further, and a cut starts at -1 so its
 * first paste goes back exactly where the cut classes were.
 */
let canvasClipboard: { clipboard: DiagramClipboard; pastes: number } | null = null;

/** Per-browser memory of the folded copy/paste hint; storage can be missing or blocked. */
const CLIPBOARD_HINT_KEY = 'diagram.clipboardHint.folded';

function readHintFolded(): boolean {
  try {
    return window.localStorage.getItem(CLIPBOARD_HINT_KEY) === 'true';
  } catch {
    return false;
  }
}

function writeHintFolded(folded: boolean): void {
  try {
    window.localStorage.setItem(CLIPBOARD_HINT_KEY, String(folded));
  } catch {
    // Without storage the hint simply opens again on the next visit.
  }
}

const DEFAULT_NODE_WIDTH = 260;
const GRID_SIZE = 24;
// Bounded re-check for the deferred fit: React Flow measures a node's DOM size with a
// ResizeObserver after commit, which lands within a frame or two on a healthy machine.
// The instrumented verification measured DOM entry at 157-389 ms, so 120 frames (~2 s at
// 60 Hz) covers it with a >4x margin even on a slow machine, while guaranteeing the
// loop can never spin forever: past the limit the fit is abandoned instead of yanking
// the viewport at an arbitrary time.
const MAX_FIT_FRAMES = 120;
const nodeTypes: NodeTypes = { umlClass: UmlClassNode, umlNote: UmlNoteNode, umlNoteAnchor: UmlNoteAnchorNode };
const edgeTypes: EdgeTypes = { umlRelationship: UmlRelationshipEdge };

interface UmlCanvasProps {
  model: ModelResource;
  selection: CanvasSelection | null;
  onSelect: (selection: CanvasSelection | null) => void;
  /** Returns the envelope it dispatched, so a caller can refer to the element a command creates. */
  onCommand: (request: ModelCommandRequest) => OperationEnvelope | null | void;
  onAddClassifier: (kind: ClassifierKind) => void;
  /** Kind a drag between two classes creates; chosen in the tool rail. */
  relationKind?: RelationToolKind;
  /** While the relation tool is on, a clicked class becomes the next end of the relation setup. */
  relationActive?: boolean;
  onRelationEndPicked?: (classifierId: string) => void;
  /** A drag for an association class still needs its class: the setup takes over with both ends. */
  onConnectionNeedsClass?: (ends: { sourceId: string; targetId: string }) => void;
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

function geometryPayload(
  model: ModelResource,
  classifierId: string,
  rectangle: NodeRectangle,
  zOrder = 0,
  targetType: 'CLASSIFIER' | 'NOTE' = 'CLASSIFIER',
): GeometryUpdate | null {
  const view = defaultView(model);
  if (!view) {
    return null;
  }
  return {
    targetType,
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
      // The height is left to the card. A stored height was captured while the class was still
      // empty, so it only works as a floor: as a fixed height it clipped every class that grew
      // after that, leaving its handles and the routes attached in the middle of the box.
      style: { minHeight: geometry?.h ?? defaultHeight(classifier) },
      ...(zIndex !== undefined ? { zIndex } : {}),
      data,
      ...(previous?.selected !== undefined ? { selected: previous.selected } : {}),
      draggable: true,
      deletable: false,
    };
  });
}

/** ANC-06: stored anchor sides override the dominant-axis handle choice. */
const ANCHOR_HANDLE_SIDE: Record<AnchorSide, 'top' | 'right' | 'bottom' | 'left'> = {
  N: 'top',
  E: 'right',
  S: 'bottom',
  W: 'left',
};

export function chooseHandles(
  source: NodeRectangle,
  target: NodeRectangle,
  anchors?: { source: RelationshipAnchor; target: RelationshipAnchor } | null,
): { sourceHandle: string; targetHandle: string } {
  const sourceCenter = { x: source.x + source.width / 2, y: source.y + source.height / 2 };
  const targetCenter = { x: target.x + target.width / 2, y: target.y + target.height / 2 };
  const deltaX = targetCenter.x - sourceCenter.x;
  const deltaY = targetCenter.y - sourceCenter.y;
  const auto = Math.abs(deltaY) >= Math.abs(deltaX)
    ? (deltaY >= 0
      ? { source: 'bottom', target: 'top' }
      : { source: 'top', target: 'bottom' })
    : (deltaX >= 0
      ? { source: 'right', target: 'left' }
      : { source: 'left', target: 'right' });
  // A missing or malformed end (and an unknown side) simply keeps the auto side.
  const sideFor = (anchor: RelationshipAnchor | undefined | null): 'top' | 'right' | 'bottom' | 'left' | null =>
    anchor && typeof anchor.side === 'string' && anchor.side in ANCHOR_HANDLE_SIDE
      ? ANCHOR_HANDLE_SIDE[anchor.side as AnchorSide]
      : null;
  return {
    sourceHandle: `source-${sideFor(anchors?.source) ?? auto.source}`,
    targetHandle: `target-${sideFor(anchors?.target) ?? auto.target}`,
  };
}

const SIDE_OF_HANDLE: Record<string, AnchorSide> = { top: 'N', right: 'E', bottom: 'S', left: 'W' };

/**
 * The side each end of a new relationship was connected through, read from the handle ids
 * (`source-right`, `target-top`, ...). `flipped` is true when connectionDirection swapped the pair,
 * so the handle that React Flow called the target belongs to the end the user started from.
 */
export function connectionSides(
  sourceHandle: string | null | undefined,
  targetHandle: string | null | undefined,
  flipped: boolean,
): { source: AnchorSide; target: AnchorSide } | null {
  const sideOf = (handle: string | null | undefined) => SIDE_OF_HANDLE[(handle ?? '').split('-')[1] ?? ''];
  const source = sideOf(flipped ? targetHandle : sourceHandle);
  const target = sideOf(flipped ? sourceHandle : targetHandle);
  return source && target ? { source, target } : null;
}

/**
 * Keeps the connected sides only when the router would not have picked them anyway. A side stored
 * for no reason would pin the route to the centre of that side and take the end out of its lane.
 */
export function differingSides(
  sides: { source: AnchorSide; target: AnchorSide },
  source: NodeRectangle,
  target: NodeRectangle,
): { source: AnchorSide; target: AnchorSide } | null {
  return sides.source === autoAnchorSide(source, target, 'source') && sides.target === autoAnchorSide(source, target, 'target')
    ? null
    : sides;
}

/**
 * Both handle types share each side's position, so React Flow resolves a connection by handle
 * type, not by gesture. The direction of a UML relationship is the one the user drew: from the
 * class the drag started on to the class it ended on.
 */
export function connectionDirection(
  source: string,
  target: string,
  startedOn: string | null,
): { sourceId: string; targetId: string } {
  const startedOnTarget = startedOn === target && startedOn !== source;
  return startedOnTarget ? { sourceId: target, targetId: source } : { sourceId: source, targetId: target };
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
  interactionHandlers?: {
    onRelationshipContextMenu?: RelationshipEdgeData['onRelationshipContextMenu'];
    onEdgeEndpointsChange?: RelationshipEdgeData['onEdgeEndpointsChange'];
    onRouteAdjustmentChange?: RelationshipEdgeData['onRouteAdjustmentChange'];
  },
  previous: readonly RelationshipFlowEdge[] = [],
): RelationshipFlowEdge[] {
  // A rebuild must not drop the edge the user just clicked: its anchor and bend handles only
  // render while it is selected, and the rebuild runs again right after the click.
  const selectedIds = new Set(previous.filter((edge) => edge.selected).map((edge) => edge.id));
  const view = defaultView(model);
  const rectangles = classifierRectangles(model);
  const viewStyles = model.view_style ?? [];
  const lanes = sideLanes(model.relationships.flatMap((relationship) => {
    const [first, second] = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal);
    return first && second ? [{ id: relationship.id, sourceId: first.classifierId, targetId: second.classifierId }] : [];
  }), rectangles);
  return model.relationships.flatMap((relationship) => {
    const ends = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal);
    const sourceEnd = ends[0];
    const targetEnd = ends[1];
    if (!sourceEnd || !targetEnd) {
      return [];
    }
    const sourceRectangle = rectangles.get(sourceEnd.classifierId);
    const targetRectangle = rectangles.get(targetEnd.classifierId);
    const styleRow = viewStyles.find((row) => row.viewId === view?.id && row.targetId === relationship.id);
    // ANC-06: the stored anchor sides, when present, pick the connection handles too.
    const handles = sourceRectangle && targetRectangle
      ? chooseHandles(sourceRectangle, targetRectangle, styleRow?.routeAdjustment?.anchors ?? null)
      : {};
    const waypoints = view?.connector_path
      .filter((path) => path.relationshipId === relationship.id)
      .sort((left, right) => left.ordinal - right.ordinal)
      .map(({ x, y }) => ({ x, y })) ?? [];
    const style = {
      routing: styleRow?.routing ?? DEFAULT_ROUTING,
      cornerRadius: styleRow?.cornerRadius ?? 0,
      strokeWidth: styleRow?.strokeWidth ?? 2,
      dashed: styleRow?.dashed ?? false,
      routeAdjustment: styleRow?.routeAdjustment ?? null,
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
        lane: lanes.get(relationship.id) ?? { source: 0, target: 0 },
        ...(relationship.associationClassId ? { associationClassId: relationship.associationClassId } : {}),
        ...(onWaypointsChange ? { onWaypointsChange } : {}),
        ...(interactionHandlers?.onRelationshipContextMenu
          ? { onRelationshipContextMenu: interactionHandlers.onRelationshipContextMenu }
          : {}),
        ...(interactionHandlers?.onEdgeEndpointsChange
          ? { onEdgeEndpointsChange: interactionHandlers.onEdgeEndpointsChange }
          : {}),
        ...(interactionHandlers?.onRouteAdjustmentChange
          ? { onRouteAdjustmentChange: interactionHandlers.onRouteAdjustmentChange }
          : {}),
      },
      selectable: true,
      deletable: false,
      ...(selectedIds.has(relationship.id) ? { selected: true } : {}),
    }];
  });
}

/** Every node the canvas can hold: classes, notes and the invisible anchors note links can end on. */
export type CanvasNode = ClassFlowNode | NoteFlowNode | NoteAnchorFlowNode;

export interface NoteLinkData extends Record<string, unknown> {
  noteId: string;
  targetType: NoteTargetType;
  targetId: string;
}

/** The dashed edge from a note to one element it annotates. */
export type NoteLinkEdge = Edge<NoteLinkData, 'straight'>;
export type CanvasEdge = RelationshipFlowEdge | NoteLinkEdge;

export function isClassNode(node: { type?: string | undefined }): node is ClassFlowNode {
  return node.type === 'umlClass';
}

export function isNoteNode(node: { type?: string | undefined }): node is NoteFlowNode {
  return node.type === 'umlNote';
}

const DEFAULT_NOTE_WIDTH = 200;
const DEFAULT_NOTE_HEIGHT = 96;
const NOTE_ANCHOR_PREFIX = 'note-anchor:';

function noteGeometryFor(model: ModelResource, noteId: string) {
  return defaultView(model)?.element_geometry.find((geometry) =>
    geometry.targetType === 'NOTE' && geometry.targetId === noteId);
}

function noteRectangles(model: ModelResource): Map<string, NodeRectangle> {
  const rectangles = new Map<string, NodeRectangle>();
  (model.notes ?? []).forEach((note, index) => {
    const geometry = noteGeometryFor(model, note.id);
    rectangles.set(note.id, {
      // Notes without saved geometry queue up in a column right of the class grid.
      x: geometry?.x ?? 56 + 3 * 340,
      y: geometry?.y ?? 56 + index * 168,
      width: geometry?.w ?? DEFAULT_NOTE_WIDTH,
      height: geometry?.h ?? DEFAULT_NOTE_HEIGHT,
    });
  });
  return rectangles;
}

/** Where a note link ends: the class itself, or the middle of the line between a relationship's ends. */
function noteLinkTargets(model: ModelResource): Map<string, { nodeId: string; rectangle: NodeRectangle }> {
  const classes = classifierRectangles(model);
  const targets = new Map<string, { nodeId: string; rectangle: NodeRectangle }>();
  for (const [classifierId, rectangle] of classes) {
    targets.set(`CLASSIFIER:${classifierId}`, { nodeId: classifierId, rectangle });
  }
  for (const relationship of model.relationships) {
    const [first, second] = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal);
    const from = first && classes.get(first.classifierId);
    const to = second && classes.get(second.classifierId);
    if (from && to) {
      targets.set(`RELATIONSHIP:${relationship.id}`, {
        nodeId: `${NOTE_ANCHOR_PREFIX}${relationship.id}`,
        rectangle: {
          x: (from.x + from.width / 2 + to.x + to.width / 2) / 2,
          y: (from.y + from.height / 2 + to.y + to.height / 2) / 2,
          width: 1,
          height: 1,
        },
      });
    }
  }
  return targets;
}

/** The note nodes, plus one anchor node for each relationship a note is attached to. */
export function buildNoteNodes(
  model: ModelResource,
  existing: readonly CanvasNode[],
  onSelectNote?: (noteId: string) => void,
  onResizeGeometry?: (noteId: string, rectangle: NodeRectangle, zOrder: number) => void,
): Array<NoteFlowNode | NoteAnchorFlowNode> {
  const existingById = new Map(existing.map((node) => [node.id, node]));
  const rectangles = noteRectangles(model);
  const nodes: Array<NoteFlowNode | NoteAnchorFlowNode> = (model.notes ?? []).map((note) => {
    const previous = existingById.get(note.id);
    const geometry = noteGeometryFor(model, note.id);
    const rectangle = rectangles.get(note.id)!;
    const zIndex = geometry?.zOrder ?? previous?.zIndex;
    return {
      id: note.id,
      type: 'umlNote' as const,
      position: geometry ? { x: geometry.x, y: geometry.y } : previous?.position ?? { x: rectangle.x, y: rectangle.y },
      width: rectangle.width,
      style: { minHeight: rectangle.height },
      ...(zIndex !== undefined ? { zIndex } : {}),
      data: {
        note,
        ...(onSelectNote ? { onSelectNote } : {}),
        ...(onResizeGeometry ? {
          onResizeGeometry: (noteId: string, resized: NodeRectangle) =>
            onResizeGeometry(noteId, resized, previous?.zIndex ?? geometry?.zOrder ?? 0),
        } : {}),
      },
      ...(previous?.selected !== undefined ? { selected: previous.selected } : {}),
      draggable: true,
      deletable: false,
    };
  });
  const targets = noteLinkTargets(model);
  const anchored = new Set<string>();
  for (const note of model.notes ?? []) {
    for (const element of note.annotatedElements) {
      const target = targets.get(`${element.targetType}:${element.targetId}`);
      if (element.targetType === 'RELATIONSHIP' && target && !anchored.has(target.nodeId)) {
        anchored.add(target.nodeId);
        nodes.push({
          id: target.nodeId,
          type: 'umlNoteAnchor' as const,
          position: { x: target.rectangle.x, y: target.rectangle.y },
          width: 1,
          height: 1,
          data: { relationshipId: element.targetId },
          draggable: false,
          selectable: false,
          connectable: false,
          deletable: false,
          focusable: false,
        });
      }
    }
  }
  return nodes;
}

/** One dashed edge per note link whose element is still on the diagram. */
export function buildNoteLinkEdges(model: ModelResource): NoteLinkEdge[] {
  const rectangles = noteRectangles(model);
  const targets = noteLinkTargets(model);
  return (model.notes ?? []).flatMap((note: NoteResource) => note.annotatedElements.flatMap((element) => {
    const target = targets.get(`${element.targetType}:${element.targetId}`);
    const noteRectangle = rectangles.get(note.id);
    if (!target || !noteRectangle) {
      return [];
    }
    const handles = chooseHandles(noteRectangle, target.rectangle);
    return [{
      id: `note-link:${note.id}:${element.targetType}:${element.targetId}`,
      source: note.id,
      target: target.nodeId,
      sourceHandle: handles.sourceHandle,
      targetHandle: handles.targetHandle,
      type: 'straight' as const,
      data: { noteId: note.id, targetType: element.targetType, targetId: element.targetId },
      style: { stroke: '#8b3c86', strokeWidth: 1.5, strokeDasharray: '6 4' },
      className: 'uml-note-link',
      selectable: true,
      deletable: false,
    }];
  }));
}

/**
 * Geometry for notes the canvas shows without saved geometry (they sit in the fallback column), once
 * per note: what the user sees must reach the model, like it does for classes. A note whose node is
 * not measured yet waits for the next run.
 */
export function buildNoteGeometryRequests(
  model: ModelResource,
  nodes: readonly CanvasNode[],
  requested: ReadonlySet<string>,
): GeometryUpdate[] {
  const view = defaultView(model);
  if (!view) {
    return [];
  }
  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  return (model.notes ?? []).flatMap((note) => {
    const node = nodesById.get(note.id);
    const width = node?.measured?.width ?? node?.width;
    const height = node?.measured?.height ?? node?.height;
    if (noteGeometryFor(model, note.id) || requested.has(note.id) || !node || width === undefined || height === undefined) {
      return [];
    }
    return [{
      targetType: 'NOTE' as const,
      viewId: view.id,
      targetId: note.id,
      x: Math.round(node.position.x),
      y: Math.round(node.position.y),
      w: Math.max(1, Math.round(width)),
      h: Math.max(1, Math.round(height)),
      zOrder: Math.round(node.zIndex ?? 0),
    }];
  });
}

function CanvasFrame(props: UmlCanvasProps) {
  const { fitView, screenToFlowPosition } = useReactFlow<CanvasNode>();
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
  // Dedicated coalescer for relationship view styles (its own key namespace), so a
  // style.set can never swallow or be swallowed by a geometry.set for the same id.
  const styleCoalescer = useMemo(() => createGeometryCoalescer<ViewStyleUpdate>((_elementId, payload) => {
    props.onCommand(buildStyleSetRequest(payload));
  }), [props.onCommand]);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  // Latest endpoint anchors per edge, reported by UmlRelationshipEdge after each
  // render; only the edge component knows the resolved sourceX/Y and targetX/Y.
  const connectStartNodeRef = useRef<string | null>(null);
  const edgeEndpointsRef = useRef(new Map<string, { source: EdgePoint; target: EdgePoint }>());
  const selectNote = useCallback((noteId: string) => props.onSelect({ kind: 'note', noteId }), [props.onSelect]);
  const initialNodes = useMemo<CanvasNode[]>(
    () => [
      ...buildFlowNodes(props.model, [], props.onCommand, props.onSelect, undefined, props.touchedElements),
      ...buildNoteNodes(props.model, [], selectNote),
    ],
    [],
  );
  const [nodes, setNodes, onNodesChange] = useNodesState<CanvasNode>(initialNodes);
  const initialEdges = useMemo<CanvasEdge[]>(() => [...buildFlowEdges(props.model), ...buildNoteLinkEdges(props.model)], []);
  const [edges, setEdges, onEdgesChange] = useEdgesState<CanvasEdge>(initialEdges);
  // Notes whose first geometry was already requested in this mount; see buildNoteGeometryRequests.
  const notesPlacedRef = useRef(new Set<string>());
  // Latest committed edges, read by the settled route-adjustment callback to build the
  // style.set from the edge's own stored style values (same pattern as nodesRef).
  const edgesRef = useRef(edges);
  edgesRef.current = edges;
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
  // Per-classifier attempt bookkeeping for the unplaced-geometry effect, produced and
  // updated only by the pure policy in unplaced-geometry.ts: at most one request per
  // classifier per model revision (the effect re-runs on node churn when React Flow
  // re-measures, so the revision stamp is what keeps duplicate identical commands out),
  // retried only when a NEW model revision still shows no saved geometry (the "command
  // was not accepted" signal) up to the bounded limit, stopped by a command that lands,
  // and released when the classifier gains geometry or leaves the model so a later
  // re-arrival can request again.
  const unplacedAttemptsRef = useRef(new Map<string, UnplacedGeometryAttempt>());
  // Mirrors the module clipboard so the Pegar button enables as soon as something is copied.
  const [hasClipboard, setHasClipboard] = useState(canvasClipboard !== null);
  // The hint covers a corner of the diagram, so it folds into a small button once read.
  const [hintFolded, setHintFolded] = useState(() => typeof window !== 'undefined' && readHintFolded());
  const foldHint = (folded: boolean) => {
    setHintFolded(folded);
    writeHintFolded(folded);
  };
  // Classifiers of the last paste: the next model sync selects them, so they can be dragged as a group.
  const pastedIdsRef = useRef<Set<string> | null>(null);

  const persistResize = useCallback((classifierId: string, rectangle: NodeRectangle, zOrder: number) => {
    const payload = geometryPayload(props.model, classifierId, rectangle, zOrder);
    const key = `classifier:${classifierId}`;
    if (payload) {
      coalescer.schedule(key, payload);
      coalescer.flush(key);
    }
  }, [coalescer, props.model]);

  const persistNoteResize = useCallback((noteId: string, rectangle: NodeRectangle, zOrder: number) => {
    const payload = geometryPayload(props.model, noteId, rectangle, zOrder, 'NOTE');
    const key = `note:${noteId}`;
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
    setEdges((current) => current.map((edge) => edge.type === 'umlRelationship' && edge.id === relationshipId && edge.data
      ? { ...edge, data: { ...edge.data, waypoints } }
      : edge));
    if (settled) {
      coalescer.schedule(key, { targetType: 'RELATIONSHIP', viewId, targetId: relationshipId, waypoints });
      coalescer.flush(key);
    }
  }, [coalescer, setEdges, viewId]);

  const updateRouteAdjustment = useCallback<NonNullable<RelationshipEdgeData['onRouteAdjustmentChange']>>(
    (relationshipId, adjustment, settled) => {
      // Optimistic: the stored edge style follows the drag so the render never waits for
      // the command round trip; the settle then persists the whole style row.
      setEdges((current) => current.map((edge) => edge.type === 'umlRelationship' && edge.id === relationshipId && edge.data?.style
        ? { ...edge, data: { ...edge.data, style: { ...edge.data.style, routeAdjustment: adjustment } } }
        : edge));
      if (!settled || !viewId) {
        return;
      }
      const edge = edgesRef.current.find((candidate) => candidate.type === 'umlRelationship' && candidate.id === relationshipId);
      const style = edge?.type === 'umlRelationship' ? edge.data?.style : undefined;
      if (!style) {
        return;
      }
      const key = `relationship-style:${relationshipId}`;
      styleCoalescer.schedule(key, mergeViewStyleUpdate(viewId, relationshipId, {
        routing: style.routing,
        cornerRadius: style.cornerRadius,
        strokeWidth: style.strokeWidth,
        dashed: style.dashed,
        routeAdjustment: style.routeAdjustment ?? null,
      }, { routeAdjustment: adjustment }));
      styleCoalescer.flush(key);
    },
    [setEdges, styleCoalescer, viewId],
  );

  const rememberEdgeEndpoints = useCallback<NonNullable<RelationshipEdgeData['onEdgeEndpointsChange']>>(
    (relationshipId, source, target) => {
      edgeEndpointsRef.current.set(relationshipId, { source, target });
    },
    [],
  );

  const openRelationshipEndContextMenu = useCallback<NonNullable<RelationshipEdgeData['onRelationshipContextMenu']>>(
    (event, relationshipId, endOrdinal) => {
      // A right-clicked end label pins the menu to exactly that end.
      openContextMenu(event, { kind: 'relationship', relationshipId, endOrdinal });
    },
    [props.onSelect],
  );

  useEffect(() => {
    // nodesRef holds the nodes committed before this model update, i.e. exactly the
    // elements already on screen; the new classifier is not among them.
    const unplaced = unplacedClassifierIds(props.model, nodesRef.current.filter(isClassNode));
    const pasted = pastedIdsRef.current;
    pastedIdsRef.current = null;
    setNodes((current) => {
      const next: CanvasNode[] = buildFlowNodes(
        props.model,
        current.filter(isClassNode),
        props.onCommand,
        props.onSelect,
        persistResize,
        props.touchedElements,
      );
      const withNotes: CanvasNode[] = [
        ...next,
        ...buildNoteNodes(props.model, current, selectNote, persistNoteResize),
      ];
      return pasted ? withNotes.map((node) => ({ ...node, selected: pasted.has(node.id) })) : withNotes;
    });
    const nextEdges = buildFlowEdges(props.model, updateWaypoints, {
      onRelationshipContextMenu: openRelationshipEndContextMenu,
      onEdgeEndpointsChange: rememberEdgeEndpoints,
      onRouteAdjustmentChange: updateRouteAdjustment,
    }, edgesRef.current.filter((edge): edge is RelationshipFlowEdge => edge.type === 'umlRelationship'));
    const allEdges: CanvasEdge[] = [...nextEdges, ...buildNoteLinkEdges(props.model)];
    setEdges(pasted ? allEdges.map((edge) => ({ ...edge, selected: false })) : allEdges);
    if (unplaced.length > 0 && pendingFitRef.current === null) {
      // A node handed over in this render is not yet measured by React Flow, so a
      // synchronous fitView would silently drop it from the fit bounds. Record the
      // intent instead of guessing when the measurement lands: the fit effect below
      // fires only once the node is actually measurable.
      pendingFitRef.current = unplaced[0]!;
      setPendingFitId(pendingFitRef.current);
    }
  }, [props.model, props.onCommand, props.onSelect, props.touchedElements, persistResize, persistNoteResize, selectNote, updateWaypoints, setNodes, setEdges, fitView, openRelationshipEndContextMenu, rememberEdgeEndpoints, updateRouteAdjustment]);

  // Persist the position the canvas is already showing for classifiers that arrive
  // without saved geometry (they sit on the fallback grid). A position that exists only
  // in the render is invisible to every other consumer, including the XMI export, so
  // "what you see" and "what the model has" would otherwise diverge until a manual drag.
  // Kept as its own effect, away from the model-sync effect above. This effect re-runs
  // on node identity churn (React Flow re-measures and replaces node objects a frame or
  // two after mount, before any server round trip), so the cadence lives entirely in the
  // pure decision: at most one request per classifier per model revision, retried only
  // when a new revision still shows no saved geometry, up to the bounded limit.
  useEffect(() => {
    const decision = buildUnplacedGeometryRequests(
      props.model,
      nodesRef.current.filter(isClassNode),
      unplacedAttemptsRef.current,
    );
    unplacedAttemptsRef.current = decision.attempts;
    for (const request of decision.requests) {
      const key = `classifier:${request.targetId}`;
      coalescer.schedule(key, request);
      coalescer.flush(key);
    }
    for (const request of buildNoteGeometryRequests(props.model, nodesRef.current, notesPlacedRef.current)) {
      notesPlacedRef.current.add(request.targetId);
      const key = `note:${request.targetId}`;
      coalescer.schedule(key, request);
      coalescer.flush(key);
    }
  }, [props.model, nodes, coalescer]);

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

  useEffect(() => () => styleCoalescer.cancelAll(), [styleCoalescer]);

  function rectangleForNode(node: ClassFlowNode): NodeRectangle {
    return {
      x: node.position.x,
      y: node.position.y,
      width: node.measured?.width ?? node.width ?? DEFAULT_NODE_WIDTH,
      height: node.measured?.height ?? node.height ?? defaultHeight(node.data.classifier),
    };
  }

  function settleNodeGeometry(node: CanvasNode): void {
    if (node.type === 'umlNoteAnchor') {
      return;
    }
    const isNote = isNoteNode(node);
    const key = `${isNote ? 'note' : 'classifier'}:${node.id}`;
    const rectangle = isNote
      ? {
        x: node.position.x,
        y: node.position.y,
        width: node.measured?.width ?? node.width ?? DEFAULT_NOTE_WIDTH,
        height: node.measured?.height ?? node.height ?? DEFAULT_NOTE_HEIGHT,
      }
      : rectangleForNode(node as ClassFlowNode);
    const payload = geometryPayload(props.model, node.id, rectangle, node.zIndex ?? 0, isNote ? 'NOTE' : 'CLASSIFIER');
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

  function nearestEndOrdinalForEdge(edgeId: string, event: { clientX: number; clientY: number }): number {
    // Planar approximation: the edited end is whichever endpoint anchor (source
    // vs target) sits closer to the cursor; the polyline between them is ignored.
    const endpoints = edgeEndpointsRef.current.get(edgeId);
    if (!endpoints) {
      return CONTEXT_MENU_DEFAULT_END_ORDINAL;
    }
    return nearestRelationshipEndOrdinal(
      screenToFlowPosition({ x: event.clientX, y: event.clientY }, { snapToGrid: false }),
      endpoints.source,
      endpoints.target,
    );
  }

  function handleConnect(connection: Connection): void {
    if (connection.source && connection.target) {
      const direction = connectionDirection(connection.source, connection.target, connectStartNodeRef.current);
      const flipped = direction.sourceId !== connection.source;
      const draft = { ...direction, sides: connectionSides(connection.sourceHandle, connection.targetHandle, flipped) };
      // The rail kind applies while the relation tool is on; a drag with the tool off is a plain association,
      // so a kind chosen long ago never surprises the user.
      const kind = props.relationActive ? props.relationKind ?? 'ASSOCIATION' : 'ASSOCIATION';
      if (kind === 'ASSOCIATION_CLASS') {
        props.onConnectionNeedsClass?.({ sourceId: draft.sourceId, targetId: draft.targetId });
        return;
      }
      // The kind comes from the tool rail, so a drag creates the relationship straight away.
      const envelope = props.onCommand(buildRelationshipCreateRequest(draft.sourceId, draft.targetId, kind));
      keepConnectedSides(envelope, draft);
    }
  }

  /**
   * The user chose where the relationship attaches by picking the dots; keep it, unless that is
   * what the router would have chosen. The command is queued behind the create, which has already
   * put the relationship in the optimistic model.
   */
  function keepConnectedSides(
    envelope: OperationEnvelope | null | void,
    draft: { sourceId: string; targetId: string; sides: { source: AnchorSide; target: AnchorSide } | null },
  ): void {
    const relationshipId = envelope && typeof envelope.payload.id === 'string' ? envelope.payload.id : null;
    if (!relationshipId || !viewId || !draft.sides || draft.sourceId === draft.targetId) {
      return;
    }
    const sourceNode = nodesRef.current.filter(isClassNode).find((node) => node.id === draft.sourceId);
    const targetNode = nodesRef.current.filter(isClassNode).find((node) => node.id === draft.targetId);
    const kept = sourceNode && targetNode
      ? differingSides(draft.sides, rectangleForNode(sourceNode), rectangleForNode(targetNode))
      : draft.sides;
    if (kept) {
      props.onCommand(buildAnchorStyleRequest(viewId, relationshipId, kept));
    }
  }

  /** The classes of the canvas multi-selection; without one, the single selection of the page. */
  function selectedClassifierIds(): string[] {
    const classifierIds = nodesRef.current.filter(isClassNode).filter((node) => node.selected).map((node) => node.id);
    if (classifierIds.length === 0 && props.selection?.kind === 'classifier') {
      classifierIds.push(props.selection.classifierId);
    }
    return classifierIds;
  }

  function copySelected(): DiagramClipboard | null {
    const clipboard = copySelection(props.model, selectedClassifierIds());
    if (clipboard) {
      canvasClipboard = { clipboard, pastes: 0 };
      setHasClipboard(true);
    }
    return clipboard;
  }

  function cutSelected(): void {
    // A cancelled cut must leave the clipboard as it was, not empty it.
    const previous = canvasClipboard;
    const clipboard = copySelected();
    if (!clipboard || !canvasClipboard) {
      return;
    }
    const lost = relationshipsLostByCut(props.model, clipboard);
    if (lost > 0 && !window.confirm(
      `Cortar estas clases también elimina ${lost} relación(es) con clases que no seleccionaste; esas no se copian. ¿Continuar?`,
    )) {
      canvasClipboard = previous;
      setHasClipboard(previous !== null);
      return;
    }
    canvasClipboard.pastes = -1;
    props.onSelect(null);
    deleteClipboardClassifiers(clipboard, props.model, props.onCommand);
  }

  function pasteFromClipboard(): void {
    if (!canvasClipboard) {
      return;
    }
    canvasClipboard.pastes += 1;
    const result = pasteClipboard(canvasClipboard.clipboard, props.model, props.onCommand, canvasClipboard.pastes * PASTE_OFFSET);
    // A paste that created nothing must not reselect anything on a later, unrelated sync.
    pastedIdsRef.current = result.classifierIds.length > 0 ? new Set(result.classifierIds) : null;
    props.onSelect(result.classifierIds.length === 1
      ? { kind: 'classifier', classifierId: result.classifierIds[0]! }
      : null);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const target = event.target;
    if (target instanceof HTMLElement && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) {
      return;
    }
    if ((event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey && !event.repeat) {
      const key = event.key.toLowerCase();
      if (key === 'c' && copySelected()) {
        event.preventDefault();
        return;
      }
      if (key === 'x') {
        event.preventDefault();
        cutSelected();
        return;
      }
      if (key === 'v' && canvasClipboard) {
        event.preventDefault();
        pasteFromClipboard();
        return;
      }
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
    // The Multiplicidad submenu edits the end the opener preselected (a
    // right-clicked end label, or the nearest anchor for a line right-click);
    // it falls back to the source end when none was determined.
    const target = state.target;
    const endOrdinal = contextMenuEndOrdinal(target);
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
          ? multiplicityUpdateRequest(current, endOrdinal, multiplicity)
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
        endOrdinal,
        classifierName: (classifierId: string) => props.model.classifiers.find((candidate) => candidate.id === classifierId)?.name,
      }
      : undefined);
  }

  return (
    <section className="surface diagram-panel" aria-label="Editor de diagramas de clases">
      <div className="diagram-toolbar">
        <div className="diagram-toolbar-title">
          <h2>Diagrama de clases</h2>
          <p>Modelo principal · selecciona un elemento para inspeccionarlo</p>
        </div>
        <div className="diagram-toolbar-actions">
          <span className="mode-chip">UML 2.5</span>
          <button className="button button-secondary button-compact" onClick={() => void fitView({ padding: 0.2 })} type="button">
            Ajustar a la vista
          </button>
        </div>
      </div>
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
        <ReactFlow<CanvasNode, CanvasEdge>
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={(_, node) => {
            if (node.type === 'umlNote') {
              props.onSelect({ kind: 'note', noteId: node.id });
              return;
            }
            if (!isClassNode(node)) {
              return;
            }
            props.onSelect({ kind: 'classifier', classifierId: node.id });
            // A package is not a relationship end; the setup lists only classes too.
            if (props.relationActive && node.data.classifier.kind !== 'PACKAGE') {
              props.onRelationEndPicked?.(node.id);
            }
          }}
          onNodeContextMenu={(event, node) => {
            if (!isClassNode(node)) {
              // Notes have no canvas menu of their own: the properties panel edits and deletes them.
              event.preventDefault();
              if (isNoteNode(node)) {
                props.onSelect({ kind: 'note', noteId: node.id });
              }
              return;
            }
            openContextMenu(event, { kind: 'classifier', classifierId: node.id });
          }}
          onNodeDragStop={(_, node) => settleNodeGeometry(node)}
          onConnectStart={(_, params) => { connectStartNodeRef.current = params.nodeId; }}
          onConnect={handleConnect}
          onEdgeClick={(_, edge) => props.onSelect(edge.type === 'straight' && edge.data && 'noteId' in edge.data
            ? { kind: 'note', noteId: String(edge.data.noteId) }
            : { kind: 'relationship', relationshipId: edge.id })}
          onEdgeContextMenu={(event, edge) => {
            if (edge.type !== 'umlRelationship') {
              event.preventDefault();
              return;
            }
            openContextMenu(event, {
              kind: 'relationship',
              relationshipId: edge.id,
              endOrdinal: nearestEndOrdinalForEdge(edge.id, event),
            });
          }}
          onPaneClick={() => props.onSelect(null)}
          onPaneContextMenu={(event) => openContextMenu(event, { kind: 'pane' })}
          snapToGrid
          snapGrid={[GRID_SIZE, GRID_SIZE]}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.25}
          maxZoom={2}
          panOnDrag
          selectionMode={SelectionMode.Partial}
          zoomOnScroll
          zoomOnPinch
          nodesConnectable
          deleteKeyCode={null}
          proOptions={{ hideAttribution: false }}
          aria-label="Lienzo del diagrama de clases UML"
        >
          <Background variant={BackgroundVariant.Dots} gap={GRID_SIZE} size={1.2} color="#e2d3e2" />
          <Controls position="top-right" showInteractive={false} />
          <RemoteCursors markers={screenCursors} />
          {hintFolded ? (
            <Panel position="bottom-left">
              <button className="button button-secondary button-compact canvas-clipboard-reopen" onClick={() => foldHint(false)} type="button">
                Copiar y pegar <kbd>Ctrl + C</kbd>
              </button>
            </Panel>
          ) : (
          <Panel className="canvas-clipboard-hint" position="bottom-left">
            <div className="canvas-clipboard-head">
              <span>Mayús + arrastrar o Ctrl + clic selecciona varias clases.</span>
              <button aria-label="Ocultar la ayuda de copiar y pegar" className="canvas-clipboard-close" onClick={() => foldHint(true)} type="button">
                ×
              </button>
            </div>
            <div className="canvas-clipboard-actions" aria-label="Copiar y pegar">
              <button className="button button-secondary button-compact" onClick={() => { copySelected(); }} type="button">
                Copiar <kbd>Ctrl + C</kbd>
              </button>
              <button className="button button-secondary button-compact" onClick={cutSelected} type="button">
                Cortar <kbd>Ctrl + X</kbd>
              </button>
              <button className="button button-secondary button-compact" disabled={!hasClipboard} onClick={pasteFromClipboard} type="button">
                Pegar <kbd>Ctrl + V</kbd>
              </button>
            </div>
          </Panel>
          )}
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
