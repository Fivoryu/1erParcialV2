import { useEffect, useMemo, type KeyboardEvent } from 'react';
import {
  Background,
  BackgroundVariant,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useNodesState,
  useReactFlow,
  type NodeTypes,
} from '@xyflow/react';
import type { ModelResource } from '../api/types';
import type { ModelCommandRequest, ClassifierKind } from './commands';
import { UmlClassNode, type ClassFlowNode, type ClassNodeData } from './UmlClassNode';
import type { CanvasSelection } from './selection';

const DEFAULT_NODE_WIDTH = 260;
const GRID_SIZE = 24;
const nodeTypes: NodeTypes = { umlClass: UmlClassNode };

interface UmlCanvasProps {
  model: ModelResource;
  selection: CanvasSelection | null;
  onSelect: (selection: CanvasSelection | null) => void;
  onCommand: (request: ModelCommandRequest) => void;
  onAddClassifier: (kind: ClassifierKind) => void;
  onDelete: (selection: CanvasSelection) => void;
}

function defaultHeight(classifier: ModelResource['classifiers'][number]): number {
  const attributes = classifier.attributes.length ? 24 + classifier.attributes.length * 24 : 0;
  const operations = classifier.operations.length ? 24 + classifier.operations.length * 24 : 0;
  const constraints = classifier.constraints.length ? 24 + classifier.constraints.length * 22 : 0;
  const heading = classifier.kind === 'INTERFACE' ? 72 : 58;
  return Math.max(96, heading + attributes + operations + constraints);
}

function geometryFor(model: ModelResource, classifierId: string) {
  const defaultView = model.views.find((view) => view.is_default) ?? model.views[0];
  return defaultView?.element_geometry.find((geometry) =>
    geometry.targetType === 'CLASSIFIER' && geometry.targetId === classifierId);
}

export function buildFlowNodes(
  model: ModelResource,
  existing: ClassFlowNode[],
  onCommand: UmlCanvasProps['onCommand'],
  onSelect: UmlCanvasProps['onSelect'],
): ClassFlowNode[] {
  const existingById = new Map(existing.map((node) => [node.id, node]));
  return model.classifiers.map((classifier, index) => {
    const previous = existingById.get(classifier.id);
    const geometry = geometryFor(model, classifier.id);
    const data: ClassNodeData = {
      classifier,
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
    };
    const column = index % 3;
    const row = Math.floor(index / 3);
    const zIndex = previous?.zIndex ?? geometry?.zOrder;
    return {
      id: classifier.id,
      type: 'umlClass' as const,
      position: previous?.position ?? (geometry ? { x: geometry.x, y: geometry.y } : {
        x: 56 + column * 340,
        y: 56 + row * 260,
      }),
      width: previous?.width ?? geometry?.w ?? DEFAULT_NODE_WIDTH,
      height: previous?.height ?? geometry?.h ?? defaultHeight(classifier),
      ...(zIndex !== undefined ? { zIndex } : {}),
      data: {
        ...data,
        onSelectElement: onSelect,
      },
      ...(previous?.selected !== undefined ? { selected: previous.selected } : {}),
      draggable: true,
      deletable: false,
    };
  });
}

function CanvasFrame(props: UmlCanvasProps) {
  const { fitView } = useReactFlow<ClassFlowNode>();
  const initialNodes = useMemo(() => buildFlowNodes(props.model, [], props.onCommand, props.onSelect), []);
  const [nodes, setNodes, onNodesChange] = useNodesState<ClassFlowNode>(initialNodes);

  useEffect(() => {
    setNodes((current) => buildFlowNodes(props.model, current, props.onCommand, props.onSelect));
  }, [props.model, props.onCommand, props.onSelect, setNodes]);

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const target = event.target;
    if (target instanceof HTMLElement && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) {
      return;
    }
    if ((event.key === 'Delete' || event.key === 'Backspace') && props.selection) {
      event.preventDefault();
      props.onDelete(props.selection);
    }
  }

  return (
    <section className="surface diagram-panel" aria-label="Class diagram editor">
      <div className="diagram-toolbar">
        <div className="diagram-toolbar-title">
          <p className="eyebrow">Class diagram</p>
          <span className="mode-chip">UML 2.5</span>
        </div>
        <div className="diagram-toolbar-actions" aria-label="Diagram tools">
          <button className="button button-secondary button-compact" onClick={() => props.onAddClassifier('CLASS')} type="button">
            + Class
          </button>
          <button className="button button-secondary button-compact" onClick={() => props.onAddClassifier('ABSTRACT_CLASS')} type="button">
            + Abstract class
          </button>
          <button className="button button-secondary button-compact" onClick={() => props.onAddClassifier('INTERFACE')} type="button">
            + Interface
          </button>
          <button className="button button-secondary button-compact" onClick={() => void fitView({ padding: 0.2 })} type="button">
            Fit to view
          </button>
        </div>
      </div>
      <div className="diagram-canvas" onKeyDown={handleKeyDown} tabIndex={0}>
        <ReactFlow<ClassFlowNode>
          nodes={nodes}
          edges={[]}
          nodeTypes={nodeTypes}
          onNodesChange={onNodesChange}
          onNodeClick={(_, node) => props.onSelect({ kind: 'classifier', classifierId: node.id })}
          onPaneClick={() => props.onSelect(null)}
          snapToGrid
          snapGrid={[GRID_SIZE, GRID_SIZE]}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.25}
          maxZoom={2}
          panOnDrag
          zoomOnScroll
          zoomOnPinch
          nodesConnectable={false}
          deleteKeyCode={null}
          proOptions={{ hideAttribution: false }}
          aria-label="UML class diagram canvas"
        >
          <Background variant={BackgroundVariant.Dots} gap={GRID_SIZE} size={1.2} color="#cbd8d3" />
          <Controls position="bottom-left" showInteractive={false} />
        </ReactFlow>
      </div>
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
