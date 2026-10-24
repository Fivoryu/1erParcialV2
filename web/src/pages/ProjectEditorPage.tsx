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
  ChatSessionResource,
  ContextMeterResource,
  DiagramMemoryResource,
  MemberRole,
  ModelResource,
  OperationEnvelope,
  PresenceCursorUpdatePayload,
  PresencePerson,
  PresenceUpdatedPayload,
  ProjectResource,
  Uuid,
} from '../api/types';
import ChatPanel from '../collaboration/ChatPanel';
import { activeSessionFor } from '../collaboration/sessionSelection';
import {
  appendMessageToSession,
  attachmentIdsFromHistory,
  chatAiStatusFromProjectEvent,
  mergeSessionHistory,
  messageFromProjectEvent,
  updateSessionAiStatus,
  type SessionAiStatus,
} from '../collaboration/chatState';
import { isCurrentMemoryRefreshScope, shouldRefreshDiagramMemories } from '../collaboration/memoryRefresh';
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
import { ReviewPanelLoader } from '../review/ReviewPanel';
import XmiImportPanel from '../xmi/XmiImportPanel';
import XmiExportPanel from '../xmi/XmiExportPanel';
import MembersPanel from '../members/MembersPanel';
import VersioningPanel from '../vcs/VersioningPanel';
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
import HelpGuide from '../help/HelpGuideWidget';
import TourOverlay from '../help/TourOverlay';
import { createTourProgress } from '../help/tourProgress';
import {
  clampTourStepIndex,
  getTourStep,
  nextTourStepIndex,
  previousTourStepIndex,
} from '../help/tourSteps';
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
  const [chatHistoryMessage, setChatHistoryMessage] = useState<string | null>(null);
  const [chatDraft, setChatDraft] = useState('');
  const [chatSessions, setChatSessions] = useState<ChatSessionResource[]>([]);
  const [chatSessionsLoading, setChatSessionsLoading] = useState(true);
  const [chatSessionActionBusy, setChatSessionActionBusy] = useState(false);
  const [chatSessionId, setChatSessionId] = useState<Uuid | null>(null);
  const [chatAiStatuses, setChatAiStatuses] = useState<Record<Uuid, SessionAiStatus>>({});
  const [contextMeter, setContextMeter] = useState<ContextMeterResource | null>(null);
  const [contextMeterLoading, setContextMeterLoading] = useState(false);
  const [contextError, setContextError] = useState<string | null>(null);
  const [compactBusy, setCompactBusy] = useState(false);
  const [compactMessage, setCompactMessage] = useState<string | null>(null);
  const [memories, setMemories] = useState<DiagramMemoryResource[]>([]);
  const [memoriesLoading, setMemoriesLoading] = useState(false);
  const [memoryError, setMemoryError] = useState<string | null>(null);
  const [memoryMessage, setMemoryMessage] = useState<string | null>(null);
  const [memoryActionBusy, setMemoryActionBusy] = useState(false);
  const [currentUserRole, setCurrentUserRole] = useState<MemberRole | null>(null);
  const [attachmentUrls, setAttachmentUrls] = useState<Record<Uuid, string>>({});
  const [chatBusy, setChatBusy] = useState(false);
  const [remoteCursorPositions, setRemoteCursors] = useState<Map<string, { x: number; y: number }>>(() => new Map());
  const [loading, setLoading] = useState(true);
  const [pendingCount, setPendingCount] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [openSection, setOpenSection] = useState<EditorSectionId | null>(null);
  const [assistantOpen, setAssistantOpen] = useState(false);
  const [assistantSeed, setAssistantSeed] = useState<string | null>(null);
  const [tourOpen, setTourOpen] = useState(false);
  const [tourStepIndex, setTourStepIndex] = useState(0);
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
  const chatSessionIdRef = useRef(chatSessionId);
  const chatSessionsRef = useRef<ChatSessionResource[]>([]);
  const currentDefaultViewIdRef = useRef<Uuid | null>(null);
  const refreshDiagramMemoriesRef = useRef<(() => Promise<void>) | null>(null);
  useLayoutEffect(() => {
    chatSessionIdRef.current = chatSessionId;
  }, [chatSessionId]);
  const attachmentRequestKeysRef = useRef(new Set<string>());
  const attachmentObjectUrlsRef = useRef<string[]>([]);
  const currentUserId = loadSession()?.userId ?? null;
  const defaultViewId = model?.views.find((view) => view.is_default)?.id ?? null;
  useLayoutEffect(() => {
    currentDefaultViewIdRef.current = defaultViewId;
    chatSessionsRef.current = [];
  }, [defaultViewId, projectId]);
  const refreshDiagramMemories = useCallback(async (): Promise<void> => {
    if (!projectId || !defaultViewId || project?.id !== projectId) {
      return;
    }
    const scope = { projectId, viewId: defaultViewId };
    if (!isCurrentMemoryRefreshScope(scope, currentProjectIdRef.current, currentDefaultViewIdRef.current)) {
      return;
    }

    setMemoriesLoading(true);
    try {
      const items = await api.listMemories(projectId, defaultViewId);
      if (isCurrentMemoryRefreshScope(scope, currentProjectIdRef.current, currentDefaultViewIdRef.current)) {
        setMemories(items);
        setMemoryError(null);
      }
    } catch (cause: unknown) {
      if (isCurrentMemoryRefreshScope(scope, currentProjectIdRef.current, currentDefaultViewIdRef.current)) {
        setMemoryError(loginErrorMessage(cause));
      }
    } finally {
      if (isCurrentMemoryRefreshScope(scope, currentProjectIdRef.current, currentDefaultViewIdRef.current)) {
        setMemoriesLoading(false);
      }
    }
  }, [defaultViewId, project?.id, projectId]);
  refreshDiagramMemoriesRef.current = refreshDiagramMemories;
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

  const refreshContextMeter = useCallback(async (sessionId: Uuid): Promise<void> => {
    if (!projectId) {
      return;
    }
    setContextMeterLoading(true);
    try {
      const report = await api.getContextMeter(projectId, sessionId);
      if (currentProjectIdRef.current === projectId && chatSessionIdRef.current === sessionId) {
        setContextMeter(report);
        setContextError(null);
      }
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId && chatSessionIdRef.current === sessionId) {
        setContextError(loginErrorMessage(cause));
      }
    } finally {
      if (currentProjectIdRef.current === projectId && chatSessionIdRef.current === sessionId) {
        setContextMeterLoading(false);
      }
    }
  }, [projectId]);

  const modelLoaded = model !== null;
  useEffect(() => {
    if (!projectId || !modelLoaded || project?.id !== projectId) {
      return;
    }

    const progress = createTourProgress(projectId, window.localStorage);
    setTourStepIndex(0);
    setTourOpen(progress.shouldShow());
  }, [modelLoaded, project?.id, projectId]);

  useEffect(() => {
    let active = true;
    setChatSessions([]);
    setChatSessionId(null);
    setChatHistory([]);
    setChatAiStatuses({});
    setChatError(null);
    setChatHistoryMessage(null);
    setChatLoading(Boolean(projectId && defaultViewId));
    setChatSessionsLoading(Boolean(projectId && defaultViewId));
    setChatSessionActionBusy(false);
    setChatBusy(false);
    setCompactBusy(false);
    setMemoryActionBusy(false);
    setContextMeter(null);
    setContextMeterLoading(false);
    setContextError(null);
    setCompactMessage(null);

    if (!projectId || !defaultViewId) {
      setChatSessionsLoading(false);
      setChatLoading(false);
      if (projectId && modelLoaded) {
        setChatError('This diagram has no default view for chat sessions.');
      }
      return () => {
        active = false;
      };
    }

    chatSessionsRef.current = [];
    void api.listSessions(projectId, defaultViewId)
      .then((sessions) => {
        if (!active || currentProjectIdRef.current !== projectId) {
          return;
        }
        chatSessionsRef.current = sessions;
        setChatSessions(sessions);
        const session = activeSessionFor(
          [{ id: defaultViewId, isDefault: true }],
          sessions,
          chatSessionIdRef.current,
        );
        setChatSessionId(session?.id ?? null);
        setChatError(session ? null : 'No chat session is available for this diagram yet.');
      })
      .catch((cause: unknown) => {
        if (active) {
          setChatError(loginErrorMessage(cause));
        }
      })
      .finally(() => {
        if (active) {
          setChatSessionsLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [defaultViewId, modelLoaded, projectId]);

  useEffect(() => {
    let active = true;
    setChatHistory([]);
    setChatDraft('');
    setChatHistoryMessage(null);
    setChatLoading(Boolean(projectId && chatSessionId));
    setContextMeter(null);
    setContextError(null);
    setContextMeterLoading(Boolean(projectId && chatSessionId));
    setCompactMessage(null);
    if (!projectId || !chatSessionId) {
      setChatLoading(false);
      setContextMeterLoading(false);
      return () => {
        active = false;
      };
    }

    void api.listSessionMessages(projectId, chatSessionId)
      .then((history) => {
        if (active && currentProjectIdRef.current === projectId) {
          setChatHistory((current) => mergeSessionHistory(current, history, chatSessionId));
        }
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
    void refreshContextMeter(chatSessionId);

    return () => {
      active = false;
    };
  }, [chatSessionId, projectId, refreshContextMeter]);

  useEffect(() => {
    setMemories([]);
    setMemoryError(null);
    setMemoryMessage(null);
    if (!projectId || !defaultViewId || project?.id !== projectId) {
      setMemoriesLoading(false);
      return;
    }
    void refreshDiagramMemories();
  }, [defaultViewId, project?.id, projectId, refreshDiagramMemories]);

  useEffect(() => {
    let active = true;
    setCurrentUserRole(null);
    if (!projectId || !currentUserId) {
      return () => {
        active = false;
      };
    }
    void api.listProjectMembers(projectId)
      .then((members) => {
        if (active && currentProjectIdRef.current === projectId) {
          setCurrentUserRole(members.find((member) => member.userId === currentUserId)?.role ?? null);
        }
      })
      .catch(() => {
        if (active) {
          setCurrentUserRole(null);
        }
      });
    return () => {
      active = false;
    };
  }, [currentUserId, projectId]);

  useEffect(() => {
    for (const attachmentId of attachmentIdsFromHistory(chatHistory)) {
      if (!projectId || !chatSessionId) {
        continue;
      }
      const requestKey = `${projectId}/${chatSessionId}/${attachmentId}`;
      if (attachmentRequestKeysRef.current.has(requestKey)) {
        continue;
      }
      attachmentRequestKeysRef.current.add(requestKey);
      void api.getAttachmentBytes(projectId, chatSessionId, attachmentId)
        .then((bytes) => {
          if (currentProjectIdRef.current !== projectId || typeof URL.createObjectURL !== 'function') {
            return;
          }
          const objectUrl = URL.createObjectURL(bytes);
          attachmentObjectUrlsRef.current.push(objectUrl);
          setAttachmentUrls((current) => ({ ...current, [attachmentId]: objectUrl }));
        })
        .catch(() => {
          // Keep the message visible if an attachment cannot be downloaded.
        });
    }
  }, [chatHistory, chatSessionId, projectId]);

  useEffect(() => {
    setAttachmentUrls({});
    return () => {
      for (const objectUrl of attachmentObjectUrlsRef.current) {
        URL.revokeObjectURL(objectUrl);
      }
      attachmentObjectUrlsRef.current = [];
      attachmentRequestKeysRef.current.clear();
    };
  }, [projectId]);

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
          const message = messageFromProjectEvent(event.data);
          if (message) {
            setChatHistory((current) => appendMessageToSession(current, message, chatSessionIdRef.current));
            if (message.sessionId === chatSessionIdRef.current) {
              void refreshContextMeter(message.sessionId);
            }
          }
        } else if (event.type === 'chat.ai.status') {
          const status = chatAiStatusFromProjectEvent(event.data);
          const belongsToCurrentView = status !== null
            && chatSessionsRef.current.some((session) => session.id === status.sessionId
              && session.viewId === currentDefaultViewIdRef.current);
          if (status && currentProjectIdRef.current === projectId && belongsToCurrentView) {
            setChatAiStatuses((current) => updateSessionAiStatus(current, status));
            if (shouldRefreshDiagramMemories(status, chatSessionsRef.current, currentDefaultViewIdRef.current)) {
              void refreshDiagramMemoriesRef.current?.();
            }
          }
        } else if (event.type === 'operation.applied') {
          void refreshModelFromEvent();
        }
      },
    });
  }, [currentUserId, projectId, refreshContextMeter, refreshModelFromEvent]);

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
  const handleNavigateToSection = useCallback((id: EditorSectionId) => {
    setOpenSection(findEditorSection(id).id);
  }, []);
  const closeSection = useCallback(() => setOpenSection(null), []);
  const openTour = useCallback(() => {
    setTourStepIndex(0);
    setOpenSection(null);
    setAssistantOpen(false);
    setTourOpen(true);
  }, []);
  const changeTourStep = useCallback((requestedIndex: number) => {
    const nextIndex = clampTourStepIndex(requestedIndex);
    setTourStepIndex(nextIndex);
    setOpenSection(getTourStep(nextIndex).section ?? null);
    setAssistantOpen(false);
  }, []);
  const finishTour = useCallback(() => {
    if (projectId) {
      createTourProgress(projectId, window.localStorage).markSeen();
    }
    setTourOpen(false);
  }, [projectId]);

  // `openSection` and `assistantOpen` are independent, so opening the assistant
  // from the floating button must close any open section dialog first: two open
  // dialogs would mean two backdrops, two focus traps and two scroll locks.
  const handleOpenAssistant = useCallback((seed?: string) => {
    setOpenSection(null);
    setAssistantSeed(seed ?? null);
    setAssistantOpen(true);
  }, []);
  const closeAssistant = useCallback(() => {
    setAssistantOpen(false);
    setAssistantSeed(null);
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

  async function createChatSession(): Promise<void> {
    if (!projectId || !defaultViewId || chatSessionActionBusy) {
      return;
    }
    const scope = { projectId, viewId: defaultViewId };
    setChatSessionActionBusy(true);
    setChatError(null);
    try {
      const created = await api.createSession(projectId, defaultViewId, `Session ${chatSessions.length + 1}`);
      if (!isCurrentMemoryRefreshScope(scope, currentProjectIdRef.current, currentDefaultViewIdRef.current)
        || created.viewId !== defaultViewId) {
        return;
      }
      chatSessionsRef.current = chatSessionsRef.current.some((session) => session.id === created.id)
        ? chatSessionsRef.current
        : [...chatSessionsRef.current, created];
      setChatSessions((current) => current.some((session) => session.id === created.id)
        ? current
        : [...current, created]);
      setChatSessionId(created.id);
    } catch (cause: unknown) {
      if (isCurrentMemoryRefreshScope(scope, currentProjectIdRef.current, currentDefaultViewIdRef.current)) {
        setChatError(loginErrorMessage(cause));
      }
    } finally {
      if (isCurrentMemoryRefreshScope(scope, currentProjectIdRef.current, currentDefaultViewIdRef.current)) {
        setChatSessionActionBusy(false);
      }
    }
  }

  function changeChatSession(sessionId: Uuid): void {
    if (chatSessions.some((session) => session.id === sessionId)) {
      setChatSessionId(sessionId);
      setChatError(null);
      setChatHistoryMessage(null);
    }
  }

  async function sendMessage(body: string, images: File[]): Promise<boolean> {
    const trimmedBody = body.trim();
    const sessionId = chatSessionId;
    if (!projectId || !trimmedBody || chatBusy || !sessionId) {
      return false;
    }

    setChatBusy(true);
    setChatError(null);
    try {
      const uploaded = await Promise.all(images.map((file) => api.uploadAttachment(projectId, sessionId, file)));
      const message = await api.postSessionMessage(
        projectId,
        sessionId,
        trimmedBody,
        uploaded.map((attachment) => attachment.attachmentId),
      );
      if (currentProjectIdRef.current !== projectId) {
        return false;
      }
      setChatHistory((current) => appendMessageToSession(current, message, chatSessionIdRef.current));
      if (chatSessionIdRef.current === sessionId) {
        setChatDraft('');
        void refreshContextMeter(sessionId);
      }
      return true;
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId) {
        setChatError(loginErrorMessage(cause));
      }
      return false;
    } finally {
      if (currentProjectIdRef.current === projectId) {
        setChatBusy(false);
      }
    }
  }

  async function askAi(messageId: Uuid): Promise<void> {
    const sessionId = chatSessionId;
    if (!projectId || !sessionId) {
      return;
    }

    setChatError(null);
    try {
      await api.askAi(projectId, sessionId, messageId);
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId && chatSessionIdRef.current === sessionId) {
        setChatError(loginErrorMessage(cause));
      }
    }
  }

  async function compactChatContext(): Promise<void> {
    if (!projectId || !chatSessionId || compactBusy) {
      return;
    }
    const sessionId = chatSessionId;
    setCompactBusy(true);
    setCompactMessage(null);
    setContextError(null);
    try {
      const outcome = await api.compactSession(projectId, sessionId);
      if (currentProjectIdRef.current !== projectId || chatSessionIdRef.current !== sessionId) {
        return;
      }
      setCompactMessage(outcome.compacted
        ? 'Context compacted. Original messages remain available.'
        : outcome.reason);
      await refreshContextMeter(sessionId);
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId && chatSessionIdRef.current === sessionId) {
        setContextError(loginErrorMessage(cause));
      }
    } finally {
      if (currentProjectIdRef.current === projectId) {
        setCompactBusy(false);
      }
    }
  }

  async function extractDiagramMemories(): Promise<void> {
    if (!projectId || !defaultViewId || memoryActionBusy) {
      return;
    }
    setMemoryActionBusy(true);
    setMemoryError(null);
    setMemoryMessage(null);
    try {
      const result = await api.extractMemories(projectId, defaultViewId);
      if (currentProjectIdRef.current !== projectId) {
        return;
      }
      setMemoryMessage(result.consumed
        ? `Extraction complete: ${result.memoriesCreated} memories created, ${result.conflictsMarked} conflicts, ${result.replacements} replacements.`
        : 'No pending material was available to extract.');
      await refreshDiagramMemories();
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId) {
        setMemoryError(loginErrorMessage(cause));
      }
    } finally {
      if (currentProjectIdRef.current === projectId) {
        setMemoryActionBusy(false);
      }
    }
  }

  async function forgetDiagramMemory(memoryId: Uuid): Promise<void> {
    if (!projectId || !defaultViewId || currentUserRole !== 'OWNER') {
      return;
    }
    if (!window.confirm('Forget this diagram memory? Chat history will remain unchanged.')) {
      return;
    }
    setMemoryError(null);
    setMemoryMessage(null);
    try {
      const result = await api.forgetMemory(projectId, defaultViewId, memoryId);
      if (currentProjectIdRef.current === projectId) {
        setMemoryMessage(`${result.deletedMemories} diagram memory forgotten. Chat history was not changed.`);
        await refreshDiagramMemories();
      }
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId) {
        setMemoryError(loginErrorMessage(cause));
      }
    }
  }

  async function forgetAllDiagramMemories(): Promise<void> {
    if (!projectId || !defaultViewId || currentUserRole !== 'OWNER') {
      return;
    }
    if (!window.confirm('Forget all memory for this diagram? Chat history will remain unchanged.')) {
      return;
    }
    setMemoryError(null);
    setMemoryMessage(null);
    try {
      const result = await api.forgetAllMemories(projectId, defaultViewId);
      if (currentProjectIdRef.current === projectId) {
        setMemoryMessage(`${result.deletedMemories} diagram memories forgotten. Chat history was not changed.`);
        await refreshDiagramMemories();
      }
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId) {
        setMemoryError(loginErrorMessage(cause));
      }
    }
  }

  async function deleteChatHistory(): Promise<void> {
    if (!projectId || !chatSessionId || currentUserRole !== 'OWNER') {
      return;
    }
    const sessionId = chatSessionId;
    if (!window.confirm('Delete history for this session? Diagram memory will remain unchanged.')) {
      return;
    }
    setChatError(null);
    setChatHistoryMessage(null);
    try {
      const result = await api.deleteSessionHistory(projectId, sessionId);
      if (currentProjectIdRef.current === projectId && chatSessionIdRef.current === sessionId) {
        setChatHistory([]);
        setChatHistoryMessage(`${result.deletedMessages} messages deleted. Diagram memory was not changed.`);
        setChatDraft('');
        void refreshContextMeter(sessionId);
      }
    } catch (cause: unknown) {
      if (currentProjectIdRef.current === projectId && chatSessionIdRef.current === sessionId) {
        setChatError(loginErrorMessage(cause));
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
            {!tourOpen && (
              <button className="text-button" onClick={openTour} type="button">
                Show editor tour
              </button>
            )}

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
              <XmiExportPanel projectId={project.id} projectName={project.name} />
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
                activeSessionId={chatSessionId}
                aiStatus={chatSessionId ? chatAiStatuses[chatSessionId] ?? null : null}
                attachmentUrls={attachmentUrls}
                busy={chatBusy}
                canDeleteHistory={currentUserRole === 'OWNER'}
                canManageMemory={currentUserRole === 'OWNER'}
                compactBusy={compactBusy}
                compactMessage={compactMessage}
                contextError={contextError}
                contextMeter={contextMeter}
                contextMeterLoading={contextMeterLoading}
                draft={chatDraft}
                error={chatError}
                extractBusy={memoryActionBusy}
                history={chatHistory}
                historyMessage={chatHistoryMessage}
                loading={chatLoading}
                memories={memories}
                memoriesLoading={memoriesLoading}
                memoryError={memoryError}
                memoryMessage={memoryMessage}
                onCompact={() => void compactChatContext()}
                onCreateSession={() => void createChatSession()}
                onDeleteHistory={() => void deleteChatHistory()}
                onDraftChange={setChatDraft}
                onExtractMemories={() => void extractDiagramMemories()}
                onForgetAllMemories={() => void forgetAllDiagramMemories()}
                onForgetMemory={(memoryId) => void forgetDiagramMemory(memoryId)}
                onAskAi={(messageId) => void askAi(messageId)}
                onSend={(body, images) => sendMessage(body, images)}
                onSessionChange={changeChatSession}
                sessionActionBusy={chatSessionActionBusy}
                sessions={chatSessions}
                sessionsLoading={chatSessionsLoading}
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
              open={activeSection?.id === 'review'}
              title={findEditorSection('review').title}
              size={findEditorSection('review').size}
              titleVisible={false}
              onClose={closeSection}
            >
              <ReviewPanelLoader
                projectId={project.id}
                model={model}
                role={currentUserRole}
                onExplain={handleOpenAssistant}
                onProposeFix={handleOpenAssistant}
              />
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
              open={activeSection?.id === 'vcs'}
              title={findEditorSection('vcs').title}
              size={findEditorSection('vcs').size}
              titleVisible={false}
              onClose={closeSection}
            >
              <VersioningPanel
                projectId={project.id}
                onModelCheckout={(checkoutModel) => {
                  canonicalModelRef.current = checkoutModel;
                  revisionRef.current = checkoutModel.revision;
                  pendingCommandsRef.current = [];
                  publishModel(checkoutModel);
                  setSelection(null);
                }}
              />
            </Modal>

            <Modal
              open={assistantOpen}
              title="Asistente de modelo"
              size="md"
              titleVisible={false}
              onClose={closeAssistant}
            >
              <AssistantPanel
                currentRevision={model.revision}
                initialUtterance={assistantSeed ?? undefined}
                model={model}
                projectId={project.id}
              />
            </Modal>

            <FloatingAssistantButton open={assistantOpen} onOpen={handleOpenAssistant} />
            <HelpGuide
              api={api}
              openSection={openSection}
              onNavigateToSection={handleNavigateToSection}
            />
            {tourOpen && openSection === null && !assistantOpen && project.id === projectId && (
              <TourOverlay
                stepIndex={tourStepIndex}
                onPrevious={() => changeTourStep(previousTourStepIndex(tourStepIndex))}
                onNext={() => changeTourStep(nextTourStepIndex(tourStepIndex))}
                onFinish={finishTour}
                onReopenHelp={openTour}
              />
            )}
          </>
        ) : null}
      </div>
    </main>
  );
}
