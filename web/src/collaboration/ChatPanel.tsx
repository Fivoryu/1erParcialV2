import type { FormEvent } from 'react';
import type { ChatMessageResource } from '../api/types';
import { formatMessageTime } from './messageTime';

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
  const orderedHistory = [...history].sort((left, right) => (
    Date.parse(left.createdAt) - Date.parse(right.createdAt) || left.id.localeCompare(right.id)
  ));

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
