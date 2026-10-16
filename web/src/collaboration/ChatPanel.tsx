import { useEffect, type FormEvent } from 'react';
import type { ChatMessageResource } from '../api/types';
import { appendTranscript } from '../assistant/speechTranscript';
import { useSpeechRecognition } from '../assistant/useSpeechRecognition';
import { formatMessageTime } from './messageTime';

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
  onSend: (body: string) => void;
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
}: ChatPanelProps) {
  const { locale, timeZone } = new Intl.DateTimeFormat().resolvedOptions();
  const speech = useSpeechRecognition();
  const orderedHistory = [...history].sort((left, right) => (
    Date.parse(left.createdAt) - Date.parse(right.createdAt) || left.id.localeCompare(right.id)
  ));

  // T7/L47: the finished transcript is appended to the draft; sending stays explicit — the user
  // presses Send. A no-op on an empty transcript so nothing the user wrote is discarded.
  useEffect(() => {
    if (!speech.listening && speech.transcript.length > 0) {
      onDraftChange(appendTranscript(draft, speech.transcript));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-append only when a transcript completes
  }, [speech.listening, speech.transcript]);

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const body = draft.trim();
    if (!body || busy) {
      return;
    }
    onSend(body);
  }

  return (
    <section className="surface chat-panel" aria-labelledby="chat-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Live collaboration</p>
          <h2 id="chat-title">Project chat</h2>
        </div>
      </div>

      {error && <div className="notice notice-error" role="alert">{error}</div>}

      {loading ? (
        <p className="quiet-state chat-empty" role="status">Loading messages…</p>
      ) : orderedHistory.length === 0 ? (
        <p className="quiet-state chat-empty">No messages yet.</p>
      ) : (
        <ol className="chat-list">
          {orderedHistory.map((message) => {
            const isReader = message.authorUserId === userId;
            return (
              <li
                className={`chat-message${isReader ? ' chat-message--own' : ''}`}
                data-current-reader={isReader ? 'true' : undefined}
                key={message.id}
              >
                <div className="chat-message-heading">
                  <h3>
                    {message.authorDisplayName}
                    {isReader && <span className="chat-author-you">You</span>}
                  </h3>
                  <time className="chat-message-time" dateTime={message.createdAt}>
                    {formatMessageTime(message.createdAt, locale, timeZone)}
                  </time>
                </div>
                {/* The server stores plain text without HTML sanitization; React escaping keeps message bodies text. */}
                <p className="chat-message-body">{message.body}</p>
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
        <button
          className="button button-primary"
          disabled={busy || !draft.trim()}
          type="submit"
        >
          {busy ? 'Sending…' : 'Send'}
        </button>
      </form>
    </section>
  );
}
