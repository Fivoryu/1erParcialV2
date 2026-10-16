import { useEffect, useRef, useState, type FormEvent } from 'react';
import type {
  ChatMessageResource,
  ChatSessionResource,
  ContextMeterResource,
  DiagramMemoryResource,
  Uuid,
} from '../api/types';
import { appendTranscript } from '../assistant/speechTranscript';
import { useSpeechRecognition } from '../assistant/useSpeechRecognition';
import { formatMessageTime } from './messageTime';
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
}

function aiStateLabel(status: SessionAiStatus): string {
  switch (status.state) {
    case 'QUEUED': return 'queued';
    case 'RUNNING': return 'running';
    case 'COMPLETED': return 'completed';
    case 'FAILED': return 'failed';
    case 'CANCELLED': return 'cancelled';
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
  if (memory.sourceSessionId) labels.push(`session ${memory.sourceSessionId}`);
  if (memory.sourceMessageId) labels.push(`message ${memory.sourceMessageId}`);
  if (memory.replacedBy) labels.push(`replaced by ${memory.replacedBy}`);
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
}: ChatPanelProps) {
  const { locale, timeZone } = new Intl.DateTimeFormat().resolvedOptions();
  const speech = useSpeechRecognition();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedImages, setSelectedImages] = useState<File[]>([]);
  const [imageNotice, setImageNotice] = useState<string | null>(null);
  const orderedHistory = [...history].sort((left, right) => (
    Date.parse(left.createdAt) - Date.parse(right.createdAt) || left.id.localeCompare(right.id)
  ));
  const selectedSession = sessions.find((session) => session.id === activeSessionId) ?? null;

  useEffect(() => {
    setSelectedImages([]);
    setImageNotice(null);
  }, [activeSessionId]);

  // T7/L47: the finished transcript is appended to the draft; sending stays explicit — the user
  // presses Send. A no-op on an empty transcript so nothing the user wrote is ever discarded.
  useEffect(() => {
    if (!speech.listening && speech.transcript.length > 0) {
      onDraftChange(appendTranscript(draft, speech.transcript));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-append only when a transcript completes
  }, [speech.listening, speech.transcript]);

  function handleFileChange(files: FileList | null): void {
    const selection = limitChatAttachments(Array.from(files ?? []));
    setSelectedImages(selection.selected);
    setImageNotice(selection.rejectedCount > 0 ? 'Only three images can be attached to one message.' : null);
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
      <div className="section-heading">
        <div>
          <p className="eyebrow">Live collaboration</p>
          <h2 id="chat-title">Project chat</h2>
        </div>
      </div>

      {error && <div className="notice notice-error" role="alert">{error}</div>}

      <div className="chat-session-controls">
        <label htmlFor="chat-session-selector">Chat session</label>
        <select
          id="chat-session-selector"
          value={activeSessionId ?? ''}
          disabled={sessionsLoading || sessions.length === 0 || sessionActionBusy}
          onChange={(event) => onSessionChange(event.target.value)}
        >
          <option value="" disabled>{sessionsLoading ? 'Loading sessions…' : 'No chat session available'}</option>
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
          {sessionActionBusy ? 'Creating…' : 'New session'}
        </button>
        {selectedSession && <span className="quiet-state">Active: {selectedSession.title}</span>}
      </div>

      {aiStatus && (
        <p className={`chat-ai-status chat-ai-status--${aiStatus.state.toLowerCase()}`} role="status">
          AI request {aiStateLabel(aiStatus)}
          {aiStatus.reason ? `: ${aiStatus.reason}` : ''}
        </p>
      )}

      {loading ? (
        <p className="quiet-state chat-empty" role="status">Loading messages…</p>
      ) : orderedHistory.length === 0 ? (
        <p className="quiet-state chat-empty">No messages yet.</p>
      ) : (
        <ol className="chat-list">
          {orderedHistory.map((message) => {
            const isReader = message.authorUserId !== null && message.authorUserId === userId;
            const isAssistant = message.kind === 'AI';
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
                    {isReader && <span className="chat-author-you">You</span>}
                    {isAssistant && <span className="chat-author-assistant">AI</span>}
                  </h3>
                  <time className="chat-message-time" dateTime={message.createdAt}>
                    {formatMessageTime(message.createdAt, locale, timeZone)}
                  </time>
                </div>
                {/* The server stores plain text without HTML sanitization; React escaping keeps message bodies text. */}
                <p className="chat-message-body">{message.body}</p>
                {message.attachments && message.attachments.length > 0 && (
                  <div className="chat-message-attachments" aria-label="Message images">
                    {message.attachments.map((attachmentId) => attachmentUrls[attachmentId] ? (
                      <img
                        className="chat-attachment-thumbnail"
                        key={attachmentId}
                        src={attachmentUrls[attachmentId]}
                        alt={`Image attached by ${message.authorDisplayName}`}
                      />
                    ) : (
                      <span className="chat-attachment-pending" key={attachmentId} role="status">Loading image…</span>
                    ))}
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      )}

      <form className="chat-compose-form" onSubmit={handleSubmit}>
        <label className="sr-only" htmlFor="chat-message-draft">Message</label>
        <input
          autoComplete="off"
          id="chat-message-draft"
          maxLength={2000}
          onChange={(event) => onDraftChange(event.target.value)}
          placeholder="Write a message…"
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
        <label className="sr-only" htmlFor="chat-image-attachments">Image attachments</label>
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
          aria-label="Attach images"
          onClick={() => fileInputRef.current?.click()}
        >
          Attach images
        </button>
        <button
          className="button button-primary"
          disabled={busy || !draft.trim() || !activeSessionId}
          type="submit"
        >
          {busy ? 'Sending…' : 'Send'}
        </button>
      </form>

      {selectedImages.length > 0 && (
        <ul className="chat-selected-attachments" aria-label="Images to send">
          {selectedImages.map((image, index) => <li key={`${image.name}-${index}`}>{image.name}</li>)}
        </ul>
      )}
      {imageNotice && <p className="notice notice-error" role="alert">{imageNotice}</p>}

      <div className="chat-history-actions">
        <p>Chat history is scoped to the selected session; forgetting diagram memory does not delete it.</p>
        {canDeleteHistory && (
          <button className="button button-secondary" type="button" onClick={onDeleteHistory} disabled={!activeSessionId || loading}>
            Delete history for this session
          </button>
        )}
        {historyMessage && <p className="quiet-state" role="status">{historyMessage}</p>}
      </div>

      <section className="context-meter-panel" aria-labelledby="context-meter-title">
        <h3 id="context-meter-title">AI context budget</h3>
        {contextMeterLoading ? (
          <p className="quiet-state" role="status">Loading context meter…</p>
        ) : contextMeter ? (
          <>
            <label htmlFor="chat-context-progress">{contextMeter.percent}% of the input budget ({contextMeter.totalUsed} / {contextMeter.promptBudget} tokens)</label>
            <progress
              id="chat-context-progress"
              value={clampedPercent}
              max={100}
              aria-valuetext={`${contextMeter.percent}% of the ${contextMeter.promptBudget}-token input budget`}
              data-threshold-reached={contextMeter.compactionPending || contextMeter.totalUsed >= contextMeter.promptThreshold}
              style={{ accentColor: meterColor(contextMeter) }}
            />
            <dl className="context-meter-breakdown">
              <div><dt>Context window</dt><dd>{contextMeter.contextWindow}</dd></div>
              <div><dt>Response reserved</dt><dd>{contextMeter.reservedForResponse}</dd></div>
              <div><dt>Input budget</dt><dd>{contextMeter.promptBudget}</dd></div>
              <div><dt>Instructions</dt><dd>{contextMeter.instructions}</dd></div>
              <div><dt>Diagram</dt><dd>{contextMeter.diagramData}</dd></div>
              <div><dt>Memories</dt><dd>{contextMeter.memories}</dd></div>
              <div><dt>Session history</dt><dd>{contextMeter.history}</dd></div>
              <div><dt>Images</dt><dd>{contextMeter.images}</dd></div>
              <div><dt>Compaction threshold</dt><dd>{contextMeter.promptThreshold}</dd></div>
            </dl>
            <p>{contextMeter.compactionPending ? 'Compaction threshold reached.' : 'Context is below the compaction threshold.'}</p>
            <button className="button button-secondary" type="button" onClick={onCompact} disabled={compactBusy || !activeSessionId}>
              {compactBusy ? 'Compacting…' : 'Compact this session context'}
            </button>
          </>
        ) : (
          <p className="quiet-state">{activeSessionId ? 'Context meter unavailable.' : 'Choose a session to view its context.'}</p>
        )}
        {contextError && <p className="notice notice-error" role="alert">{contextError}</p>}
        {compactMessage && <p className="quiet-state" role="status">{compactMessage}</p>}
      </section>

      <section className="diagram-memory-panel" aria-labelledby="diagram-memory-title">
        <h3 id="diagram-memory-title">Diagram memory</h3>
        <p>Memory belongs to this diagram, not to one chat session. Deleting chat history does not forget memory.</p>
        {memoryError && <p className="notice notice-error" role="alert">{memoryError}</p>}
        {memoryMessage && <p className="quiet-state" role="status">{memoryMessage}</p>}
        {memoriesLoading ? (
          <p className="quiet-state" role="status">Loading diagram memories…</p>
        ) : memories.length === 0 ? (
          <p className="quiet-state">No diagram memories yet.</p>
        ) : (
          <ul className="diagram-memory-list">
            {memories.map((memory) => {
              const sources = sourceLabels(memory);
              return (
                <li key={memory.memoryId}>
                  <p><strong>{memory.kind}</strong> · <span>{memory.status}</span></p>
                  <p>{memory.content}</p>
                  <p className="diagram-memory-sources">
                    Sources: {sources.length > 0 ? sources.join(' · ') : 'No linked source'}
                  </p>
                  {canManageMemory && (
                    <button
                      className="button button-secondary"
                      type="button"
                      aria-label={`Forget memory: ${memory.content}`}
                      onClick={() => onForgetMemory(memory.memoryId)}
                    >
                      Forget this memory
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        )}
        <div className="diagram-memory-actions">
          <button className="button button-secondary" type="button" onClick={onExtractMemories} disabled={extractBusy}>
            {extractBusy ? 'Extracting…' : 'Extract memories'}
          </button>
          {canManageMemory && (
            <button
              className="button button-secondary"
              type="button"
              disabled={memoriesLoading || memories.length === 0}
              onClick={onForgetAllMemories}
            >
              Forget all diagram memories
            </button>
          )}
        </div>
        {!canManageMemory && <p className="quiet-state">Only the project OWNER can forget diagram memories.</p>}
      </section>
    </section>
  );
}
