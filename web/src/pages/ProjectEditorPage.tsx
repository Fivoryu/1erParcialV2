import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiTransportError, createApiClient } from '../api/client';
import { loadSession } from '../api/session';
import type {
  ChatMessageResource,
  MessagePostedPayload,
  ModelResource,
  OperationEnvelope,
  PresenceCursorUpdatePayload,
  PresencePerson,
  PresenceUpdatedPayload,
  ProjectResource,
} from '../api/types';
import ChatPanel from '../collaboration/ChatPanel';
import PresencePanel from '../collaboration/PresencePanel';
import { createCursorCoalescer, type CursorCoalescer } from '../collaboration/cursorCoalescer';
import { buildRemoteCursorMarkers } from '../collaboration/remoteCursors';
import { subscribeProjectEvents } from '../collaboration/projectEvents';
import { useEditingLease } from '../collaboration/useEditingLease';
import UmlCanvas from '../diagram/UmlCanvas';
import { useUndoRedoShortcuts } from '../diagram/useUndoRedoShortcuts';
import PropertiesPanel from '../diagram/PropertiesPanel';
import AssistantPanel from '../assistant/AssistantPanel';
import GenerationPanel from '../generation/GenerationPanel';
import XmiImportPanel from '../xmi/XmiImportPanel';
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
import { groupTouchesByElement } from '../diagram/touch-highlight';
import { loginErrorMessage } from './LoginPage';
import { findEditorSection, nextActiveSection, type EditorSectionId } from './editorSections';
import EditorOptionsBar from './EditorOptionsBar';
import FloatingAssistantButton from '../ui/FloatingAssistantButton';
import Modal from '../ui/Modal';

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

function selectedTargetElementId(selection: CanvasSelection | null): string | null {
  if (selection === null) {
    return null;
  }

  // CanvasSelection uses kind-specific identifiers instead of a shared `id` field.
  switch (selection.kind) {
    case 'classifier':
      return selection.classifierId;
    case 'relationship':
      return selection.relationshipId;
    default:
      return selection.elementId;
  }
}

function presenceSnapshot(data: unknown): PresencePerson[] | null {
  if (typeof data !== 'object' || data === null) {
    return null;
  }
  const payload = (data as { payload?: PresenceUpdatedPayload }).payload;
  return Array.isArray(payload?.presence) ? payload.presence : null;
}

function presenceCursor(data: unknown): PresenceCursorUpdatePayload['cursor'] | null {
  if (typeof data !== 'object' || data === null) {
    return null;
  }
  const payload = (data as { payload?: { cursor?: unknown } }).payload;
  if (typeof payload !== 'object' || payload === null || typeof payload.cursor !== 'object' || payload.cursor === null) {
    return null;
  }
  const cursor = payload.cursor as Partial<PresenceCursorUpdatePayload['cursor']>;
  return typeof cursor.userId === 'string' && typeof cursor.x === 'number' && typeof cursor.y === 'number'
    ? { userId: cursor.userId, x: cursor.x, y: cursor.y }
    : null;
}

function cursorsFromSnapshot(
  people: PresencePerson[],
  currentUserId: string | null,
): Map<string, { x: number; y: number }> {
  const cursors = new Map<string, { x: number; y: number }>();
  for (const person of people) {
    if (person.userId !== currentUserId && person.cursorX !== null && person.cursorY !== null) {
      cursors.set(person.userId, { x: person.cursorX, y: person.cursorY });
    }
  }
  return cursors;
}

function postedMessage(data: unknown): ChatMessageResource | null {
  if (typeof data !== 'object' || data === null) {
    return null;
  }

  const payload = (data as { payload?: MessagePostedPayload }).payload;
  const message = payload?.message;
  if (typeof message?.id !== 'string'
    || typeof message.authorUserId !== 'string'
    || typeof message.authorDisplayName !== 'string'
    || typeof message.body !== 'string'
    || typeof message.createdAt !== 'string') {
    return null;
  }
  return message;
}

interface PendingCommand {
  sequence: number;
  envelope: OperationEnvelope;
}

