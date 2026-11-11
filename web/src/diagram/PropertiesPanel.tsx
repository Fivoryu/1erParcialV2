import { useEffect, useState, type FormEvent } from 'react';
import type {
  AttributeResource,
  ClassifierResource,
  ConstraintResource,
  ConstraintSeverity,
  ModelResource,
  NoteResource,
  NoteTargetType,
  OperationEnvelope,
  OperationResource,
  RelationshipResource,
} from '../api/types';
import {
  buildClassifierSetNamespaceRequest,
  buildClassifierSetStoreRequest,
  buildConstraintAddRequest,
  buildConstraintRemoveRequest,
  buildConstraintUpdateRequest,
  buildGeometrySetRequest,
  buildNoteLinkRequest,
  buildNoteUnlinkRequest,
  buildNoteUpdateRequest,
  NOTE_BODY_MAX,
  buildParameterAddRequest,
  buildParameterRemoveRequest,
  buildParameterUpdateRequest,
  buildRelationshipUpdateRequest,
  buildStyleSetRequest,
  DEFAULT_ROUTING,
  ATTRIBUTE_STEREOTYPES,
  buildClassifierSetStereotypeRequest,
  CLASSIFIER_STEREOTYPES,
  classifierAcceptsStereotype,
  DEPENDENCY_STEREOTYPES,
  OPERATION_STEREOTYPES,
  STEREOTYPE_LABELS,
  mergeViewStyleUpdate,
  RELATIONSHIP_KINDS,
  type ConstraintOwnerType,
  type ModelCommandRequest,
  type ParameterDirection,
  type RelationshipKind,
  type RoutingMode,
  type TypeReferenceInput,
  type ViewStyleValues,
} from './commands';
import type { CanvasSelection } from './selection';
import { associationClassCandidates, isAssociationKind } from './associationClass';
import {
  RANGE_MULTIPLICITY,
  formatStereotype,
  orderedAttributes,
  orderedOperations,
  rangeBoundsPayload,
  validateRangeBounds,
} from './uml-format';
import { describeStoreReason, STORE_LABELS, StoreBadge } from './StoreBadge';

interface PropertiesPanelProps {
  model: ModelResource;
  selection: CanvasSelection | null;
  onCommand: (request: ModelCommandRequest) => OperationEnvelope | null;
  onDelete: (selection: CanvasSelection) => void;
  onSelect: (selection: CanvasSelection) => void;
}

const MULTIPLICITIES = [
  { value: 'EXACTLY_ONE', label: '1' },
  { value: 'ZERO_OR_ONE', label: '0..1' },
  { value: 'ONE_OR_MANY', label: '1..*' },
  { value: 'ZERO_OR_MANY', label: '0..*' },
] as const;

/** The multiplicity choice being edited: the kind plus the text of the two RANGE bound inputs. */
interface MultiplicityDraft {
  kind: string;
  lower: string;
  upper: string;
}

function multiplicityDraft(source: {
  multiplicity: string;
  lowerBound?: number | null;
  upperBound?: number | null;
} | undefined): MultiplicityDraft {
  return {
    kind: source?.multiplicity ?? 'EXACTLY_ONE',
    lower: source?.lowerBound == null ? '' : String(source.lowerBound),
    upper: source?.upperBound == null ? '' : String(source.upperBound),
  };
}

function multiplicityError(draft: MultiplicityDraft): string | null {
  return draft.kind === RANGE_MULTIPLICITY ? validateRangeBounds(draft.lower, draft.upper) : null;
}

