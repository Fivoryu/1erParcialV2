import type { ChatProposalResource, ClassifierResource, ModelResource, RelationshipResource } from '../api/types';
import { describeAssistantOperation, type DiagramNames } from '../assistant/assistantNames';
import { formatAttribute, formatOperation, formatRelationshipName, orderedAttributes, orderedOperations } from '../diagram/uml-format';
import { projectProposalPreview, type ProposalPreviewState } from './proposalProjectionPreview';

const STATE_LABELS: Record<ProposalPreviewState, { sign: string; label: string }> = {
  added: { sign: '+', label: 'Añadido' },
  removed: { sign: '−', label: 'Eliminado' },
  modified: { sign: '~', label: 'Modificado' },
};

const CARD_WIDTH = 220;
const GAP = 48;
const PADDING = 24;

interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Height the read-only card needs, so new members never overflow a stored geometry. */
function estimatedHeight(classifier: ClassifierResource): number {
  // Heading with a badge, then each compartment's padding plus one 22px row per member.
  const compartment = (rows: number) => (rows > 0 ? 16 + rows * 22 : 0);
  return 78 + compartment(classifier.attributes.length) + compartment(classifier.operations.length);
}

/** Places each class where the default view has it; classes without a position go in a row below. */
function layout(model: ModelResource): Map<string, Box> {
  const view = model.views.find((item) => item.is_default) ?? model.views[0];
  const boxes = new Map<string, Box>();
  const unplaced: ClassifierResource[] = [];
  for (const classifier of model.classifiers) {
    const geometry = view?.element_geometry.find((item) => item.targetType === 'CLASSIFIER' && item.targetId === classifier.id);
    if (geometry) {
      boxes.set(classifier.id, {
        // Labels need a little more room than the editor's card, so the width never shrinks below the default.
        x: geometry.x, y: geometry.y, w: Math.max(geometry.w, CARD_WIDTH), h: Math.max(geometry.h, estimatedHeight(classifier)),
      });
    } else {
      unplaced.push(classifier);
    }
  }
  const placed = [...boxes.values()];
  const left = placed.length > 0 ? Math.min(...placed.map((box) => box.x)) : 0;
  const top = placed.length > 0 ? Math.max(...placed.map((box) => box.y + box.h)) + GAP : 0;
  unplaced.forEach((classifier, index) => {
    boxes.set(classifier.id, { x: left + index * (CARD_WIDTH + GAP), y: top, w: CARD_WIDTH, h: estimatedHeight(classifier) });
  });
  return boxes;
}

function center(box: Box) {
  return { x: box.x + box.w / 2, y: box.y + box.h / 2 };
}

function StateBadge({ state }: { state: ProposalPreviewState | undefined }) {
  if (!state) {
    return null;
  }
  return (
    <span className={`proposal-preview-badge proposal-preview-badge--${state}`}>
      {STATE_LABELS[state].sign} {STATE_LABELS[state].label}
    </span>
  );
}

function stateProps(name: string, state: ProposalPreviewState | undefined) {
  return state
    ? { 'data-preview-item': name, 'data-preview-state': state, className: `proposal-preview-item proposal-preview-item--${state}` }
    : { 'data-preview-item': name, className: 'proposal-preview-item' };
}