export default function ProjectEditorPage() {
  const { projectId } = useParams<{ projectId: string }>();
  // No asynchronous completion may write per-project state for a project that is no longer open.
  const currentProjectIdRef = useRef(projectId);

  const [project, setProject] = useState<ProjectResource | null>(null);
  const [model, setModel] = useState<ModelResource | null>(null);
  const [selection, setSelection] = useState<CanvasSelection | null>(null);
  const [presence, setPresence] = useState<PresencePerson[]>([]);
  const [chatHistory, setChatHistory] = useState<ChatMessageResource[]>([]);
  const [chatLoading, setChatLoading] = useState(true);
  const [chatError, setChatError] = useState<string | null>(null);
  const [chatDraft, setChatDraft] = useState('');
  const [chatBusy, setChatBusy] = useState(false);
  const [remoteCursorPositions, setRemoteCursors] = useState<Map<string, { x: number; y: number }>>(() => new Map());
  const [loading, setLoading] = useState(true);
  const [pendingCount, setPendingCount] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [openSection, setOpenSection] = useState<EditorSectionId | null>(null);
  const [assistantOpen, setAssistantOpen] = useState(false);
  const modelRef = useRef<ModelResource | null>(null);
  const canonicalModelRef = useRef<ModelResource | null>(null);
  const revisionRef = useRef<number | null>(null);
  const pendingCommandsRef = useRef<PendingCommand[]>([]);
  useLayoutEffect(() => {
    currentProjectIdRef.current = projectId;
    // These caches are per-project; cross-project comparisons are meaningless and can reject the right model.
    canonicalModelRef.current = null;
    revisionRef.current = null;
    pendingCommandsRef.current = [];
  }, [projectId]);
  const sequenceRef = useRef(0);
  const queueRef = useRef<Promise<void>>(Promise.resolve());
  const currentUserId = loadSession()?.userId ?? null;
  const editingLease = useEditingLease(projectId, currentUserId);
  const cursorCoalescerRef = useRef<CursorCoalescer<{ x: number; y: number }> | null>(null);

  useEffect(() => {
    if (!projectId) {
      cursorCoalescerRef.current = null;
      return;
    }

    // The 50 ms rate limit exists to keep cursor reports within the SLO's 10–30 updates/second budget.
    const coalescer = createCursorCoalescer<{ x: number; y: number }>((position) => {
      void api.reportProjectCursorPosition(projectId, position.x, position.y).catch(() => {
        // Presence is ephemeral and must never break editing.
      });
    });
    cursorCoalescerRef.current = coalescer;
    return () => {
      coalescer.cancel();
      if (cursorCoalescerRef.current === coalescer) {
        cursorCoalescerRef.current = null;
      }
    };
  }, [projectId]);

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
        if (currentProjectIdRef.current !== projectId) {
          return;
        }
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

  useEffect(() => {
    let active = true;
    setChatHistory([]);
    setChatLoading(Boolean(projectId));
    setChatError(null);
    setChatDraft('');
    setChatBusy(false);
    if (!projectId) {
      return () => {
        active = false;
      };
    }

    void api.listProjectMessages(projectId)
      .then((history) => {
        if (!active) {
          return;
        }
        setChatHistory((current) => {
          const messagesById = new Map(current.map((message) => [message.id, message]));
          for (const message of history) {
            if (!messagesById.has(message.id)) {
              messagesById.set(message.id, message);
            }
          }
          return [...messagesById.values()];
        });
      })
      .catch((cause: unknown) => {
        if (active) {
          setChatError(loginErrorMessage(cause));
        }
      })
      .finally(() => {
        if (active) {
          setChatLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [projectId]);

  const reconcileAfterCommand = useCallback(async (sequence: number, envelope: OperationEnvelope, resultRevision?: number | null) => {
    let canonical = canonicalModelRef.current;
    try {
      if (!projectId) {
        return;
      }
      canonical = await api.getModel(projectId);
    } catch (cause: unknown) {
      if (currentProjectIdRef.current !== projectId) {
        return;
      }
      if (resultRevision !== undefined && resultRevision !== null && canonical) {
        canonical = applyOptimisticCommand(canonical, envelope);
        canonical = { ...canonical, revision: resultRevision };
      } else if (!canonical) {
        setError(messageFor(cause));
        return;
      }
    }

    if (currentProjectIdRef.current !== projectId) {
      return;
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

  const refreshModelFromEvent = useCallback(async (): Promise<void> => {
    if (!projectId) {
      return;
    }

    try {
      const canonical = await api.getModel(projectId);
      if (currentProjectIdRef.current !== projectId) {
        return;
      }
      if (canonicalModelRef.current && canonical.revision < canonicalModelRef.current.revision) {
        return;
      }
      canonicalModelRef.current = canonical;
      revisionRef.current = canonical.revision;
      const projected = pendingCommandsRef.current
        .slice()
        .sort((left, right) => left.sequence - right.sequence)
        .reduce((current, pending) => applyOptimisticCommand(current, pending.envelope), canonical);
      publishModel(projected);
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId) {
        setError(messageFor(cause));
      }
    }
  }, [projectId, publishModel]);

  const undoRedo = useUndoRedoShortcuts({
    projectId,
    onApplied: () => {
      // Undo/redo enters through the normal command pipeline (it fires operation.applied), but the
      // refetch goes through the same path the page already uses so the refresh does not depend on
      // SSE arrival timing.
      void refreshModelFromEvent();
    },
    onError: (cause) => {
      if (currentProjectIdRef.current === projectId) {
        setError(messageFor(cause));
      }
    },
    // Escape only clears when something is selected; the canvas keeps its own Delete/Backspace handling untouched.
    onEscape: selection ? () => setSelection(null) : undefined,
  });

  useEffect(() => {
    if (!projectId) {
      setPresence([]);
      setRemoteCursors(new Map());
      return;
    }

    setPresence([]);
    setRemoteCursors(new Map());
    return subscribeProjectEvents({
      projectId,
      onEvent(event) {
        if (event.type === 'presence.updated') {
          const cursor = presenceCursor(event.data);
          if (cursor) {
            if (cursor.userId !== currentUserId) {
              setRemoteCursors((current) => {
                const next = new Map(current);
                next.set(cursor.userId, { x: cursor.x, y: cursor.y });
                return next;
              });
            }
            return;
          }
          const snapshot = presenceSnapshot(event.data);
          if (snapshot) {
            setPresence(snapshot);
            setRemoteCursors(cursorsFromSnapshot(snapshot, currentUserId));
          }
        } else if (event.type === 'message.posted') {
          const message = postedMessage(event.data);
          if (message) {
            setChatHistory((current) => {
              // The poster receives their own event after POST, so deduplicate messages by id.
              if (current.some((item) => item.id === message.id)) {
                return current;
              }
              return [...current, message];
            });
          }
        } else if (event.type === 'operation.applied') {
          void refreshModelFromEvent();
        }
      },
    });
  }, [currentUserId, projectId, refreshModelFromEvent]);

  const dispatchEnvelope = useCallback((envelope: OperationEnvelope): void => {
    if (!projectId || !modelRef.current) {
      return;
    }
    editingLease.acquireForEdit(envelope.targets[0]);
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
  }, [editingLease.acquireForEdit, projectId, publishModel, reconcileAfterCommand]);

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

  const targetElementId = selectedTargetElementId(selection);
  const activeSection = openSection === null ? null : findEditorSection(openSection);
  const handleOpenSection = useCallback((id: EditorSectionId) => {
    setOpenSection((current) => nextActiveSection(current, id));
  }, []);
  const closeSection = useCallback(() => setOpenSection(null), []);

  // `openSection` and `assistantOpen` are independent, so opening the assistant
  // from the floating button must close any open section dialog first: two open
  // dialogs would mean two backdrops, two focus traps and two scroll locks.
  const handleOpenAssistant = useCallback(() => {
    setOpenSection(null);
    setAssistantOpen(true);
  }, []);
  const touchedElements = useMemo(() => groupTouchesByElement(presence), [presence]);
  const remoteCursorMarkers = useMemo(
    () => buildRemoteCursorMarkers(presence, remoteCursorPositions, currentUserId),
    [currentUserId, presence, remoteCursorPositions],
  );

  useEffect(() => {
    if (!projectId) {
      return;
    }
    void api.reportProjectPresenceTouch(projectId, targetElementId).catch(() => {
      // Presence is ephemeral and must never break editing.
    });
  }, [projectId, targetElementId]);

  async function sendMessage(body: string): Promise<void> {
    const trimmedBody = body.trim();
    if (!projectId || !trimmedBody || chatBusy) {
      return;
    }

    setChatBusy(true);
    setChatError(null);
    try {
      const message = await api.postProjectMessage(projectId, trimmedBody);
      if (currentProjectIdRef.current !== projectId) {
        return;
      }
      setChatHistory((current) => current.some((item) => item.id === message.id)
        ? current
        : [...current, message]);
      setChatDraft('');
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId) {
        setChatError(loginErrorMessage(cause));
      }
    } finally {
      if (currentProjectIdRef.current === projectId) {
        setChatBusy(false);
      }
    }
  }

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
              <div className="diagram-toolbar-actions" aria-label="Undo and redo">
                <button
                  className="button button-secondary button-compact"
                  type="button"
                  disabled={!undoRedo.canUndo || undoRedo.busy}
                  onClick={() => undoRedo.undo()}
                >
                  Undo
                </button>
                <button
                  className="button button-secondary button-compact"
                  type="button"
                  disabled={!undoRedo.canRedo || undoRedo.busy}
                  onClick={() => undoRedo.redo()}
                >
                  Redo
                </button>
                <div className="revision-chip" aria-live="polite">
                  <span className="status-dot" />
                  Revision {model.revision}
                  <span className="revision-status">{pendingCount > 0 ? 'Saving changes…' : 'Saved'}</span>
                </div>
              </div>
            </section>

            {error && (
              <div className="notice notice-error" role="alert">
                <span>{error}</span>
                <button className="text-button" onClick={() => setError(null)} type="button">Dismiss</button>
              </div>
            )}

            <EditorOptionsBar
              activeSection={openSection}
              onOpenSection={handleOpenSection}
              badges={{ collaboration: presence.length }}
            />

            <div className="editor-workspace">
              <UmlCanvas
                model={model}
                selection={selection}
                onSelect={setSelection}
                onCommand={dispatchCommand}
                onAddClassifier={addClassifier}
                onDelete={deleteSelection}
                touchedElements={touchedElements}
                remoteCursors={remoteCursorMarkers}
                onCursorMove={(position) => cursorCoalescerRef.current?.schedule(position)}
              />
              <PropertiesPanel
                model={model}
                selection={selection}
                onCommand={dispatchCommand}
                onDelete={deleteSelection}
                onSelect={setSelection}
              />
            </div>

            <Modal
              open={activeSection?.id === 'xmi'}
              title={findEditorSection('xmi').title}
              size={findEditorSection('xmi').size}
              titleVisible={false}
              onClose={closeSection}
            >
              <XmiImportPanel projectId={project.id} />
            </Modal>

            <Modal
              open={activeSection?.id === 'collaboration'}
              title={findEditorSection('collaboration').title}
              size={findEditorSection('collaboration').size}
              titleVisible={false}
              onClose={closeSection}
            >
              <PresencePanel entries={presence} userId={currentUserId} />
              <ChatPanel
                busy={chatBusy}
                draft={chatDraft}
                error={chatError}
                history={chatHistory}
                loading={chatLoading}
                onDraftChange={setChatDraft}
                onSend={(body) => void sendMessage(body)}
                userId={currentUserId}
              />
            </Modal>

            <Modal
              open={activeSection?.id === 'members'}
              title={findEditorSection('members').title}
              size={findEditorSection('members').size}
              titleVisible={false}
              onClose={closeSection}
            >
              <MembersPanel key={project.id} projectId={project.id} />
            </Modal>

            <Modal
              open={activeSection?.id === 'generation'}
              title={findEditorSection('generation').title}
              size={findEditorSection('generation').size}
              titleVisible={false}
              onClose={closeSection}
            >
              <GenerationPanel
                projectId={project.id}
                modelId={model.id}
                versionId={model.versionId}
                modelRevision={model.revision}
                editingLease={editingLease}
              />
            </Modal>

            <Modal
              open={assistantOpen}
              title="Asistente de modelo"
              size="md"
              titleVisible={false}
              onClose={() => setAssistantOpen(false)}
            >
              <AssistantPanel currentRevision={model.revision} projectId={project.id} />
            </Modal>

            <FloatingAssistantButton open={assistantOpen} onOpen={handleOpenAssistant} />
          </>
        ) : null}
      </div>
    </main>
  );
}