/** The select (with its Rango option) and, for a RANGE, the Mínimo / Máximo inputs with their inline error. */
function MultiplicityFields({
  prefix,
  draft,
  onChange,
}: {
  prefix: string;
  draft: MultiplicityDraft;
  onChange: (draft: MultiplicityDraft) => void;
}) {
  const error = multiplicityError(draft);
  return (
    <>
      <label className="field-label" htmlFor={`${prefix}-multiplicity`}>Multiplicidad</label>
      <select
        id={`${prefix}-multiplicity`}
        onChange={(event) => onChange({ ...draft, kind: event.target.value })}
        value={draft.kind}
      >
        {MULTIPLICITIES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        <option value={RANGE_MULTIPLICITY}>Rango</option>
      </select>
      {draft.kind === RANGE_MULTIPLICITY && (
        <div className="multiplicity-range-fields">
          <label className="field-label" htmlFor={`${prefix}-lower-bound`}>Mínimo</label>
          <input
            aria-invalid={error !== null}
            id={`${prefix}-lower-bound`}
            inputMode="numeric"
            onChange={(event) => onChange({ ...draft, lower: event.target.value })}
            value={draft.lower}
          />
          <label className="field-label" htmlFor={`${prefix}-upper-bound`}>Máximo <span>Vacío = ilimitado (*)</span></label>
          <input
            aria-invalid={error !== null}
            id={`${prefix}-upper-bound`}
            inputMode="numeric"
            onChange={(event) => onChange({ ...draft, upper: event.target.value })}
            placeholder="*"
            value={draft.upper}
          />
          {error && <p className="field-error" role="alert">{error}</p>}
        </div>
      )}
    </>
  );
}

const RELATIONSHIP_KIND_LABELS: Record<RelationshipKind, string> = {
  ASSOCIATION: 'Asociación',
  AGGREGATION: 'Agregación',
  COMPOSITION: 'Composición',
  GENERALIZATION: 'Generalización',
  INTERFACE_REALIZATION: 'Realización de interfaz',
  DEPENDENCY: 'Dependencia',
};

const NAVIGABILITY_OPTIONS = [
  { value: 'UNSPECIFIED', label: 'Sin especificar' },
  { value: 'NAVIGABLE', label: 'Navegable' },
  { value: 'NOT_NAVIGABLE', label: 'No navegable' },
] as const;

const CONSTRAINT_SEVERITIES: Array<{ value: ConstraintSeverity; label: string }> = [
  { value: 'ERROR', label: 'Error' },
  { value: 'WARNING', label: 'Advertencia' },
  { value: 'INFO', label: 'Información' },
];

function hasAncestor(model: ModelResource, classifierId: string, ancestorId: string): boolean {
  const classifiersById = new Map(model.classifiers.map((classifier) => [classifier.id, classifier]));
  const visited = new Set<string>();
  let current = classifiersById.get(classifierId);
  while (current) {
    if (current.id === ancestorId) {
      return true;
    }
    if (visited.has(current.id) || !current.namespaceId) {
      return false;
    }
    visited.add(current.id);
    current = classifiersById.get(current.namespaceId);
  }
  return false;
}

export function availableNamespacePackages(model: ModelResource, classifierId: string): ClassifierResource[] {
  return model.classifiers.filter((candidate) => candidate.kind === 'PACKAGE'
    && candidate.id !== classifierId
    && !hasAncestor(model, candidate.id, classifierId));
}

export function dispatchPackageSelection(
  onCommand: PropertiesPanelProps['onCommand'],
  classifierId: string,
  selectedNamespaceId: string,
): void {
  onCommand(buildClassifierSetNamespaceRequest(classifierId, selectedNamespaceId || null));
}

export function dispatchStoreSelection(
  onCommand: PropertiesPanelProps['onCommand'],
  classifierId: string,
  selectedStore: string,
): void {
  onCommand(buildClassifierSetStoreRequest(
    classifierId,
    selectedStore === 'SQL' || selectedStore === 'DOCUMENT' ? selectedStore : null,
  ));
}

/** A select over a fixed stereotype list; users never type stereotype syntax. Empty value means none. */
function StereotypeSelect({
  id,
  options,
  value,
  onChange,
}: {
  id: string;
  options: readonly string[];
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <>
      <label className="field-label" htmlFor={id}>Estereotipo</label>
      <select id={id} onChange={(event) => onChange(event.target.value)} value={value}>
        <option value="">(ninguno)</option>
        {options.map((name) => (
          <option key={name} value={name}>{STEREOTYPE_LABELS[name] ?? name}</option>
        ))}
      </select>
    </>
  );
}

/** The payload fragment of a member save: only a changed stereotype is sent, and none is an explicit null. */
export function stereotypeChange(stored: string | null | undefined, selected: string): { stereotype?: string | null } {
  const next = selected || null;
  return next === (stored ?? null) ? {} : { stereotype: next };
}

export function dispatchStereotypeSelection(
  onCommand: PropertiesPanelProps['onCommand'],
  classifierId: string,
  selected: string,
): void {
  onCommand(buildClassifierSetStereotypeRequest(classifierId, selected || null));
}

/** Lets the user fix where a class is stored, or hand the choice back to the automatic classifier. */
function StoreField({
  classifier,
  onCommand,
}: {
  classifier: ClassifierResource;
  onCommand: PropertiesPanelProps['onCommand'];
}) {
  const automaticLabel = classifier.storeOverride || !classifier.store
    ? 'Automático'
    : `Automático (${STORE_LABELS[classifier.store]})`;
  return (
    <div className="property-store" data-testid="classifier-store-field">
      <label className="field-label" htmlFor="classifier-store">Almacenamiento</label>
      <select
        className="classifier-store-select"
        id="classifier-store"
        onChange={(event) => dispatchStoreSelection(onCommand, classifier.id, event.target.value)}
        value={classifier.storeOverride ?? ''}
      >
        <option value="">{automaticLabel}</option>
        <option value="SQL">SQL (relacional)</option>
        <option value="DOCUMENT">Documento (MongoDB)</option>
      </select>
      {classifier.store && (
        <p className="property-store-reason" data-testid="classifier-store-reason">
          <StoreBadge manual={classifier.storeOverride !== undefined} reason={classifier.storeReason ?? ''} store={classifier.store} />
          <span>{classifier.storeReason ? describeStoreReason(classifier.storeReason) : ''}</span>
        </p>
      )}
    </div>
  );
}

function ConstraintEditor({
  constraint,
  onCommand,
}: {
  constraint: ConstraintResource;
  onCommand: PropertiesPanelProps['onCommand'];
}) {
  const [expression, setExpression] = useState(constraint.expression);
  const [severity, setSeverity] = useState<ConstraintSeverity>(constraint.severity);

  useEffect(() => {
    setExpression(constraint.expression);
    setSeverity(constraint.severity);
  }, [constraint.id, constraint.expression, constraint.severity]);

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanExpression = expression.trim();
    if (!cleanExpression) {
      return;
    }
    const values: Partial<Pick<ConstraintResource, 'expression' | 'severity'>> = {};
    if (cleanExpression !== constraint.expression) {
      values.expression = cleanExpression;
    }
    if (severity !== constraint.severity) {
      values.severity = severity;
    }
    if (Object.keys(values).length > 0) {
      onCommand(buildConstraintUpdateRequest(constraint.id, values));
    }
  }

  return (
    <form className="property-constraint-form" onSubmit={save}>
      <label className="field-label">
        Expresión
        <input aria-label="Expresión de la restricción" onChange={(event) => setExpression(event.target.value)}
          required value={expression} />
      </label>
      <label className="field-label">
        Gravedad
        <select aria-label="Gravedad de la restricción" onChange={(event) => setSeverity(event.target.value as ConstraintSeverity)}
          value={severity}>
          {CONSTRAINT_SEVERITIES.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      </label>
      <div className="property-constraint-actions">
        <button className="button button-secondary button-compact"
          disabled={expression.trim() === constraint.expression && severity === constraint.severity} type="submit">
          Guardar restricción
        </button>
        <button aria-label="Eliminar restricción" className="text-button danger-button"
          onClick={() => onCommand(buildConstraintRemoveRequest(constraint.id))} type="button">
          Eliminar
        </button>
      </div>
    </form>
  );
}

function ConstraintSection({
  ownerType,
  ownerId,
  constraints,
  onCommand,
  onAdded,
}: {
  ownerType: ConstraintOwnerType;
  ownerId: string;
  constraints: ConstraintResource[];
  onCommand: PropertiesPanelProps['onCommand'];
  onAdded?: (constraintId: string) => void;
}) {
  const [expression, setExpression] = useState('');
  const [severity, setSeverity] = useState<ConstraintSeverity>('ERROR');

  function addConstraint(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanExpression = expression.trim();
    if (!cleanExpression) {
      return;
    }
    const envelope = onCommand(buildConstraintAddRequest(ownerType, ownerId, cleanExpression, severity));
    const id = envelope?.payload.id;
    if (typeof id === 'string') {
      onAdded?.(id);
    }
    setExpression('');
    setSeverity('ERROR');
  }

  return (
    <section className="property-section" aria-labelledby="constraints-title">
      <h3 id="constraints-title">Restricciones</h3>
      {constraints.length > 0 ? (
        <ul className="property-constraint-list">
          {constraints.map((constraint) => (
            <li key={constraint.id}>
              <ConstraintEditor constraint={constraint} onCommand={onCommand} />
            </li>
          ))}
        </ul>
      ) : <p className="property-hint">Aún no hay restricciones.</p>}
      <form className="property-inline-form" onSubmit={addConstraint}>
        <label className="field-label" htmlFor="new-constraint-expression">Expresión</label>
        <input
          id="new-constraint-expression"
          onChange={(event) => setExpression(event.target.value)}
          placeholder="p. ej., balance >= 0"
          value={expression}
        />
        <label className="field-label" htmlFor="new-constraint-severity">Gravedad</label>
        <select id="new-constraint-severity" onChange={(event) => setSeverity(event.target.value as ConstraintSeverity)}
          value={severity}>
          {CONSTRAINT_SEVERITIES.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        <button className="button button-secondary button-compact" disabled={!expression.trim()} type="submit">
          Agregar restricción
        </button>
      </form>
    </section>
  );
}

/**
 * A collapsible list of a class's members: a class with many attributes would otherwise push the
 * rest of the panel out of view. Each row selects its member for editing.
 */
function MemberSection({
  title,
  addLabel,
  guideTarget,
  onAdd,
  rows,
}: {
  title: string;
  addLabel: string;
  guideTarget?: string | undefined;
  onAdd: () => void;
  rows: Array<{ id: string; name: string; detail: string; onSelect: () => void }>;
}) {
  return (
    <details className="property-section member-section" open>
      <summary>
        <strong>{title}</strong>
        <span className="member-count">{rows.length}</span>
        <span className="member-toggle-hint">Ocultar / mostrar</span>
      </summary>
      {rows.length > 0 ? (
        <ul className="member-list">
          {rows.map((row) => (
            <li key={row.id}>
              <button className="member-row" onClick={row.onSelect} type="button">
                <span>{row.name}</span>
                {row.detail && <span className="member-detail">{row.detail}</span>}
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="property-hint">Todavía no hay {title.toLowerCase()}.</p>
      )}
      <div className="property-actions">
        <button className="button button-secondary button-compact" data-guide-target={guideTarget} onClick={onAdd} type="button">{addLabel}</button>
      </div>
    </details>
  );
}

function typeReference(model: ModelResource, name: string): TypeReferenceInput {
  const classifier = model.classifiers.find((candidate) => candidate.name === name.trim());
  return classifier
    ? { classifierId: classifier.id }
    : { primitiveName: name.trim() };
}

function nextOrdinal(items: Array<{ ordinal: number }>): number {
  return items.reduce((maximum, item) => Math.max(maximum, item.ordinal), -1) + 1;
}

/** How a relationship is named where the user picks it: its name, or its kind and the classes it joins. */
export function relationshipLabel(model: ModelResource, relationship: RelationshipResource): string {
  const ends = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal)
    .map((end) => model.classifiers.find((candidate) => candidate.id === end.classifierId)?.name ?? '?');
  const joined = ends.join(' – ');
  return relationship.name ? `${relationship.name} (${joined})` : `${relationship.kind} (${joined})`;
}

/** Saves the body of a note only when it is not blank and actually changed; returns whether it sent. */
export function dispatchNoteBody(onCommand: PropertiesPanelProps['onCommand'], note: NoteResource, body: string): boolean {
  if (!body.trim() || body === note.body) {
    return false;
  }
  onCommand(buildNoteUpdateRequest(note.id, body));
  return true;
}

/** Links a note to the element chosen in the select, whose value is `TYPE:id`; returns whether it sent. */
export function dispatchNoteLink(onCommand: PropertiesPanelProps['onCommand'], noteId: string, choice: string): boolean {
  const [targetType, targetId] = choice.split(':');
  if ((targetType === 'CLASSIFIER' || targetType === 'RELATIONSHIP') && targetId) {
    onCommand(buildNoteLinkRequest(noteId, targetType, targetId));
    return true;
  }
  return false;
}

function NoteProperties({
  note,
  model,
  onCommand,
  onDelete,
}: {
  note: NoteResource;
  model: ModelResource;
  onCommand: PropertiesPanelProps['onCommand'];
  onDelete: () => void;
}) {
  const [body, setBody] = useState(note.body);
  const [pending, setPending] = useState('');

  useEffect(() => {
    setBody(note.body);
  }, [note.id, note.body]);

  const linked = note.annotatedElements.flatMap((element) => {
    if (element.targetType === 'CLASSIFIER') {
      const classifier = model.classifiers.find((candidate) => candidate.id === element.targetId);
      return classifier ? [{ ...element, label: classifier.name }] : [];
    }
    const relationship = model.relationships.find((candidate) => candidate.id === element.targetId);
    return relationship ? [{ ...element, label: relationshipLabel(model, relationship) }] : [];
  });
  const isLinked = (targetType: NoteTargetType, targetId: string) =>
    note.annotatedElements.some((element) => element.targetType === targetType && element.targetId === targetId);
  const candidates = [
    ...model.classifiers
      .filter((classifier) => !isLinked('CLASSIFIER', classifier.id))
      .map((classifier) => ({ key: `CLASSIFIER:${classifier.id}`, label: `Clase: ${classifier.name}` })),
    ...model.relationships
      .filter((relationship) => !isLinked('RELATIONSHIP', relationship.id))
      .map((relationship) => ({ key: `RELATIONSHIP:${relationship.id}`, label: `Relación: ${relationshipLabel(model, relationship)}` })),
  ];

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    dispatchNoteBody(onCommand, note, body);
  }

  function link() {
    if (dispatchNoteLink(onCommand, note.id, pending)) {
      setPending('');
    }
  }

  return (
    <>
      <div className="property-section">
        <div className="property-section-heading">
          <h3>Nota</h3>
        </div>
        <form className="property-constraint-form" onSubmit={save}>
          <label className="field-label">
            Texto de la nota
            <textarea aria-label="Texto de la nota" maxLength={NOTE_BODY_MAX} onChange={(event) => setBody(event.target.value)}
              rows={5} value={body} />
          </label>
          <div className="property-constraint-actions">
            <button className="button button-secondary button-compact"
              disabled={!body.trim() || body === note.body} type="submit">
              Guardar nota
            </button>
            <button className="text-button danger-button" onClick={onDelete} type="button">Eliminar nota</button>
          </div>
        </form>
      </div>
      <section className="property-section" aria-label="Elementos anotados">
        <div className="property-section-heading">
          <h3>Anota a</h3>
        </div>
        {linked.length > 0 ? (
          <ul className="property-constraint-list">
            {linked.map((element) => (
              <li key={`${element.targetType}:${element.targetId}`}>
                <span>{element.targetType === 'CLASSIFIER' ? 'Clase' : 'Relación'}: {element.label}</span>
                <button aria-label={`Quitar vínculo con ${element.label}`} className="text-button danger-button"
                  onClick={() => onCommand(buildNoteUnlinkRequest(note.id, element.targetType, element.targetId))} type="button">
                  Quitar
                </button>
              </li>
            ))}
          </ul>
        ) : <p className="property-hint">La nota no está vinculada a ningún elemento.</p>}
        {candidates.length > 0 && (
          <div className="property-constraint-actions">
            <select aria-label="Vincular la nota a" onChange={(event) => setPending(event.target.value)} value={pending}>
              <option value="">Vincular a…</option>
              {candidates.map((candidate) => (
                <option key={candidate.key} value={candidate.key}>{candidate.label}</option>
              ))}
            </select>
            <button className="button button-secondary button-compact" disabled={!pending} onClick={link} type="button">
              Vincular
            </button>
          </div>
        )}
      </section>
    </>
  );
}

function ClassifierProperties({
  classifier,
  model,
  onCommand,
  onDelete,
  onSelect,
}: {
  classifier: ClassifierResource;
  model: ModelResource;
  onCommand: PropertiesPanelProps['onCommand'];
  onDelete: () => void;
  onSelect: (selection: CanvasSelection) => void;
}) {
  const canBeAbstract = classifier.kind === 'CLASS' || classifier.kind === 'ABSTRACT_CLASS';
  const isPackage = classifier.kind === 'PACKAGE';
  const isEnumeration = classifier.kind === 'ENUMERATION';
  const namespacePackages = availableNamespacePackages(model, classifier.id);
  const namespaceValue = namespacePackages.some((candidate) => candidate.id === classifier.namespaceId)
    ? classifier.namespaceId ?? ''
    : '';

  function addAttribute() {
    // Enumeration literals are untyped identifiers; the backend rejects a type.
    const envelope = onCommand({
      type: 'attribute.add',
      targets: [{ scope: 'classifier', id: classifier.id }],
      payload: isEnumeration
        ? {
          name: `LITERAL_${classifier.attributes.length + 1}`,
          ordinal: nextOrdinal(classifier.attributes),
        }
        : {
          name: `attribute${classifier.attributes.length + 1}`,
          type: { primitiveName: 'String' },
          visibility: 'PUBLIC',
          multiplicity: 'EXACTLY_ONE',
          lowerBound: null,
          upperBound: null,
          defaultValue: null,
          ordinal: nextOrdinal(classifier.attributes),
        },
    });
    const id = envelope?.payload.id;
    if (typeof id === 'string') {
      onSelect({ kind: 'attribute', classifierId: classifier.id, elementId: id });
    }
  }

  function addOperation() {
    const envelope = onCommand({
      type: 'operation.add',
      targets: [{ scope: 'classifier', id: classifier.id }],
      payload: {
        name: `operation${classifier.operations.length + 1}`,
        returnType: null,
        visibility: 'PUBLIC',
        isAbstract: false,
        isStatic: false,
        ordinal: nextOrdinal(classifier.operations),
      },
    });
    const id = envelope?.payload.id;
    if (typeof id === 'string') {
      onSelect({ kind: 'operation', classifierId: classifier.id, elementId: id });
    }
  }

  return (
    <>
      <section className="property-section" aria-labelledby="classifier-properties-title">
        <div className="property-section-heading">
          <h3 id="classifier-properties-title">{isPackage ? 'Paquete' : isEnumeration ? 'Enumeración' : 'Clase'}</h3>
          <button className="text-button danger-button" onClick={onDelete} type="button">
            Eliminar {isPackage ? 'paquete' : isEnumeration ? 'enumeración' : 'clase'}
          </button>
        </div>
        <p className="property-summary">
          {isPackage ? 'Paquete' : isEnumeration ? 'Enumeración' : classifier.kind === 'INTERFACE' ? 'Interfaz' : classifier.isAbstract ? 'Clase abstracta' : 'Clase'}
        </p>
        <label className="field-label" htmlFor="classifier-package">Paquete</label>
        <select
          className="classifier-package-select"
          id="classifier-package"
          onChange={(event) => dispatchPackageSelection(onCommand, classifier.id, event.target.value)}
          value={namespaceValue}
        >
          <option value="">Ninguno</option>
          {namespacePackages.map((candidate) => (
            <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
          ))}
        </select>
        {canBeAbstract && (
          <label className="property-check">
            <input
              checked={classifier.isAbstract}
              onChange={(event) => onCommand({
                type: 'classifier.setAbstract',
                targets: [{ scope: 'classifier', id: classifier.id }],
                payload: { isAbstract: event.target.checked },
              })}
              type="checkbox"
            />
            <span>Clase abstracta</span>
          </label>
        )}
        {classifierAcceptsStereotype(classifier.kind) && (
          <StereotypeSelect
            id="classifier-stereotype"
            onChange={(value) => dispatchStereotypeSelection(onCommand, classifier.id, value)}
            options={CLASSIFIER_STEREOTYPES}
            value={classifier.stereotype ?? ''}
          />
        )}
        {canBeAbstract && <StoreField classifier={classifier} onCommand={onCommand} />}
      </section>

      {!isPackage && (
        <MemberSection
          title={isEnumeration ? 'Literales' : 'Atributos'}
          addLabel={isEnumeration ? 'Agregar literal' : 'Agregar atributo'}
          guideTarget={isEnumeration ? undefined : 'attribute-add'}
          onAdd={addAttribute}
          rows={orderedAttributes(classifier).map((attribute) => ({
            id: attribute.id,
            name: attribute.name,
            detail: isEnumeration ? '' : attribute.type ?? '',
            onSelect: () => onSelect({ kind: 'attribute', classifierId: classifier.id, elementId: attribute.id }),
          }))}
        />
      )}
      {!isPackage && !isEnumeration && (
        <MemberSection
          title="Operaciones"
          addLabel="Agregar operación"
          guideTarget="operation-add"
          onAdd={addOperation}
          rows={orderedOperations(classifier).map((operation) => ({
            id: operation.id,
            name: `${operation.name}()`,
            detail: operation.returnType ?? '',
            onSelect: () => onSelect({ kind: 'operation', classifierId: classifier.id, elementId: operation.id }),
          }))}
        />
      )}

      {!isPackage && (
        <ConstraintSection
          constraints={classifier.constraints}
          onAdded={(constraintId) => onSelect({ kind: 'constraint', classifierId: classifier.id, elementId: constraintId })}
          onCommand={onCommand}
          ownerId={classifier.id}
          ownerType="CLASSIFIER"
        />
      )}
    </>
  );
}

function RelationshipProperties({
  relationship,
  model,
  onCommand,
  onDelete,
}: {
  relationship: RelationshipResource;
  model: ModelResource;
  onCommand: PropertiesPanelProps['onCommand'];
  onDelete: () => void;
}) {
  const ends = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal);
  const sourceEnd = ends[0];
  const targetEnd = ends[1];
  const [name, setName] = useState(relationship.name ?? '');
  const [kind, setKind] = useState<RelationshipKind>(relationship.kind as RelationshipKind);
  const [stereotype, setStereotype] = useState(relationship.stereotype ?? '');
  const [associationClassId, setAssociationClassId] = useState(relationship.associationClassId ?? '');
  const [sourceRole, setSourceRole] = useState(sourceEnd?.role ?? '');
  const [sourceMultiplicity, setSourceMultiplicity] = useState(multiplicityDraft(sourceEnd));
  const [sourceNavigability, setSourceNavigability] = useState(sourceEnd?.navigability ?? 'UNSPECIFIED');
  const [targetRole, setTargetRole] = useState(targetEnd?.role ?? '');
  const [targetMultiplicity, setTargetMultiplicity] = useState(multiplicityDraft(targetEnd));
  const [targetNavigability, setTargetNavigability] = useState(targetEnd?.navigability ?? 'UNSPECIFIED');
  const sourceClassifier = model.classifiers.find((classifier) => classifier.id === sourceEnd?.classifierId);
  const targetClassifier = model.classifiers.find((classifier) => classifier.id === targetEnd?.classifierId);
  const defaultViewId = model.views.find((view) => view.is_default)?.id ?? model.views[0]?.id;
  const styleRow = model.view_style?.find((row) => row.viewId === defaultViewId
    && row.targetId === relationship.id);
  const currentStyle: ViewStyleValues = {
    routing: styleRow?.routing ?? DEFAULT_ROUTING,
    cornerRadius: styleRow?.cornerRadius ?? 0,
    strokeWidth: styleRow?.strokeWidth ?? 2,
    dashed: styleRow?.dashed ?? false,
    // ROR-02/T2: absent means "clear" downstream, so every style.set must carry the stored
    // adjustment (or an explicit null when the row has none).
    routeAdjustment: styleRow?.routeAdjustment ?? null,
  };

  function setStyle(override: Partial<ViewStyleValues>): void {
    if (!defaultViewId) {
      return;
    }
    onCommand(buildStyleSetRequest(
      mergeViewStyleUpdate(defaultViewId, relationship.id, currentStyle, override),
    ));
  }

  function resetRoute(): void {
    if (!defaultViewId) {
      return;
    }
    onCommand(buildGeometrySetRequest({
      targetType: 'RELATIONSHIP',
      viewId: defaultViewId,
      targetId: relationship.id,
      waypoints: [],
    }));
    setStyle({ routing: DEFAULT_ROUTING });
  }

  useEffect(() => {
    setName(relationship.name ?? '');
    setKind(relationship.kind as RelationshipKind);
    setStereotype(relationship.stereotype ?? '');
    setAssociationClassId(relationship.associationClassId ?? '');
    setSourceRole(sourceEnd?.role ?? '');
    setSourceMultiplicity(multiplicityDraft(sourceEnd));
    setSourceNavigability(sourceEnd?.navigability ?? 'UNSPECIFIED');
    setTargetRole(targetEnd?.role ?? '');
    setTargetMultiplicity(multiplicityDraft(targetEnd));
    setTargetNavigability(targetEnd?.navigability ?? 'UNSPECIFIED');
  }, [
    relationship.id,
    relationship.name,
    relationship.kind,
    relationship.stereotype,
    relationship.associationClassId,
    sourceEnd?.role,
    sourceEnd?.multiplicity,
    sourceEnd?.lowerBound,
    sourceEnd?.upperBound,
    sourceEnd?.navigability,
    targetEnd?.role,
    targetEnd?.multiplicity,
    targetEnd?.lowerBound,
    targetEnd?.upperBound,
    targetEnd?.navigability,
  ]);

  const endsInvalid = multiplicityError(sourceMultiplicity) !== null || multiplicityError(targetMultiplicity) !== null;

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!sourceEnd || !targetEnd || endsInvalid) {
      return;
    }
    onCommand(buildRelationshipUpdateRequest(relationship, {
      name: name.trim() || null,
      kind,
      stereotype: kind === 'DEPENDENCY' ? stereotype || null : null,
      associationClassId: isAssociationKind(kind) ? associationClassId || null : null,
      source: {
        role: sourceRole.trim() || null,
        multiplicity: sourceMultiplicity.kind,
        ...rangeBoundsPayload(sourceMultiplicity.kind, sourceMultiplicity.lower, sourceMultiplicity.upper),
        navigability: sourceNavigability,
      },
      target: {
        role: targetRole.trim() || null,
        multiplicity: targetMultiplicity.kind,
        ...rangeBoundsPayload(targetMultiplicity.kind, targetMultiplicity.lower, targetMultiplicity.upper),
        navigability: targetNavigability,
      },
    }));
  }

  function endFields(
    side: 'source' | 'target',
    classifierName: string | undefined,
    role: string,
    setRole: (value: string) => void,
    multiplicity: MultiplicityDraft,
    setMultiplicity: (value: MultiplicityDraft) => void,
    navigability: string,
    setNavigability: (value: string) => void,
  ) {
    const title = side === 'source' ? 'Extremo de origen' : 'Extremo de destino';
    const prefix = `relationship-${side}`;
    return (
      <fieldset className="relationship-end-fields">
        <legend>{title}{classifierName ? ` · ${classifierName}` : ''}</legend>
        <label className="field-label" htmlFor={`${prefix}-role`}>Rol</label>
        <input id={`${prefix}-role`} onChange={(event) => setRole(event.target.value)} value={role} />
        <MultiplicityFields draft={multiplicity} onChange={setMultiplicity} prefix={prefix} />
        <label className="field-label" htmlFor={`${prefix}-navigability`}>Navegabilidad</label>
        <select id={`${prefix}-navigability`} onChange={(event) => setNavigability(event.target.value)} value={navigability}>
          {NAVIGABILITY_OPTIONS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
      </fieldset>
    );
  }

  return (
    <>
      <section className="property-section" aria-labelledby="relationship-properties-title">
      <div className="property-section-heading">
        <div>
          <h3 id="relationship-properties-title">Relación</h3>
          <p className="property-summary">{sourceClassifier?.name ?? 'Clase'} a {targetClassifier?.name ?? 'Clase'}</p>
        </div>
        <button className="text-button danger-button" onClick={onDelete} type="button">Eliminar relación</button>
      </div>
      <form className="property-form" onSubmit={save}>
        <label className="field-label" htmlFor="relationship-name">Nombre <span>Opcional</span></label>
        <input id="relationship-name" onChange={(event) => setName(event.target.value)} value={name} />
        <label className="field-label" htmlFor="relationship-kind-property">Tipo</label>
        <select
          id="relationship-kind-property"
          onChange={(event) => setKind(event.target.value as RelationshipKind)}
          value={kind}
        >
          {RELATIONSHIP_KINDS.map((value) => (
            <option key={value} value={value}>{RELATIONSHIP_KIND_LABELS[value]}</option>
          ))}
        </select>
        {kind === 'DEPENDENCY' && (
          <>
            <label className="field-label" htmlFor="relationship-stereotype">Estereotipo <span>Opcional</span></label>
            <select
              id="relationship-stereotype"
              onChange={(event) => setStereotype(event.target.value)}
              value={stereotype}
            >
              <option value="">Ninguno</option>
              {DEPENDENCY_STEREOTYPES.map((value) => (
                <option key={value} value={value}>{formatStereotype(value)}</option>
              ))}
            </select>
          </>
        )}
        {isAssociationKind(kind) && (
          <>
            <label className="field-label" htmlFor="relationship-association-class">Clase de asociación <span>Opcional</span></label>
            <select
              id="relationship-association-class"
              onChange={(event) => setAssociationClassId(event.target.value)}
              value={associationClassId}
            >
              <option value="">Ninguna</option>
              {associationClassCandidates(model, relationship).map((candidate) => (
                <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
              ))}
            </select>
          </>
        )}
        {endFields('source', sourceClassifier?.name, sourceRole, setSourceRole, sourceMultiplicity,
          setSourceMultiplicity, sourceNavigability, setSourceNavigability)}
        {endFields('target', targetClassifier?.name, targetRole, setTargetRole, targetMultiplicity,
          setTargetMultiplicity, targetNavigability, setTargetNavigability)}
        <button className="button button-primary button-compact" disabled={endsInvalid} type="submit">Guardar relación</button>
      </form>
    </section>
      <section className="property-section" aria-labelledby="relationship-style-title">
        <div className="property-section-heading">
          <div>
            <h3 id="relationship-style-title">Ruta y estilo</h3>
            <p className="property-summary">Apariencia por vista, guardada con style.set</p>
          </div>
          <button className="text-button" onClick={resetRoute} type="button">Restablecer ruta</button>
        </div>
        <div className="property-form">
          <label className="field-label" htmlFor="relationship-routing">Trazado</label>
          <select
            id="relationship-routing"
            onChange={(event) => setStyle({ routing: event.target.value as RoutingMode })}
            value={currentStyle.routing}
          >
            <option value="STRAIGHT">Recto</option>
            <option value="FREE">Libre (puntos intermedios)</option>
            <option value="ORTHOGONAL">Ortogonal</option>
          </select>
          <label className="field-label" htmlFor="relationship-corner-radius">Radio de esquina</label>
          <input
            id="relationship-corner-radius"
            min={0}
            onChange={(event) => {
              const value = Number(event.target.value);
              if (Number.isFinite(value)) {
                setStyle({ cornerRadius: Math.max(0, Math.floor(value)) });
              }
            }}
            type="number"
            value={currentStyle.cornerRadius}
          />
          <label className="field-label" htmlFor="relationship-stroke-width">Grosor de línea</label>
          <input
            id="relationship-stroke-width"
            min={1}
            onChange={(event) => {
              const value = Number(event.target.value);
              if (Number.isFinite(value)) {
                setStyle({ strokeWidth: Math.max(1, Math.floor(value)) });
              }
            }}
            type="number"
            value={currentStyle.strokeWidth}
          />
          <label className="field-label" htmlFor="relationship-dashed">
            <input
              checked={currentStyle.dashed}
              id="relationship-dashed"
              onChange={(event) => setStyle({ dashed: event.target.checked })}
              type="checkbox"
            />
            {' '}Línea discontinua
          </label>
        </div>
      </section>
      <ConstraintSection
        constraints={relationship.constraints}
        onCommand={onCommand}
        ownerId={relationship.id}
        ownerType="RELATIONSHIP"
      />
    </>
  );
}

function AttributeProperties({
  attribute,
  classifier,
  model,
  onCommand,
  onDelete,
}: {
  attribute: AttributeResource;
  classifier: ClassifierResource;
  model: ModelResource;
  onCommand: PropertiesPanelProps['onCommand'];
  onDelete: () => void;
}) {
  const [type, setType] = useState(attribute.type ?? '');
  const [visibility, setVisibility] = useState(attribute.visibility);
  const [multiplicity, setMultiplicity] = useState(multiplicityDraft(attribute));
  const [defaultValue, setDefaultValue] = useState(attribute.defaultValue ?? '');
  const [stereotype, setStereotype] = useState(attribute.stereotype ?? '');

  useEffect(() => {
    setType(attribute.type ?? '');
    setVisibility(attribute.visibility);
    setMultiplicity(multiplicityDraft(attribute));
    setDefaultValue(attribute.defaultValue ?? '');
    setStereotype(attribute.stereotype ?? '');
  }, [attribute.stereotype, attribute.id, attribute.type, attribute.visibility, attribute.multiplicity,
    attribute.lowerBound, attribute.upperBound, attribute.defaultValue]);

  const multiplicityInvalid = multiplicityError(multiplicity) !== null;

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanType = type.trim();
    if (!cleanType || multiplicityInvalid) {
      return;
    }
    onCommand({
      type: 'attribute.update',
      targets: [{ scope: 'attribute', id: attribute.id }],
      payload: {
        type: typeReference(model, cleanType),
        visibility,
        multiplicity: multiplicity.kind,
        ...rangeBoundsPayload(multiplicity.kind, multiplicity.lower, multiplicity.upper),
        defaultValue: defaultValue === '' ? null : defaultValue,
        ...stereotypeChange(attribute.stereotype, stereotype),
      },
    });
  }

  if (classifier.kind === 'ENUMERATION') {
    // A literal is only its name (renamed on the canvas); it has no type,
    // visibility, multiplicity or default value.
    return (
      <div className="property-section-heading">
        <div>
          <h3>Literal de enumeración</h3>
          <p className="property-summary">{attribute.name} en {classifier.name}</p>
        </div>
        <button className="text-button danger-button" onClick={onDelete} type="button">Eliminar</button>
      </div>
    );
  }

  return (
    <>
      <div className="property-section-heading">
        <div>
          <h3>Atributo</h3>
          <p className="property-summary">En {classifier.name}</p>
        </div>
        <button className="text-button danger-button" onClick={onDelete} type="button">Eliminar</button>
      </div>
      <form className="property-form" onSubmit={save}>
        <label className="field-label" htmlFor="attribute-type">Tipo</label>
        <input id="attribute-type" onChange={(event) => setType(event.target.value)} required value={type} />

        <label className="field-label" htmlFor="attribute-visibility">Visibilidad</label>
        <select id="attribute-visibility" onChange={(event) => setVisibility(event.target.value)} value={visibility}>
          <option value="PUBLIC">Público (+)</option>
          <option value="PRIVATE">Privado (-)</option>
          <option value="PROTECTED">Protegido (#)</option>
          <option value="PACKAGE">Paquete (~)</option>
        </select>

        <StereotypeSelect id="attribute-stereotype" onChange={setStereotype} options={ATTRIBUTE_STEREOTYPES} value={stereotype} />

        <MultiplicityFields draft={multiplicity} onChange={setMultiplicity} prefix="attribute" />

        <label className="field-label" htmlFor="attribute-default">Valor predeterminado</label>
        <input id="attribute-default" onChange={(event) => setDefaultValue(event.target.value)} value={defaultValue} />
        <button className="button button-primary button-compact" disabled={multiplicityInvalid} type="submit">Guardar atributo</button>
      </form>
    </>
  );
}

function ParameterProperties({
  parameter,
  index,
  total,
  model,
  onCommand,
}: {
  parameter: OperationResource['parameters'][number];
  index: number;
  total: number;
  model: ModelResource;
  onCommand: PropertiesPanelProps['onCommand'];
}) {
  const [name, setName] = useState(parameter.name);
  const [type, setType] = useState(parameter.type ?? '');
  const [direction, setDirection] = useState<ParameterDirection>(
    parameter.direction === 'OUT' || parameter.direction === 'INOUT' ? parameter.direction : 'IN',
  );

  useEffect(() => {
    setName(parameter.name);
    setType(parameter.type ?? '');
    setDirection(parameter.direction === 'OUT' || parameter.direction === 'INOUT' ? parameter.direction : 'IN');
  }, [parameter.id, parameter.name, parameter.type, parameter.direction]);

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanName = name.trim();
    const cleanType = type.trim();
    if (!cleanName || !cleanType) {
      return;
    }
    onCommand(buildParameterUpdateRequest(parameter.id, {
      name: cleanName,
      type: typeReference(model, cleanType),
      direction,
    }));
  }

  return (
    <li className="parameter-property">
      <div className="parameter-property-heading">
        <strong>{`Parámetro ${index + 1}`}</strong>
        <div className="parameter-order-actions">
          <button
            aria-label={`Subir parámetro ${index + 1}`}
            className="text-button"
            disabled={index === 0}
            onClick={() => onCommand(buildParameterUpdateRequest(parameter.id, { ordinal: index - 1 }))}
            type="button"
          >Subir</button>
          <button
            aria-label={`Bajar parámetro ${index + 1}`}
            className="text-button"
            disabled={index === total - 1}
            onClick={() => onCommand(buildParameterUpdateRequest(parameter.id, { ordinal: index + 1 }))}
            type="button"
          >Bajar</button>
          <button
            aria-label={`Quitar parámetro ${index + 1}`}
            className="text-button danger-button"
            onClick={() => onCommand(buildParameterRemoveRequest(parameter.id))}
            type="button"
          >Quitar parámetro</button>
        </div>
      </div>
      <form className="parameter-property-form" onSubmit={save}>
        <label className="field-label">
          Nombre
          <input onChange={(event) => setName(event.target.value)} required value={name} />
        </label>
        <label className="field-label">
          Tipo
          <input onChange={(event) => setType(event.target.value)} required value={type} />
        </label>
        <label className="field-label">
          Dirección
          <select onChange={(event) => setDirection(event.target.value as ParameterDirection)} value={direction}>
            <option value="IN">Entrada</option>
            <option value="OUT">Salida</option>
            <option value="INOUT">Entrada y salida</option>
          </select>
        </label>
        <button className="button button-secondary button-compact" type="submit">Guardar parámetro</button>
      </form>
    </li>
  );
}

