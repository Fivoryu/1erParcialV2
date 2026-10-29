import { useEffect, useRef, useState, type FormEvent } from 'react';
import type {
  ChatMessageResource,
  ChatProposalResource,
  ChatSessionResource,
  ContextMeterResource,
  DiagramMemoryResource,
  Uuid,
} from '../api/types';
import { describeAssistantOperation, type DiagramNames } from '../assistant/assistantNames';
import { appendTranscript } from '../assistant/speechTranscript';
import { speechRecognitionErrorMessage } from '../assistant/speechRecognition';
import { useSpeechRecognition } from '../assistant/useSpeechRecognition';
import MarkdownText from './MarkdownText';
import { formatMessageTime } from './messageTime';
import { proposalStatusLabel } from './chatProposals';
import { limitChatAttachments, type SessionAiStatus } from './chatState';

/**
 * T7/L47: what speech recognition actually is — the browser's Web Speech API, whose audio is
 * processed by the browser vendor's cloud in Chromium (not local); shown on the mic button.
 */
const DICTATION_HINT =
  'Dictado: usa la API de voz del navegador; en Chromium el audio se procesa en la nube del proveedor '
  + 'del navegador (no es local). La transcripción se agrega al mensaje y se envía solo cuando presionás Enviar.';

export interface ChatPanelProps {
  history: ChatMessageResource[];
  userId: string | null;
  loading: boolean;
  error: string | null;
  draft: string;
  busy: boolean;
  onDraftChange: (draft: string) => void;
  onSend: (body: string, images: File[]) => void | boolean | Promise<void | boolean>;
  onAskAi: (messageId: Uuid) => void | boolean | Promise<void | boolean>;
  sessions: ChatSessionResource[];
  sessionsLoading: boolean;
  activeSessionId: Uuid | null;
  sessionActionBusy: boolean;
  onSessionChange: (sessionId: Uuid) => void;
  onCreateSession: () => void;
  aiStatus: SessionAiStatus | null;
  contextMeter: ContextMeterResource | null;
  contextMeterLoading: boolean;
  contextError: string | null;
  compactBusy: boolean;
  compactMessage: string | null;
  onCompact: () => void;
  memories: DiagramMemoryResource[];
  memoriesLoading: boolean;
  memoryError: string | null;
  memoryMessage: string | null;
  extractBusy: boolean;
  onExtractMemories: () => void;
  canManageMemory: boolean;
  onForgetMemory: (memoryId: Uuid) => void;
  onForgetAllMemories: () => void;
  canDeleteHistory: boolean;
  onDeleteHistory: () => void;
  historyMessage: string | null;
  attachmentUrls: Record<Uuid, string>;
  /** Suggestions the assistant made in this session; omitted where chat suggestions are not offered. */
  proposals?: ChatProposalResource[];
  /** Names of the live diagram, so a suggestion's preview reads in terms of the user's own classes. */
  diagramNames?: DiagramNames | null;
  /** Editors and owners may ask for, accept and reject suggestions; viewers only read them. */
  canDecideProposals?: boolean;
  proposalBusyId?: Uuid | null;
  suggestBusy?: boolean;
  proposalNotice?: string | null;
  onSuggest?: (utterance: string) => void | boolean | Promise<void | boolean>;
  onAcceptProposal?: (proposalId: Uuid) => void;
  onRejectProposal?: (proposalId: Uuid) => void;
}

const EMPTY_NAMES: DiagramNames = {
  classifiers: new Map(),
  attributes: new Map(),
  operations: new Map(),
  relationships: new Map(),
};

interface ProposalCardProps {
  proposal: ChatProposalResource;
  diagramNames: DiagramNames;
  canDecide: boolean;
  busy: boolean;
  onAccept: (proposalId: Uuid) => void;
  onReject: (proposalId: Uuid) => void;
}

