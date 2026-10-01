import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiTransportError, createApiClient } from '../api/client';
import type { ModelResource, OperationEnvelope, ProjectResource } from '../api/types';
import UmlCanvas from '../diagram/UmlCanvas';
import PropertiesPanel from '../diagram/PropertiesPanel';
import GenerationPanel from '../generation/GenerationPanel';
import MembersPanel from '../members/MembersPanel';
import {
  applyOptimisticCommand,
  buildClassifierCreateEnvelope,
  buildModelCommandEnvelope,
  buildRelationshipDeleteRequest,
  classifierCascadeCount,
  type ClassifierKind,
  type ModelCommandRequest,
} from '../diagram/commands';
import type { CanvasSelection } from '../diagram/selection';

const api = createApiClient();

function messageFor(error: unknown): string {
  if (error instanceof ApiTransportError) {
    return error.message;
  }
  return 'This project could not be opened. Check the connection and try again.';
}

function messageForOutcome(outcome: { kind: string; diagnostics?: Array<{ message: string }> }): string {
  if (outcome.kind === 'rejected') {
    return outcome.diagnostics?.[0]?.message ?? 'The change was rejected by the model.';
  }
  if (outcome.kind === 'conflict') {
    return 'The change conflicts with the current model revision.';
  }
  return 'The change could not be applied.';
}

interface PendingCommand {
  sequence: number;
  envelope: OperationEnvelope;
}