function OperationProperties({
  operation,
  classifier,
  model,
  onCommand,
  onDelete,
}: {
  operation: OperationResource;
  classifier: ClassifierResource;
  model: ModelResource;
  onCommand: PropertiesPanelProps['onCommand'];
  onDelete: () => void;
}) {
  const [returnType, setReturnType] = useState(operation.returnType ?? '');
  const [visibility, setVisibility] = useState(operation.visibility);
  const [isAbstract, setIsAbstract] = useState(operation.isAbstract);
  const [isStatic, setIsStatic] = useState(operation.isStatic);
  const [stereotype, setStereotype] = useState(operation.stereotype ?? '');

  useEffect(() => {
    setReturnType(operation.returnType ?? '');
    setVisibility(operation.visibility);
    setIsAbstract(operation.isAbstract);
    setIsStatic(operation.isStatic);
    setStereotype(operation.stereotype ?? '');
  }, [operation.stereotype, operation.id, operation.returnType, operation.visibility, operation.isAbstract, operation.isStatic]);

  function addParameter() {
    onCommand(buildParameterAddRequest(operation.id, {
      name: `parameter${operation.parameters.length + 1}`,
      type: { primitiveName: 'String' },
      direction: 'IN',
      ordinal: nextOrdinal(operation.parameters),
    }));
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onCommand({
      type: 'operation.update',
      targets: [{ scope: 'operation', id: operation.id }],
      payload: {
        returnType: returnType.trim() ? typeReference(model, returnType) : null,
        visibility,
        isAbstract,
        isStatic,
        ...stereotypeChange(operation.stereotype, stereotype),
      },
    });
  }

  return (
    <>
      <div className="property-section-heading">
        <div>
          <h3>Operación</h3>
          <p className="property-summary">En {classifier.name}</p>
        </div>
        <button className="text-button danger-button" onClick={onDelete} type="button">Eliminar</button>
      </div>
      <form className="property-form" onSubmit={save}>
        <label className="field-label" htmlFor="operation-return-type">Tipo de retorno <span>Opcional</span></label>
        <input id="operation-return-type" onChange={(event) => setReturnType(event.target.value)} value={returnType} />

        <label className="field-label" htmlFor="operation-visibility">Visibilidad</label>
        <select id="operation-visibility" onChange={(event) => setVisibility(event.target.value)} value={visibility}>
          <option value="PUBLIC">Público (+)</option>
          <option value="PRIVATE">Privado (-)</option>
          <option value="PROTECTED">Protegido (#)</option>
          <option value="PACKAGE">Paquete (~)</option>
        </select>

        <StereotypeSelect id="operation-stereotype" onChange={setStereotype} options={OPERATION_STEREOTYPES} value={stereotype} />

        <label className="property-check">
          <input checked={isAbstract} onChange={(event) => setIsAbstract(event.target.checked)} type="checkbox" />
          <span>Operación abstracta</span>
        </label>
        <label className="property-check">
          <input checked={isStatic} onChange={(event) => setIsStatic(event.target.checked)} type="checkbox" />
          <span>Operación estática</span>
        </label>
        <button className="button button-primary button-compact" type="submit">Guardar operación</button>
      </form>
      <section className="parameter-properties" aria-labelledby="operation-parameters-title">
        <div className="parameter-properties-heading">
          <h4 id="operation-parameters-title">Parámetros</h4>
          <button className="button button-secondary button-compact" onClick={addParameter} type="button">
            Agregar parámetro
          </button>
        </div>
        {operation.parameters.length > 0 ? (
          <ol className="parameter-property-list">
            {[...operation.parameters]
              .sort((left, right) => left.ordinal - right.ordinal)
              .map((parameter, index, ordered) => (
                <ParameterProperties
                  key={parameter.id}
                  index={index}
                  model={model}
                  onCommand={onCommand}
                  parameter={parameter}
                  total={ordered.length}
                />
              ))}
          </ol>
        ) : <p className="property-hint">Aún no hay parámetros.</p>}
      </section>
    </>
  );
}

export default function PropertiesPanel({ model, selection, onCommand, onDelete, onSelect }: PropertiesPanelProps) {
  if (!selection) {
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Detalles</p>
        <h2 id="properties-title">Propiedades</h2>
        <p className="property-hint">Selecciona una clase o uno de sus elementos para verlo y editarlo.</p>
      </aside>
    );
  }

  if (selection.kind === 'note') {
    const note = (model.notes ?? []).find((candidate) => candidate.id === selection.noteId);
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Detalles</p>
        <h2 id="properties-title">Propiedades</h2>
        {note ? (
          <NoteProperties
            note={note}
            model={model}
            onCommand={onCommand}
            onDelete={() => onDelete(selection)}
          />
        ) : <p className="property-hint">Esta nota ya no está en el modelo.</p>}
      </aside>
    );
  }

  if (selection.kind === 'relationship') {
    const relationship = model.relationships.find((candidate) => candidate.id === selection.relationshipId);
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Detalles</p>
        <h2 id="properties-title">Propiedades</h2>
        {relationship ? (
          <RelationshipProperties
            relationship={relationship}
            model={model}
            onCommand={onCommand}
            onDelete={() => onDelete(selection)}
          />
        ) : <p className="property-hint">Esta relación ya no está en el modelo.</p>}
      </aside>
    );
  }

  const classifier = model.classifiers.find((candidate) => candidate.id === selection.classifierId);
  if (!classifier) {
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Detalles</p>
        <h2 id="properties-title">Propiedades</h2>
        <p className="property-hint">Este elemento ya no está en el modelo.</p>
      </aside>
    );
  }

  if (selection.kind === 'classifier') {
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Detalles</p>
        <h2 id="properties-title">Propiedades</h2>
        <ClassifierProperties
          classifier={classifier}
          model={model}
          onCommand={onCommand}
          onDelete={() => onDelete(selection)}
          onSelect={onSelect}
        />
      </aside>
    );
  }

  if (selection.kind === 'attribute') {
    const attribute = classifier.attributes.find((candidate) => candidate.id === selection.elementId);
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Detalles</p>
        <h2 id="properties-title">Propiedades</h2>
        {attribute ? (
          <AttributeProperties
            attribute={attribute}
            classifier={classifier}
            model={model}
            onCommand={onCommand}
            onDelete={() => onDelete(selection)}
          />
        ) : <p className="property-hint">Este elemento ya no está en el modelo.</p>}
      </aside>
    );
  }

  if (selection.kind === 'operation') {
    const operation = classifier.operations.find((candidate) => candidate.id === selection.elementId);
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Detalles</p>
        <h2 id="properties-title">Propiedades</h2>
        {operation ? (
          <OperationProperties
            operation={operation}
            classifier={classifier}
            model={model}
            onCommand={onCommand}
            onDelete={() => onDelete(selection)}
          />
        ) : <p className="property-hint">Este elemento ya no está en el modelo.</p>}
      </aside>
    );
  }

  const constraint = classifier.constraints.find((candidate) => candidate.id === selection.elementId);
  return (
    <aside className="surface properties-panel" aria-labelledby="properties-title">
      <p className="eyebrow">Detalles</p>
      <h2 id="properties-title">Propiedades</h2>
      <div className="property-section">
        <div className="property-section-heading">
          <h3>Restricción</h3>
        </div>
        {constraint
          ? <ConstraintEditor constraint={constraint} onCommand={onCommand} />
          : <p className="property-hint">This element is no longer in the model.</p>}
      </div>
    </aside>
  );
}