/** The suggestion every member sees: what would change, and who decided what. */
function ProposalCard({ proposal, diagramNames, canDecide, busy, onAccept, onReject }: ProposalCardProps) {
  const pending = proposal.status === 'PENDING';
  return (
    <section
      className={`chat-proposal chat-proposal--${proposal.status.toLowerCase()}`}
      aria-label={`Cambio sugerido por ${proposal.requestedByDisplayName}`}
    >
      <p className="eyebrow">Cambio sugerido · {proposalStatusLabel(proposal.status)}</p>
      <p className="chat-proposal-preview-title">
        {pending ? 'Si se acepta, el diagrama cambiará así:' : 'El cambio sugerido:'}
      </p>
      <ul className="chat-proposal-operations">
        {proposal.operations.map((operation, index) => (
          <li key={`${operation.type}-${index}`}>{describeAssistantOperation(operation, diagramNames)}</li>
        ))}
      </ul>
      {proposal.resultSummary && (
        <p className="quiet-state chat-proposal-result" role="status">
          {proposal.decidedByDisplayName ? `${proposal.decidedByDisplayName}: ` : ''}{proposal.resultSummary}
        </p>
      )}
      {pending && canDecide && (
        <div className="chat-proposal-actions">
          <button className="button button-primary" type="button" disabled={busy} onClick={() => onAccept(proposal.id)}>
            {busy ? 'Procesando…' : 'Aceptar y aplicar'}
          </button>
          <button className="button button-secondary" type="button" disabled={busy} onClick={() => onReject(proposal.id)}>
            Rechazar
          </button>
        </div>
      )}
      {pending && !canDecide && (
        <p className="quiet-state">Solo una persona editora o propietaria puede aceptar esta sugerencia.</p>
      )}
    </section>
  );
}

function aiStateLabel(status: SessionAiStatus): string {
  switch (status.state) {
    case 'QUEUED': return 'en cola';
    case 'RUNNING': return 'en curso';
    case 'COMPLETED': return 'completada';
    case 'FAILED': return 'fallida';
    case 'CANCELLED': return 'cancelada';
  }
}

function memoryKindLabel(kind: DiagramMemoryResource['kind']): string {
  return kind === 'DECISION' ? 'Decisión' : 'Hipótesis';
}

function memoryStatusLabel(status: DiagramMemoryResource['status']): string {
  switch (status) {
    case 'ACTIVE': return 'Activa';
    case 'CONFLICTED': return 'En conflicto';
    case 'REPLACED': return 'Reemplazada';
  }
}

function meterColor(meter: ContextMeterResource): string {
  if (meter.compactionPending || meter.totalUsed >= meter.promptThreshold) {
    return '#b42318';
  }
  if (meter.percent >= 60) {
    return '#b54708';
  }
  return '#16794b';
}

function sourceLabels(memory: DiagramMemoryResource): string[] {
  const labels: string[] = [];
  if (memory.sourceSessionId) labels.push(`sesión ${memory.sourceSessionId}`);
  if (memory.sourceMessageId) labels.push(`mensaje ${memory.sourceMessageId}`);
  if (memory.replacedBy) labels.push(`reemplazado por ${memory.replacedBy}`);
  return labels;
}

