import { useEffect, useState, type FormEvent } from 'react';
import type {
  AttributeResource,
  ClassifierResource,
  ConstraintResource,
  ConstraintSeverity,
  ModelResource,
  OperationEnvelope,
  OperationResource,
  RelationshipResource,
} from '../api/types';
import {
  buildClassifierSetNamespaceRequest,
  buildConstraintAddRequest,
  buildConstraintRemoveRequest,
  buildConstraintUpdateRequest,
  buildGeometrySetRequest,
  buildParameterAddRequest,
  buildParameterRemoveRequest,
  buildParameterUpdateRequest,
  buildRelationshipUpdateRequest,
  buildStyleSetRequest,
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

const RELATIONSHIP_KIND_LABELS: Record<RelationshipKind, string> = {
  ASSOCIATION: 'Association',
  AGGREGATION: 'Aggregation',
  COMPOSITION: 'Composition',
  GENERALIZATION: 'Generalization',
  INTERFACE_REALIZATION: 'Interface realization',
  DEPENDENCY: 'Dependency',
};

const NAVIGABILITY_OPTIONS = [
  { value: 'UNSPECIFIED', label: 'Unspecified' },
  { value: 'NAVIGABLE', label: 'Navigable' },
  { value: 'NOT_NAVIGABLE', label: 'Not navigable' },
] as const;

const CONSTRAINT_SEVERITIES: Array<{ value: ConstraintSeverity; label: string }> = [
  { value: 'ERROR', label: 'Error' },
  { value: 'WARNING', label: 'Warning' },
  { value: 'INFO', label: 'Info' },
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
        Expression
        <input aria-label="Constraint expression" onChange={(event) => setExpression(event.target.value)}
          required value={expression} />
      </label>
      <label className="field-label">
        Severity
        <select aria-label="Constraint severity" onChange={(event) => setSeverity(event.target.value as ConstraintSeverity)}
          value={severity}>
          {CONSTRAINT_SEVERITIES.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      </label>
      <div className="property-constraint-actions">
        <button className="button button-secondary button-compact"
          disabled={expression.trim() === constraint.expression && severity === constraint.severity} type="submit">
          Save constraint
        </button>
        <button aria-label="Delete constraint" className="text-button danger-button"
          onClick={() => onCommand(buildConstraintRemoveRequest(constraint.id))} type="button">
          Delete
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
      <h3 id="constraints-title">Constraints</h3>
      {constraints.length > 0 ? (
        <ul className="property-constraint-list">
          {constraints.map((constraint) => (
            <li key={constraint.id}>
              <ConstraintEditor constraint={constraint} onCommand={onCommand} />
            </li>
          ))}
        </ul>
      ) : <p className="property-hint">No constraints yet.</p>}
      <form className="property-inline-form" onSubmit={addConstraint}>
        <label className="field-label" htmlFor="new-constraint-expression">Expression</label>
        <input
          id="new-constraint-expression"
          onChange={(event) => setExpression(event.target.value)}
          placeholder="e.g. balance >= 0"
          value={expression}
        />
        <label className="field-label" htmlFor="new-constraint-severity">Severity</label>
        <select id="new-constraint-severity" onChange={(event) => setSeverity(event.target.value as ConstraintSeverity)}
          value={severity}>
          {CONSTRAINT_SEVERITIES.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        <button className="button button-secondary button-compact" disabled={!expression.trim()} type="submit">
          Add constraint
        </button>
      </form>
    </section>
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
  const namespacePackages = availableNamespacePackages(model, classifier.id);
  const namespaceValue = namespacePackages.some((candidate) => candidate.id === classifier.namespaceId)
    ? classifier.namespaceId ?? ''
    : '';

  function addAttribute() {
    const envelope = onCommand({
      type: 'attribute.add',
      targets: [{ scope: 'classifier', id: classifier.id }],
      payload: {
        name: `attribute${classifier.attributes.length + 1}`,
        type: { primitiveName: 'String' },
        visibility: 'PUBLIC',
        multiplicity: 'EXACTLY_ONE',
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
          <h3 id="classifier-properties-title">{isPackage ? 'Package' : 'Class'}</h3>
          <button className="text-button danger-button" onClick={onDelete} type="button">
            Delete {isPackage ? 'package' : 'class'}
          </button>
        </div>
        <p className="property-summary">
          {isPackage ? 'Package' : classifier.kind === 'INTERFACE' ? 'Interface' : classifier.isAbstract ? 'Abstract class' : 'Class'}
        </p>
        <label className="field-label" htmlFor="classifier-package">Package</label>
        <select
          className="classifier-package-select"
          id="classifier-package"
          onChange={(event) => dispatchPackageSelection(onCommand, classifier.id, event.target.value)}
          value={namespaceValue}
        >
          <option value="">None</option>
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
            <span>Abstract class</span>
          </label>
        )}
        {!isPackage && (
          <div className="property-actions">
            <button className="button button-secondary button-compact" onClick={addAttribute} type="button">Add attribute</button>
            <button className="button button-secondary button-compact" onClick={addOperation} type="button">Add operation</button>
          </div>
        )}
      </section>

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
  const [sourceRole, setSourceRole] = useState(sourceEnd?.role ?? '');
  const [sourceMultiplicity, setSourceMultiplicity] = useState(sourceEnd?.multiplicity ?? 'EXACTLY_ONE');
  const [sourceNavigability, setSourceNavigability] = useState(sourceEnd?.navigability ?? 'UNSPECIFIED');
  const [targetRole, setTargetRole] = useState(targetEnd?.role ?? '');
  const [targetMultiplicity, setTargetMultiplicity] = useState(targetEnd?.multiplicity ?? 'EXACTLY_ONE');
  const [targetNavigability, setTargetNavigability] = useState(targetEnd?.navigability ?? 'UNSPECIFIED');
  const sourceClassifier = model.classifiers.find((classifier) => classifier.id === sourceEnd?.classifierId);
  const targetClassifier = model.classifiers.find((classifier) => classifier.id === targetEnd?.classifierId);
  const defaultViewId = model.views.find((view) => view.is_default)?.id ?? model.views[0]?.id;
  const styleRow = model.view_style?.find((row) => row.viewId === defaultViewId
    && row.targetId === relationship.id);
  const currentStyle: ViewStyleValues = {
    routing: styleRow?.routing ?? 'STRAIGHT',
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
    setStyle({ routing: 'STRAIGHT' });
  }

  useEffect(() => {
    setName(relationship.name ?? '');
    setKind(relationship.kind as RelationshipKind);
    setSourceRole(sourceEnd?.role ?? '');
    setSourceMultiplicity(sourceEnd?.multiplicity ?? 'EXACTLY_ONE');
    setSourceNavigability(sourceEnd?.navigability ?? 'UNSPECIFIED');
    setTargetRole(targetEnd?.role ?? '');
    setTargetMultiplicity(targetEnd?.multiplicity ?? 'EXACTLY_ONE');
    setTargetNavigability(targetEnd?.navigability ?? 'UNSPECIFIED');
  }, [
    relationship.id,
    relationship.name,
    relationship.kind,
    sourceEnd?.role,
    sourceEnd?.multiplicity,
    sourceEnd?.navigability,
    targetEnd?.role,
    targetEnd?.multiplicity,
    targetEnd?.navigability,
  ]);

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!sourceEnd || !targetEnd) {
      return;
    }
    onCommand(buildRelationshipUpdateRequest(relationship, {
      name: name.trim() || null,
      kind,
      source: {
        role: sourceRole.trim() || null,
        multiplicity: sourceMultiplicity,
        navigability: sourceNavigability,
      },
      target: {
        role: targetRole.trim() || null,
        multiplicity: targetMultiplicity,
        navigability: targetNavigability,
      },
    }));
  }

  function endFields(
    side: 'source' | 'target',
    classifierName: string | undefined,
    role: string,
    setRole: (value: string) => void,
    multiplicity: string,
    setMultiplicity: (value: string) => void,
    navigability: string,
    setNavigability: (value: string) => void,
  ) {
    const title = side === 'source' ? 'Source end' : 'Target end';
    const prefix = `relationship-${side}`;
    return (
      <fieldset className="relationship-end-fields">
        <legend>{title}{classifierName ? ` · ${classifierName}` : ''}</legend>
        <label className="field-label" htmlFor={`${prefix}-role`}>Role</label>
        <input id={`${prefix}-role`} onChange={(event) => setRole(event.target.value)} value={role} />
        <label className="field-label" htmlFor={`${prefix}-multiplicity`}>Multiplicity</label>
        <select id={`${prefix}-multiplicity`} onChange={(event) => setMultiplicity(event.target.value)} value={multiplicity}>
          {MULTIPLICITIES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
        <label className="field-label" htmlFor={`${prefix}-navigability`}>Navigability</label>
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
          <h3 id="relationship-properties-title">Relationship</h3>
          <p className="property-summary">{sourceClassifier?.name ?? 'Class'} to {targetClassifier?.name ?? 'Class'}</p>
        </div>
        <button className="text-button danger-button" onClick={onDelete} type="button">Delete relationship</button>
      </div>
      <form className="property-form" onSubmit={save}>
        <label className="field-label" htmlFor="relationship-name">Name <span>Optional</span></label>
        <input id="relationship-name" onChange={(event) => setName(event.target.value)} value={name} />
        <label className="field-label" htmlFor="relationship-kind-property">Kind</label>
        <select
          id="relationship-kind-property"
          onChange={(event) => setKind(event.target.value as RelationshipKind)}
          value={kind}
        >
          {RELATIONSHIP_KINDS.map((value) => (
            <option key={value} value={value}>{RELATIONSHIP_KIND_LABELS[value]}</option>
          ))}
        </select>
        {endFields('source', sourceClassifier?.name, sourceRole, setSourceRole, sourceMultiplicity,
          setSourceMultiplicity, sourceNavigability, setSourceNavigability)}
        {endFields('target', targetClassifier?.name, targetRole, setTargetRole, targetMultiplicity,
          setTargetMultiplicity, targetNavigability, setTargetNavigability)}
        <button className="button button-primary button-compact" type="submit">Save relationship</button>
      </form>
    </section>
      <section className="property-section" aria-labelledby="relationship-style-title">
        <div className="property-section-heading">
          <div>
            <h3 id="relationship-style-title">Route &amp; style</h3>
            <p className="property-summary">Per-view appearance, saved with style.set</p>
          </div>
          <button className="text-button" onClick={resetRoute} type="button">Reset route</button>
        </div>
        <div className="property-form">
          <label className="field-label" htmlFor="relationship-routing">Routing</label>
          <select
            id="relationship-routing"
            onChange={(event) => setStyle({ routing: event.target.value as RoutingMode })}
            value={currentStyle.routing}
          >
            <option value="STRAIGHT">Straight</option>
            <option value="FREE">Free (waypoints)</option>
            <option value="ORTHOGONAL">Orthogonal</option>
          </select>
          <label className="field-label" htmlFor="relationship-corner-radius">Corner radius</label>
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
          <label className="field-label" htmlFor="relationship-stroke-width">Stroke width</label>
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
            {' '}Dashed line
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
  const [multiplicity, setMultiplicity] = useState(attribute.multiplicity);
  const [defaultValue, setDefaultValue] = useState(attribute.defaultValue ?? '');

  useEffect(() => {
    setType(attribute.type ?? '');
    setVisibility(attribute.visibility);
    setMultiplicity(attribute.multiplicity);
    setDefaultValue(attribute.defaultValue ?? '');
  }, [attribute.id, attribute.type, attribute.visibility, attribute.multiplicity, attribute.defaultValue]);

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanType = type.trim();
    if (!cleanType) {
      return;
    }
    onCommand({
      type: 'attribute.update',
      targets: [{ scope: 'attribute', id: attribute.id }],
      payload: {
        type: typeReference(model, cleanType),
        visibility,
        multiplicity,
        defaultValue: defaultValue === '' ? null : defaultValue,
      },
    });
  }

  return (
    <>
      <div className="property-section-heading">
        <div>
          <h3>Attribute</h3>
          <p className="property-summary">In {classifier.name}</p>
        </div>
        <button className="text-button danger-button" onClick={onDelete} type="button">Delete</button>
      </div>
      <form className="property-form" onSubmit={save}>
        <label className="field-label" htmlFor="attribute-type">Type</label>
        <input id="attribute-type" onChange={(event) => setType(event.target.value)} required value={type} />

        <label className="field-label" htmlFor="attribute-visibility">Visibility</label>
        <select id="attribute-visibility" onChange={(event) => setVisibility(event.target.value)} value={visibility}>
          <option value="PUBLIC">Public (+)</option>
          <option value="PRIVATE">Private (-)</option>
        </select>

        <label className="field-label" htmlFor="attribute-multiplicity">Multiplicity</label>
        <select id="attribute-multiplicity" onChange={(event) => setMultiplicity(event.target.value)} value={multiplicity}>
          {MULTIPLICITIES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>

        <label className="field-label" htmlFor="attribute-default">Default value</label>
        <input id="attribute-default" onChange={(event) => setDefaultValue(event.target.value)} value={defaultValue} />
        <button className="button button-primary button-compact" type="submit">Save attribute</button>
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
        <strong>{`Parameter ${index + 1}`}</strong>
        <div className="parameter-order-actions">
          <button
            aria-label={`Move parameter ${index + 1} up`}
            className="text-button"
            disabled={index === 0}
            onClick={() => onCommand(buildParameterUpdateRequest(parameter.id, { ordinal: index - 1 }))}
            type="button"
          >Move up</button>
          <button
            aria-label={`Move parameter ${index + 1} down`}
            className="text-button"
            disabled={index === total - 1}
            onClick={() => onCommand(buildParameterUpdateRequest(parameter.id, { ordinal: index + 1 }))}
            type="button"
          >Move down</button>
          <button
            aria-label={`Remove parameter ${index + 1}`}
            className="text-button danger-button"
            onClick={() => onCommand(buildParameterRemoveRequest(parameter.id))}
            type="button"
          >Remove parameter</button>
        </div>
      </div>
      <form className="parameter-property-form" onSubmit={save}>
        <label className="field-label">
          Name
          <input onChange={(event) => setName(event.target.value)} required value={name} />
        </label>
        <label className="field-label">
          Type
          <input onChange={(event) => setType(event.target.value)} required value={type} />
        </label>
        <label className="field-label">
          Direction
          <select onChange={(event) => setDirection(event.target.value as ParameterDirection)} value={direction}>
            <option value="IN">In</option>
            <option value="OUT">Out</option>
            <option value="INOUT">Inout</option>
          </select>
        </label>
        <button className="button button-secondary button-compact" type="submit">Save parameter</button>
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

  useEffect(() => {
    setReturnType(operation.returnType ?? '');
    setVisibility(operation.visibility);
    setIsAbstract(operation.isAbstract);
    setIsStatic(operation.isStatic);
  }, [operation.id, operation.returnType, operation.visibility, operation.isAbstract, operation.isStatic]);

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
      },
    });
  }

  return (
    <>
      <div className="property-section-heading">
        <div>
          <h3>Operation</h3>
          <p className="property-summary">In {classifier.name}</p>
        </div>
        <button className="text-button danger-button" onClick={onDelete} type="button">Delete</button>
      </div>
      <form className="property-form" onSubmit={save}>
        <label className="field-label" htmlFor="operation-return-type">Return type <span>Optional</span></label>
        <input id="operation-return-type" onChange={(event) => setReturnType(event.target.value)} value={returnType} />

        <label className="field-label" htmlFor="operation-visibility">Visibility</label>
        <select id="operation-visibility" onChange={(event) => setVisibility(event.target.value)} value={visibility}>
          <option value="PUBLIC">Public (+)</option>
          <option value="PRIVATE">Private (-)</option>
        </select>

        <label className="property-check">
          <input checked={isAbstract} onChange={(event) => setIsAbstract(event.target.checked)} type="checkbox" />
          <span>Abstract operation</span>
        </label>
        <label className="property-check">
          <input checked={isStatic} onChange={(event) => setIsStatic(event.target.checked)} type="checkbox" />
          <span>Static operation</span>
        </label>
        <button className="button button-primary button-compact" type="submit">Save operation</button>
      </form>
      <section className="parameter-properties" aria-labelledby="operation-parameters-title">
        <div className="parameter-properties-heading">
          <h4 id="operation-parameters-title">Parameters</h4>
          <button className="button button-secondary button-compact" onClick={addParameter} type="button">
            Add parameter
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
        ) : <p className="property-hint">No parameters yet.</p>}
      </section>
    </>
  );
}

export default function PropertiesPanel({ model, selection, onCommand, onDelete, onSelect }: PropertiesPanelProps) {
  if (!selection) {
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Details</p>
        <h2 id="properties-title">Properties</h2>
        <p className="property-hint">Select a class or one of its members to inspect and edit it.</p>
      </aside>
    );
  }

  if (selection.kind === 'relationship') {
    const relationship = model.relationships.find((candidate) => candidate.id === selection.relationshipId);
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Details</p>
        <h2 id="properties-title">Properties</h2>
        {relationship ? (
          <RelationshipProperties
            relationship={relationship}
            model={model}
            onCommand={onCommand}
            onDelete={() => onDelete(selection)}
          />
        ) : <p className="property-hint">This relationship is no longer in the model.</p>}
      </aside>
    );
  }

  const classifier = model.classifiers.find((candidate) => candidate.id === selection.classifierId);
  if (!classifier) {
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Details</p>
        <h2 id="properties-title">Properties</h2>
        <p className="property-hint">This element is no longer in the model.</p>
      </aside>
    );
  }

  if (selection.kind === 'classifier') {
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Details</p>
        <h2 id="properties-title">Properties</h2>
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
        <p className="eyebrow">Details</p>
        <h2 id="properties-title">Properties</h2>
        {attribute ? (
          <AttributeProperties
            attribute={attribute}
            classifier={classifier}
            model={model}
            onCommand={onCommand}
            onDelete={() => onDelete(selection)}
          />
        ) : <p className="property-hint">This element is no longer in the model.</p>}
      </aside>
    );
  }

  if (selection.kind === 'operation') {
    const operation = classifier.operations.find((candidate) => candidate.id === selection.elementId);
    return (
      <aside className="surface properties-panel" aria-labelledby="properties-title">
        <p className="eyebrow">Details</p>
        <h2 id="properties-title">Properties</h2>
        {operation ? (
          <OperationProperties
            operation={operation}
            classifier={classifier}
            model={model}
            onCommand={onCommand}
            onDelete={() => onDelete(selection)}
          />
        ) : <p className="property-hint">This element is no longer in the model.</p>}
      </aside>
    );
  }

  const constraint = classifier.constraints.find((candidate) => candidate.id === selection.elementId);
  return (
    <aside className="surface properties-panel" aria-labelledby="properties-title">
      <p className="eyebrow">Details</p>
      <h2 id="properties-title">Properties</h2>
      <div className="property-section">
        <div className="property-section-heading">
          <h3>Constraint</h3>
        </div>
        {constraint
          ? <ConstraintEditor constraint={constraint} onCommand={onCommand} />
          : <p className="property-hint">This element is no longer in the model.</p>}
      </div>
    </aside>
  );
}
