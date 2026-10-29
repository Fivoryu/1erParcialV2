import { useState, type ReactElement } from 'react';
import type { ModelResource, OperationEnvelope } from '../api/types';
import { associationClassCandidates } from './associationClass';
import type { ModelCommandRequest } from './commands';
import { buildRelationToolRequest, draftRelationship, relationToolChoice, type RelationToolKind } from './relationTool';

export interface RelationSetupProps {
  model: ModelResource;
  kind: RelationToolKind;
  sourceId: string;
  targetId: string;
  onEndsChange: (ends: { sourceId: string; targetId: string }) => void;
  onCommand: (request: ModelCommandRequest) => OperationEnvelope | null | void;
  onExit: () => void;
}

/**
 * Inspector panel of the relation tool: picks the two ends (clicking classes on the canvas fills
 * them too) and, for an association class, the class that joins the association.
 */
export default function RelationSetup(props: RelationSetupProps): ReactElement {
  const choice = relationToolChoice(props.kind);
  const [associationClassId, setAssociationClassId] = useState('');
  const [status, setStatus] = useState('');
  const classifiers = props.model.classifiers.filter((classifier) => classifier.kind !== 'PACKAGE');
  const candidates = props.kind === 'ASSOCIATION_CLASS' && props.sourceId && props.targetId
    ? associationClassCandidates(props.model, draftRelationship(props.sourceId, props.targetId))
    : [];
  const chosenClass = candidates.some((candidate) => candidate.id === associationClassId) ? associationClassId : '';
  const name = (id: string) => props.model.classifiers.find((classifier) => classifier.id === id)?.name ?? '';

  function create(): void {
    const request = buildRelationToolRequest({
      kind: props.kind,
      sourceId: props.sourceId,
      targetId: props.targetId,
      ...(chosenClass ? { associationClassId: chosenClass } : {}),
    });
    if (!request) {
      setStatus(props.kind === 'ASSOCIATION_CLASS' && props.sourceId && props.targetId
        ? 'Elige la clase de asociación.'
        : 'Elige el origen y el destino.');
      return;
    }
    props.onCommand(request);
    setStatus(`${choice.label} creada: ${name(props.sourceId)} → ${name(props.targetId)}.`);
    setAssociationClassId('');
    props.onEndsChange({ sourceId: '', targetId: '' });
  }

  return (
    <section aria-labelledby="relation-setup-title" className="relation-setup" id="relation-setup">
      <div className="relation-setup-head">
        <div>
          <span className="flow-badge">Nueva relación</span>
          <h3 id="relation-setup-title">{choice.label}</h3>
        </div>
        <button className="button button-secondary button-compact" onClick={props.onExit} type="button">Salir</button>
      </div>
      <p className="property-hint">{choice.description} Haz clic en dos clases del lienzo o elígelas aquí.</p>
      <label className="field-label" htmlFor="relation-source">Origen</label>
      <select
        id="relation-source"
        onChange={(event) => props.onEndsChange({ sourceId: event.target.value, targetId: props.targetId })}
        value={props.sourceId}
      >
        <option value="">Elige una clase</option>
        {classifiers.map((classifier) => <option key={classifier.id} value={classifier.id}>{classifier.name}</option>)}
      </select>
      <label className="field-label" htmlFor="relation-target">Destino</label>
      <select
        id="relation-target"
        onChange={(event) => props.onEndsChange({ sourceId: props.sourceId, targetId: event.target.value })}
        value={props.targetId}
      >
        <option value="">Elige una clase</option>
        {classifiers.map((classifier) => <option key={classifier.id} value={classifier.id}>{classifier.name}</option>)}
      </select>
      {props.kind === 'ASSOCIATION_CLASS' && (
        <>
          <label className="field-label" htmlFor="relation-association-class">Clase de asociación</label>
          <select
            disabled={candidates.length === 0}
            id="relation-association-class"
            onChange={(event) => setAssociationClassId(event.target.value)}
            value={chosenClass}
          >
            <option value="">{candidates.length ? 'Elige una clase' : 'No hay clases disponibles'}</option>
            {candidates.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.name}</option>)}
          </select>
          <p className="property-hint">Una clase distinta de ambos extremos, que no sea ya clase de otra asociación.</p>
        </>
      )}
      <button className="button button-primary relation-create" onClick={create} type="button">Agregar al diagrama</button>
      <p className="relation-status" role="status">{status}</p>
    </section>
  );
}