export default function ChatPanel({
  history,
  userId,
  loading,
  error,
  draft,
  busy,
  onDraftChange,
  onSend,
  onAskAi,
  sessions,
  sessionsLoading,
  activeSessionId,
  sessionActionBusy,
  onSessionChange,
  onCreateSession,
  aiStatus,
  contextMeter,
  contextMeterLoading,
  contextError,
  compactBusy,
  compactMessage,
  onCompact,
  memories,
  memoriesLoading,
  memoryError,
  memoryMessage,
  extractBusy,
  onExtractMemories,
  canManageMemory,
  onForgetMemory,
  onForgetAllMemories,
  canDeleteHistory,
  onDeleteHistory,
  historyMessage,
  attachmentUrls,
  proposals = [],
  diagramNames = null,
  canDecideProposals = false,
  proposalBusyId = null,
  suggestBusy = false,
  proposalNotice = null,
  onSuggest,
  onAcceptProposal,
  onRejectProposal,
}: ChatPanelProps) {
  const { timeZone } = new Intl.DateTimeFormat().resolvedOptions();
  const speech = useSpeechRecognition();
  const speechNotice = speechRecognitionErrorMessage(speech.error);
  const draftRef = useRef(draft);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedImages, setSelectedImages] = useState<File[]>([]);
  const [imageNotice, setImageNotice] = useState<string | null>(null);
  const orderedHistory = [...history].sort((left, right) => (
    Date.parse(left.createdAt) - Date.parse(right.createdAt) || left.id.localeCompare(right.id)
  ));
  const selectedSession = sessions.find((session) => session.id === activeSessionId) ?? null;
  const aiRequestInProgress = aiStatus !== null
    && aiStatus.sessionId === activeSessionId
    && (aiStatus.state === 'QUEUED' || aiStatus.state === 'RUNNING');

  useEffect(() => {
    setSelectedImages([]);
    setImageNotice(null);
  }, [activeSessionId]);

  useEffect(() => {
    draftRef.current = draft;
  }, [draft]);

  // T7/L47: the finished transcript is appended to the draft; sending stays explicit — the user
  // presses Send. A no-op on an empty transcript so nothing the user wrote is ever discarded.
  useEffect(() => {
    if (!speech.listening && speech.transcript.length > 0) {
      const nextDraft = appendTranscript(draft, speech.transcript);
      draftRef.current = nextDraft;
      onDraftChange(nextDraft);
      speech.reset();
    }
    // Re-append only when a transcript completes; the effect is deliberately
    // scoped to the listening state and the completed transcript.
  }, [speech.listening, speech.transcript]);

  async function handleAskAi(message: ChatMessageResource): Promise<void> {
    let result: void | boolean;
    try {
      result = await onAskAi(message.id);
    } catch {
      result = false;
    }
    if (result === false && draftRef.current.trim().length === 0) {
      draftRef.current = message.body;
      onDraftChange(message.body);
    }
  }

  async function handleSuggest(): Promise<void> {
    const utterance = draft.trim();
    if (!utterance || !onSuggest) {
      return;
    }
    let result: void | boolean;
    try {
      result = await onSuggest(utterance);
    } catch {
      result = false;
    }
    // The request text is only spent when a suggestion was actually shared; a failure keeps it to retry.
    if (result !== false && draftRef.current.trim() === utterance) {
      draftRef.current = '';
      onDraftChange('');
    }
  }

  function handleFileChange(files: FileList | null): void {
    const selection = limitChatAttachments(Array.from(files ?? []));
    setSelectedImages(selection.selected);
    setImageNotice(selection.rejectedCount > 0 ? 'Solo se pueden adjuntar tres imágenes a un mensaje.' : null);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const body = draft.trim();
    if (!body || busy || !activeSessionId) {
      return;
    }
    const submittedImages = selectedImages;
    const result = onSend(body, submittedImages);
    void Promise.resolve(result).then((sent) => {
      if (sent !== false) {
        setSelectedImages((current) => current === submittedImages ? [] : current);
        setImageNotice(null);
      }
    }).catch(() => undefined);
  }

  const clampedPercent = contextMeter ? Math.max(0, Math.min(100, contextMeter.percent)) : 0;

  return (
    <section className="surface chat-panel" aria-labelledby="chat-title">
      <div className="chat-layout">
        <div className="chat-main">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Conversación</p>
              <h2 id="chat-title">Chat del proyecto</h2>
            </div>
          </div>

          {error && <div className="notice notice-error" role="alert">{error}</div>}

          <div className="chat-session-controls">
            <label htmlFor="chat-session-selector">Sesión de chat</label>
            <select
              id="chat-session-selector"
              value={activeSessionId ?? ''}
              disabled={sessionsLoading || sessions.length === 0 || sessionActionBusy}
              onChange={(event) => onSessionChange(event.target.value)}
            >
              <option value="" disabled>{sessionsLoading ? 'Cargando sesiones…' : 'No hay sesiones de chat disponibles'}</option>
              {sessions.map((session) => (
                <option key={session.id} value={session.id}>{session.title}</option>
              ))}
            </select>
            <button
              className="button button-secondary"
              type="button"
              disabled={sessionsLoading || sessionActionBusy}
              onClick={onCreateSession}
            >
              {sessionActionBusy ? 'Creando…' : 'Nueva sesión'}
            </button>
            {selectedSession && <span className="quiet-state">Activa: {selectedSession.title}</span>}
          </div>

          {aiStatus && (
            <p className={`chat-ai-status chat-ai-status--${aiStatus.state.toLowerCase()}`} role="status">
              Solicitud de IA {aiStateLabel(aiStatus)}
              {aiStatus.reason ? `: ${aiStatus.reason}` : ''}
            </p>
          )}

          <div className="chat-conversation-area">
            {loading ? (
              <p className="quiet-state chat-loading" role="status">Cargando mensajes…</p>
            ) : orderedHistory.length === 0 ? (
              <div className="chat-empty">
                <p>Aún no hay mensajes.</p>
                <p className="chat-empty-suggestion">Inicia la conversación o pregúntale a la IA sobre el diagrama.</p>
              </div>
            ) : (
              <ol className="chat-list">
                {orderedHistory.map((message) => {
                  const isReader = message.authorUserId !== null && message.authorUserId === userId;
                  const isAssistant = message.kind === 'AI';
                  const proposal = message.kind === 'PROPOSAL'
                    ? proposals.find((entry) => entry.messageId === message.id) ?? null
                    : null;
                  return (
                    <li
                      className={`chat-message${isReader ? ' chat-message--own' : ''}${isAssistant ? ' chat-message--assistant' : ''}`}
                      data-current-reader={isReader ? 'true' : undefined}
                      data-reply-to={message.replyTo ?? undefined}
                      key={message.id}
                    >
                      <div className="chat-message-heading">
                        <h3>
                          {message.authorDisplayName}
                          {isReader && <span className="chat-author-you">Tú</span>}
                          {isAssistant && <span className="chat-author-assistant">AI</span>}
                        </h3>
                        <time className="chat-message-time" dateTime={message.createdAt}>
                          {formatMessageTime(message.createdAt, 'es', timeZone)}
                        </time>
                      </div>
                      {/* Model output is untrusted: MarkdownText creates allowlisted React elements, not HTML. */}
                      {isAssistant ? (
                        <div className="chat-message-body chat-message-body--markdown">
                          <MarkdownText text={message.body} />
                        </div>
                      ) : (
                        <p className="chat-message-body">{message.body}</p>
                      )}
                      {message.attachments && message.attachments.length > 0 && (
                        <div className="chat-message-attachments" aria-label="Imágenes del mensaje">
                          {message.attachments.map((attachmentId) => attachmentUrls[attachmentId] ? (
                            <img
                              className="chat-attachment-thumbnail"
                              key={attachmentId}
                              src={attachmentUrls[attachmentId]}
                              alt={`Imagen adjuntada por ${message.authorDisplayName}`}
                            />
                          ) : (
                            <span className="chat-attachment-pending" key={attachmentId} role="status">Cargando imagen…</span>
                          ))}
                        </div>
                      )}
                      {proposal && (
                        <ProposalCard
                          proposal={proposal}
                          diagramNames={diagramNames ?? EMPTY_NAMES}
                          canDecide={canDecideProposals}
                          busy={proposalBusyId !== null}
                          onAccept={(proposalId) => onAcceptProposal?.(proposalId)}
                          onReject={(proposalId) => onRejectProposal?.(proposalId)}
                        />
                      )}
                      {message.kind === 'HUMAN' && isReader && (
                        <button
                          className="button button-secondary"
                          type="button"
                          disabled={busy || !activeSessionId || aiRequestInProgress}
                          onClick={() => handleAskAi(message)}
                        >
                          Preguntar a la IA
                        </button>
                      )}
                    </li>
                  );
                })}
              </ol>
            )}
          </div>

          <form className="chat-compose-form" onSubmit={handleSubmit}>
            <label className="sr-only" htmlFor="chat-message-draft">Mensaje</label>
            <input
              autoComplete="off"
              id="chat-message-draft"
              maxLength={2000}
              onChange={(event) => {
                draftRef.current = event.target.value;
                onDraftChange(event.target.value);
              }}
              placeholder="Escribe un mensaje…"
              type="text"
              value={draft}
            />
            {speech.supported && (
              <button
                type="button"
                className="button chat-dictate"
                aria-label={DICTATION_HINT}
                disabled={busy || speech.listening}
                title={DICTATION_HINT}
                onClick={() => (speech.listening ? speech.stop() : speech.start())}
              >
                {speech.listening ? 'Escuchando…' : 'Dictar'}
              </button>
            )}
            <label className="sr-only" htmlFor="chat-image-attachments">Imágenes adjuntas</label>
            <input
              ref={fileInputRef}
              className="sr-only"
              id="chat-image-attachments"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              onChange={(event) => {
                handleFileChange(event.currentTarget.files);
                event.currentTarget.value = '';
              }}
            />
            <button
              className="button button-secondary chat-attach-image"
              type="button"
              disabled={busy || !activeSessionId}
              aria-label="Adjuntar imágenes"
              onClick={() => fileInputRef.current?.click()}
            >
              Adjuntar imágenes
            </button>
            {onSuggest && canDecideProposals && (
              <button
                className="button button-secondary chat-suggest-change"
                type="button"
                disabled={busy || suggestBusy || !draft.trim() || !activeSessionId}
                title="Pide a la IA que sugiera un cambio en el diagrama a partir de este texto. Nada cambiará hasta que una persona editora lo acepte."
                onClick={() => void handleSuggest()}
              >
                {suggestBusy ? 'Preparando sugerencia…' : 'Sugerir cambio'}
              </button>
            )}
            <button
              className="button button-primary"
              disabled={busy || !draft.trim() || !activeSessionId}
              type="submit"
            >
              {busy ? 'Enviando…' : 'Enviar'}
            </button>
          </form>

          {proposalNotice && <p className="notice notice-error" role="alert">{proposalNotice}</p>}
          {speechNotice && <p className="notice notice-error" role="alert">{speechNotice}</p>}

          {selectedImages.length > 0 && (
            <ul className="chat-selected-attachments" aria-label="Imágenes por enviar">
              {selectedImages.map((image, index) => <li key={`${image.name}-${index}`}>{image.name}</li>)}
            </ul>
          )}
          {imageNotice && <p className="notice notice-error" role="alert">{imageNotice}</p>}

          <div className="chat-history-actions">
            <p>El historial de chat pertenece a la sesión seleccionada; olvidar los recuerdos del diagrama no lo elimina.</p>
            {canDeleteHistory && (
              <button className="button button-secondary" type="button" onClick={onDeleteHistory} disabled={!activeSessionId || loading}>
                Eliminar el historial de esta sesión
              </button>
            )}
            {historyMessage && <p className="quiet-state" role="status">{historyMessage}</p>}
          </div>
        </div>
        <aside className="chat-side" aria-label="Contexto y recuerdos del diagrama">
          <section className="context-meter-panel" aria-labelledby="context-meter-title">
            <h3 id="context-meter-title">Presupuesto de contexto de IA</h3>
            {contextMeterLoading ? (
              <p className="quiet-state" role="status">Cargando el medidor de contexto…</p>
            ) : contextMeter ? (
              <>
                <label htmlFor="chat-context-progress">{contextMeter.percent}% del presupuesto de entrada ({contextMeter.totalUsed} / {contextMeter.promptBudget} tokens)</label>
                <progress
                  id="chat-context-progress"
                  value={clampedPercent}
                  max={100}
                  aria-valuetext={`${contextMeter.percent}% del presupuesto de entrada de ${contextMeter.promptBudget} tokens`}
                  data-threshold-reached={contextMeter.compactionPending || contextMeter.totalUsed >= contextMeter.promptThreshold}
                  style={{ accentColor: meterColor(contextMeter) }}
                />
                <details className="context-meter-details">
                  <summary>Detalles</summary>
                  <dl className="context-meter-breakdown">
                    <div><dt>Ventana de contexto</dt><dd>{contextMeter.contextWindow}</dd></div>
                    <div><dt>Reservado para la respuesta</dt><dd>{contextMeter.reservedForResponse}</dd></div>
                    <div><dt>Presupuesto de entrada</dt><dd>{contextMeter.promptBudget}</dd></div>
                    <div><dt>Instrucciones</dt><dd>{contextMeter.instructions}</dd></div>
                    <div><dt>Diagrama</dt><dd>{contextMeter.diagramData}</dd></div>
                    <div><dt>Recuerdos</dt><dd>{contextMeter.memories}</dd></div>
                    <div><dt>Historial de la sesión</dt><dd>{contextMeter.history}</dd></div>
                    <div><dt>Imágenes</dt><dd>{contextMeter.images}</dd></div>
                    <div><dt>Umbral de compactación</dt><dd>{contextMeter.promptThreshold}</dd></div>
                  </dl>
                </details>
                <p>{contextMeter.compactionPending ? 'Se alcanzó el umbral de compactación.' : 'El contexto está por debajo del umbral de compactación.'}</p>
                <button className="button button-secondary" type="button" onClick={onCompact} disabled={compactBusy || !activeSessionId}>
                  {compactBusy ? 'Compactando…' : 'Compactar el contexto de esta sesión'}
                </button>
              </>
            ) : (
              <p className="quiet-state">{activeSessionId ? 'El medidor de contexto no está disponible.' : 'Elige una sesión para ver su contexto.'}</p>
            )}
            {contextError && <p className="notice notice-error" role="alert">{contextError}</p>}
            {compactMessage && <p className="quiet-state" role="status">{compactMessage}</p>}
          </section>

          <section className="diagram-memory-panel" aria-labelledby="diagram-memory-title">
            <h3 id="diagram-memory-title">Recuerdos del diagrama</h3>
            <p>Los recuerdos pertenecen a este diagrama, no a una sesión de chat. Eliminar el historial del chat no los borra.</p>
            <p>Después de las solicitudes a la IA se extraen automáticamente conocimientos útiles sobre el diseño. Usa «Extraer recuerdos» para hacerlo manualmente.</p>
            <p>Pregunta en el chat, con tus propias palabras, para leer, guardar o revisar recuerdos. Cada solicitud a la IA usa la versión más reciente del diagrama UML.</p>
            <p>Pregunta explícitamente si quieres buscar en otros diagramas. Los resultados compartidos solo incluyen proyectos a los que todas las personas colaboradoras activas pueden acceder.</p>
            {memoryError && <p className="notice notice-error" role="alert">{memoryError}</p>}
            {memoryMessage && <p className="quiet-state" role="status">{memoryMessage}</p>}
            {memoriesLoading ? (
              <p className="quiet-state" role="status">Cargando los recuerdos del diagrama…</p>
            ) : memories.length === 0 ? (
              <p className="quiet-state">Aún no hay recuerdos del diagrama.</p>
            ) : (
              <ul className="diagram-memory-list">
                {memories.map((memory) => {
                  const sources = sourceLabels(memory);
                  return (
                    <li key={memory.memoryId}>
                      <p><strong>{memoryKindLabel(memory.kind)}</strong> · <span>{memoryStatusLabel(memory.status)}</span></p>
                      <p>{memory.content}</p>
                      <p className="diagram-memory-sources">
                        Fuentes: {sources.length > 0 ? sources.join(' · ') : 'Sin fuente vinculada'}
                      </p>
                      {canManageMemory && (
                        <button
                          className="button button-secondary"
                          type="button"
                          aria-label={`Olvidar recuerdo: ${memory.content}`}
                          onClick={() => onForgetMemory(memory.memoryId)}
                        >
                          Olvidar este recuerdo
                        </button>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
            <div className="diagram-memory-actions">
              <button className="button button-secondary" type="button" onClick={onExtractMemories} disabled={extractBusy}>
                {extractBusy ? 'Extrayendo…' : 'Extraer recuerdos'}
              </button>
              {canManageMemory && (
                <button
                  className="button button-secondary"
                  type="button"
                  disabled={memoriesLoading || memories.length === 0}
                  onClick={onForgetAllMemories}
                >
                  Olvidar todos los recuerdos del diagrama
                </button>
              )}
            </div>
            {!canManageMemory && <p className="quiet-state">Solo la persona propietaria del proyecto puede olvidar los recuerdos del diagrama.</p>}
          </section>
        </aside>
      </div>
    </section>
  );
}
