import { useEffect, useState, type FormEvent } from 'react';
import type {
  AttributeResource,
  ClassifierResource,
  ModelResource,
  OperationEnvelope,
  OperationResource,
} from '../api/types';
import type { ModelCommandRequest } from './commands';
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

function typeReference(model: ModelResource, name: string): Record<string, string> {
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
  onCommand,
  onDelete,
  onSelect,
}: {
  classifier: ClassifierResource;
  onCommand: PropertiesPanelProps['onCommand'];
  onDelete: () => void;
  onSelect: (selection: CanvasSelection) => void;
}) {
  const [constraintExpression, setConstraintExpression] = useState('');
  const canBeAbstract = classifier.kind === 'CLASS' || classifier.kind === 'ABSTRACT_CLASS';

  useEffect(() => setConstraintExpression(''), [classifier.id]);

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

  function addConstraint(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const expression = constraintExpression.trim();
    if (!expression) {
      return;
    }
    const envelope = onCommand({
      type: 'constraint.add',
      targets: [{ scope: 'classifier', id: classifier.id }],
      payload: {
        ownerType: 'CLASSIFIER',
        ownerId: classifier.id,
        expression,
        severity: 'ERROR',
      },
    });
    const id = envelope?.payload.id;
    if (typeof id === 'string') {
      onSelect({ kind: 'constraint', classifierId: classifier.id, elementId: id });
    }
    setConstraintExpression('');
  }

  return (
    <>
      <section className="property-section" aria-labelledby="classifier-properties-title">
        <div className="property-section-heading">
          <h3 id="classifier-properties-title">Class</h3>
          <button className="text-button danger-button" onClick={onDelete} type="button">Delete class</button>
        </div>
        <p className="property-summary">{classifier.kind === 'INTERFACE' ? 'Interface' : classifier.isAbstract ? 'Abstract class' : 'Class'}</p>
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
        <div className="property-actions">
          <button className="button button-secondary button-compact" onClick={addAttribute} type="button">Add attribute</button>
          <button className="button button-secondary button-compact" onClick={addOperation} type="button">Add operation</button>
        </div>
      </section>

      <section className="property-section" aria-labelledby="constraints-title">
        <h3 id="constraints-title">Constraints</h3>
        {classifier.constraints.length > 0 ? (
          <ul className="property-constraint-list">
            {classifier.constraints.map((constraint) => (
              <li key={constraint.id}>
                <span>{`{${constraint.expression}}`}</span>
                <button
                  aria-label="Delete constraint"
                  className="icon-button danger-button"
                  onClick={() => onCommand({
                    type: 'constraint.remove',
                    targets: [{ scope: 'constraint', id: constraint.id }],
                    payload: {},
                  })}
                  type="button"
                >×</button>
              </li>
            ))}
          </ul>
        ) : <p className="property-hint">No constraints yet.</p>}
        <form className="property-inline-form" onSubmit={addConstraint}>
          <label className="field-label" htmlFor="constraint-expression">Expression</label>
          <input
            id="constraint-expression"
            onChange={(event) => setConstraintExpression(event.target.value)}
            placeholder="e.g. balance >= 0"
            value={constraintExpression}
          />
          <button className="button button-secondary button-compact" disabled={!constraintExpression.trim()} type="submit">
            Add constraint
          </button>
        </form>
      </section>
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
          <button className="text-button danger-button" onClick={() => onDelete(selection)} type="button">Delete</button>
        </div>
        <p className="constraint-detail">{constraint ? `{${constraint.expression}}` : 'This element is no longer in the model.'}</p>
      </div>
    </aside>
  );
}