function PreviewClassCard({ classifier, states }: { classifier: ClassifierResource; states: Record<string, ProposalPreviewState> }) {
  const classState = states[classifier.id];
  const props = stateProps(classifier.name, classState);
  return (
    <div {...props} className={`uml-class-card ${props.className}`} data-testid="proposal-preview-class">
      <div className="uml-class-heading">
        {classifier.kind === 'INTERFACE' && <div className="uml-stereotype">«interfaz»</div>}
        {classifier.kind === 'ENUMERATION' && <div className="uml-stereotype">«enumeración»</div>}
        {classifier.kind === 'PACKAGE' && <div className="uml-stereotype">«paquete»</div>}
        <div className={`uml-class-name ${classifier.isAbstract ? 'uml-class-name--abstract' : ''}`}>
          {classifier.isAbstract ? <i>{classifier.name}</i> : classifier.name}
        </div>
        <StateBadge state={classState} />
      </div>
      {classifier.attributes.length > 0 && (
        <div className="uml-compartment uml-compartment--attributes" aria-label="Atributos">
          {orderedAttributes(classifier).map((attribute) => {
            const state = classState === 'removed' ? undefined : states[attribute.id];
            const row = stateProps(attribute.name, state);
            return (
              <div {...row} className={`uml-member-row ${row.className}`} key={attribute.id}>
                <span className="uml-member-text">
                  {classifier.kind === 'ENUMERATION' ? attribute.name : formatAttribute(attribute)}
                </span>
                <StateBadge state={state} />
              </div>
            );
          })}
        </div>
      )}
      {classifier.operations.length > 0 && (
        <div className="uml-compartment uml-compartment--operations" aria-label="Operaciones">
          {orderedOperations(classifier).map((operation) => {
            const state = classState === 'removed' ? undefined : states[operation.id];
            const row = stateProps(operation.name, state);
            return (
              <div {...row} className={`uml-member-row ${row.className}`} key={operation.id}>
                <span className="uml-member-text">{formatOperation(operation)}</span>
                <StateBadge state={state} />
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function relationshipLabel(relationship: RelationshipResource): string {
  return formatRelationshipName(relationship.name, relationship.stereotype) || relationship.kind.toLowerCase();
}

/** Lines go under the cards; labels go in a later layer above them, so a card never hides a label. */
function PreviewEdge({ relationship, boxes, state, layer }: {
  relationship: RelationshipResource;
  boxes: Map<string, Box>;
  state: ProposalPreviewState | undefined;
  layer: 'line' | 'label';
}) {
  const ends = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal);
  const from = ends[0] && boxes.get(ends[0].classifierId);
  const to = ends[1] && boxes.get(ends[1].classifierId);
  if (!from || !to) {
    return null;
  }
  const start = center(from);
  const end = center(to);
  const label = relationshipLabel(relationship);
  const text = state ? `${STATE_LABELS[state].sign} ${label} (${STATE_LABELS[state].label.toLowerCase()})` : label;
  const props = stateProps(relationship.name ?? label, state);
  if (layer === 'label') {
    return (
      <text
        className={`proposal-preview-edge-label ${props.className}`}
        x={(start.x + end.x) / 2}
        y={(start.y + end.y) / 2 - 6}
        textAnchor="middle"
      >
        {text}
      </text>
    );
  }
  return (
    <g {...props} className={`proposal-preview-edge ${props.className}`}>
      <line x1={start.x} y1={start.y} x2={end.x} y2={end.y} />
    </g>
  );
}

export interface ProposalDiagramPreviewProps {
  proposal: ChatProposalResource;
  model: ModelResource;
  diagramNames: DiagramNames;
}

/**
 * Read-only picture of how the diagram would look if the proposal were accepted. It is computed
 * from a copy of the current model and never changes it, dispatches commands or persists layout.
 */
export function ProposalDiagramPreview({ proposal, model, diagramNames }: ProposalDiagramPreviewProps) {
  const preview = projectProposalPreview(model, proposal.operations);
  const boxes = layout(preview.model);
  const all = [...boxes.values()];
  const minX = Math.min(0, ...all.map((box) => box.x)) - PADDING;
  const minY = Math.min(0, ...all.map((box) => box.y)) - PADDING;
  const width = Math.max(CARD_WIDTH, ...all.map((box) => box.x + box.w)) - minX + PADDING;
  const height = Math.max(80, ...all.map((box) => box.y + box.h)) - minY + PADDING;
  const stopped = preview.stoppedAt === null ? null : proposal.operations[preview.stoppedAt];

  return (
    <div className="proposal-preview" aria-label="Vista previa del diagrama con los cambios sugeridos">
      <p className="proposal-preview-note">
        Vista previa tentativa: así quedaría el diagrama si cada paso se aplica. Al aceptar, un paso rechazado detiene los siguientes.
      </p>
      {proposal.baseRevision !== model.revision && (
        <p className="notice proposal-preview-warning" role="status">
          El diagrama cambió desde que se generó la sugerencia (revisión {proposal.baseRevision} → {model.revision}); la vista usa el diagrama actual.
        </p>
      )}
      {stopped && (
        <p className="notice proposal-preview-warning" role="status">
          Vista previa parcial: el paso {preview.stoppedAt! + 1} ({describeAssistantOperation(stopped, diagramNames)}) no está disponible en la vista previa; ni ese paso ni los siguientes se muestran.
        </p>
      )}
      <ul className="proposal-preview-legend" aria-label="Leyenda">
        {(Object.keys(STATE_LABELS) as ProposalPreviewState[]).map((state) => (
          <li key={state}><StateBadge state={state} /></li>
        ))}
      </ul>
      <div className="proposal-preview-scroll">
        <svg
          className="proposal-preview-canvas"
          role="group"
          width={width}
          height={height}
          viewBox={`${minX} ${minY} ${width} ${height}`}
        >
          {preview.model.relationships.map((relationship) => (
            <PreviewEdge
              key={relationship.id}
              relationship={relationship}
              boxes={boxes}
              state={preview.states[relationship.id]}
              layer="line"
            />
          ))}
          {preview.model.classifiers.map((classifier) => {
            const box = boxes.get(classifier.id)!;
            return (
              <foreignObject key={classifier.id} x={box.x} y={box.y} width={box.w} height={box.h}>
                <PreviewClassCard classifier={classifier} states={preview.states} />
              </foreignObject>
            );
          })}
          {preview.model.relationships.map((relationship) => (
            <PreviewEdge
              key={`${relationship.id}-label`}
              relationship={relationship}
              boxes={boxes}
              state={preview.states[relationship.id]}
              layer="label"
            />
          ))}
        </svg>
      </div>
    </div>
  );
}