export default function ProjectEditorPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const [project, setProject] = useState<ProjectResource | null>(null);
  const [model, setModel] = useState<ModelResource | null>(null);
  const [selection, setSelection] = useState<CanvasSelection | null>(null);
  const [loading, setLoading] = useState(true);
  const [pendingCount, setPendingCount] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const modelRef = useRef<ModelResource | null>(null);
  const canonicalModelRef = useRef<ModelResource | null>(null);
  const revisionRef = useRef<number | null>(null);
  const pendingCommandsRef = useRef<PendingCommand[]>([]);
  const sequenceRef = useRef(0);
  const queueRef = useRef<Promise<void>>(Promise.resolve());

  const publishModel = useCallback((next: ModelResource) => {
    modelRef.current = next;
    setModel(next);
  }, []);

  useEffect(() => {
    let active = true;
    if (!projectId) {
      setError('A project was not selected.');
      setLoading(false);
      return () => {
        active = false;
      };
    }

    void Promise.all([api.getProject(projectId), api.getModel(projectId)])
      .then(([projectResource, modelResource]) => {
        if (active) {
          setProject(projectResource);
          canonicalModelRef.current = modelResource;
          revisionRef.current = modelResource.revision;
          pendingCommandsRef.current = [];
          publishModel(modelResource);
          setSelection(null);
          setError(null);
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(messageFor(cause));
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [projectId, publishModel]);

  const reconcileAfterCommand = useCallback(async (sequence: number, envelope: OperationEnvelope, resultRevision?: number | null) => {
    let canonical = canonicalModelRef.current;
    try {
      if (!projectId) {
        return;
      }
      canonical = await api.getModel(projectId);
    } catch (cause: unknown) {
      if (resultRevision !== undefined && resultRevision !== null && canonical) {
        canonical = applyOptimisticCommand(canonical, envelope);
        canonical = { ...canonical, revision: resultRevision };
      } else if (!canonical) {
        setError(messageFor(cause));
        return;
      }
    }

    if (!canonical) {
      return;
    }
    canonicalModelRef.current = canonical;
    revisionRef.current = canonical.revision;
    pendingCommandsRef.current = pendingCommandsRef.current.filter((pending) => pending.sequence !== sequence);
    const projected = pendingCommandsRef.current
      .filter((pending) => pending.sequence > sequence)
      .sort((left, right) => left.sequence - right.sequence)
      .reduce((current, pending) => applyOptimisticCommand(current, pending.envelope), canonical);
    publishModel(projected);
  }, [projectId, publishModel]);

  const dispatchEnvelope = useCallback((envelope: OperationEnvelope): void => {
    if (!projectId || !modelRef.current) {
      return;
    }
    const sequence = sequenceRef.current + 1;
    sequenceRef.current = sequence;
    pendingCommandsRef.current = [...pendingCommandsRef.current, { sequence, envelope }];
    publishModel(applyOptimisticCommand(modelRef.current, envelope));
    setPendingCount((count) => count + 1);
    setError(null);

    const operation = queueRef.current.then(async () => {
      const currentRevision = revisionRef.current;
      if (currentRevision !== null) {
        envelope.baseRevision = currentRevision;
      }
      try {
        const outcome = await api.executeCommand(projectId, envelope);
        if (outcome.kind === 'accepted') {
          const target = envelope.targets[0];
          if (target && envelope.type === 'classifier.delete') {
            setSelection((current) => current?.kind !== 'relationship' && current?.classifierId === target.id ? null : current);
          } else if (target && envelope.type === 'relationship.delete') {
            setSelection((current) => current?.kind === 'relationship' && current.relationshipId === target.id ? null : current);
          } else if (target && envelope.type.endsWith('.remove')) {
            setSelection((current) => current
              && current.kind === target.scope.toLowerCase()
              && current.kind !== 'classifier'
              && current.kind !== 'relationship'
              && current.elementId === target.id
              ? null
              : current);
          }
          await reconcileAfterCommand(sequence, envelope, outcome.result.resultRevision);
        } else {
          setError(messageForOutcome(outcome));
          await reconcileAfterCommand(sequence, envelope);
        }
      } catch (cause: unknown) {
        setError(messageFor(cause));
        await reconcileAfterCommand(sequence, envelope);
      } finally {
        setPendingCount((count) => Math.max(0, count - 1));
      }
    });

    queueRef.current = operation.then(() => undefined, () => undefined);
  }, [projectId, publishModel, reconcileAfterCommand]);

  const dispatchCommand = useCallback((request: ModelCommandRequest): OperationEnvelope | null => {
    const current = modelRef.current;
    if (!current) {
      return null;
    }
    const envelope = buildModelCommandEnvelope(current, request);
    dispatchEnvelope(envelope);
    return envelope;
  }, [dispatchEnvelope]);

  const addClassifier = useCallback((kind: ClassifierKind) => {
    const current = modelRef.current;
    if (!current) {
      return;
    }
    const envelope = buildClassifierCreateEnvelope(current, kind);
    const id = envelope.payload.id;
    if (typeof id === 'string') {
      setSelection({ kind: 'classifier', classifierId: id });
    }
    dispatchEnvelope(envelope);
  }, [dispatchEnvelope]);

  const deleteSelection = useCallback((selected: CanvasSelection) => {
    const current = modelRef.current;
    if (!current) {
      return;
    }
    if (selected.kind === 'relationship') {
      const relationship = current.relationships.find((candidate) => candidate.id === selected.relationshipId);
      if (!relationship) {
        return;
      }
      if (!window.confirm('Delete this relationship and its ends? This cannot be undone.')) {
        return;
      }
      dispatchCommand(buildRelationshipDeleteRequest(relationship));
      return;
    }

    if (selected.kind === 'classifier') {
      const cascadeCount = classifierCascadeCount(current, selected.classifierId);
      if (cascadeCount === 0) {
        return;
      }
      if (cascadeCount > 1 && !window.confirm(
        `Delete this class and its ${cascadeCount - 1} dependent UML elements? This cannot be undone.`,
      )) {
        return;
      }
      dispatchCommand({
        type: 'classifier.delete',
        targets: [{ scope: 'classifier', id: selected.classifierId }],
        preconditions: [{ kind: 'cascade_count', expect: cascadeCount, scope: null }],
        payload: {},
      });
      return;
    }

    dispatchCommand({
      type: `${selected.kind}.remove`,
      targets: [{ scope: selected.kind, id: selected.elementId }],
      payload: {},
    });
  }, [dispatchCommand]);

  return (
    <main className="page-shell">
      <header className="topbar">
        <Link className="brand" to="/" aria-label="Class Diagrammer home">
          <span className="brand-mark" aria-hidden="true">C</span>
          <span>Class Diagrammer</span>
        </Link>
        <Link className="topbar-link" to="/">All projects <span aria-hidden="true">↗</span></Link>
      </header>

      <div className="content-wrap editor-wrap">
        {loading ? (
          <p className="quiet-state" role="status">Opening project…</p>
        ) : error && !model ? (
          <div className="notice notice-error" role="alert">
            <span>{error}</span>
            <Link className="text-button" to="/">Back to projects</Link>
          </div>
        ) : project && model ? (
          <>
            <section className="editor-heading" aria-labelledby="project-title">
              <div>
                <p className="eyebrow">Project workspace</p>
                <h1 id="project-title">{project.name}</h1>
                {project.description && <p className="lede">{project.description}</p>}
              </div>
              <div className="revision-chip" aria-live="polite">
                <span className="status-dot" />
                Revision {model.revision}
                <span className="revision-status">{pendingCount > 0 ? 'Saving changes…' : 'Saved'}</span>
              </div>
            </section>

            {error && (
              <div className="notice notice-error" role="alert">
                <span>{error}</span>
                <button className="text-button" onClick={() => setError(null)} type="button">Dismiss</button>
              </div>
            )}

            <div className="editor-workspace">
              <UmlCanvas
                model={model}
                selection={selection}
                onSelect={setSelection}
                onCommand={dispatchCommand}
                onAddClassifier={addClassifier}
                onDelete={deleteSelection}
              />
              <PropertiesPanel
                model={model}
                selection={selection}
                onCommand={dispatchCommand}
                onDelete={deleteSelection}
                onSelect={setSelection}
              />
            </div>

            <MembersPanel key={project.id} projectId={project.id} />

            <GenerationPanel
              projectId={project.id}
              modelId={model.id}
              versionId={model.versionId}
              modelRevision={model.revision}
            />
          </>
        ) : null}
      </div>
    </main>
  );
}
