import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ChatMessageResource } from '../api/types';
import { formatMessageTime } from './messageTime';
import ChatPanel, { type ChatPanelProps } from './ChatPanel';

const readerId = '11111111-1111-4111-8111-111111111111';
const history: ChatMessageResource[] = [
  {
    id: '22222222-2222-4222-8222-222222222222',
    authorUserId: '33333333-3333-4333-8333-333333333333',
    authorDisplayName: 'Casey Collaborator',
    body: 'Later message',
    createdAt: '2026-02-01T16:45:00Z',
  },
  {
    id: '44444444-4444-4444-8444-444444444444',
    authorUserId: readerId,
    authorDisplayName: 'Morgan Reader',
    body: '<b>Earlier message</b>',
    createdAt: '2026-02-01T15:10:00Z',
  },
];

function render(overrides: Partial<ChatPanelProps> = {}): string {
  const props: ChatPanelProps = {
    history: [],
    userId: readerId,
    loading: false,
    error: null,
    draft: '',
    busy: false,
    onDraftChange: () => undefined,
    onSend: () => undefined,
    ...overrides,
  };
  return renderToStaticMarkup(createElement(ChatPanel, props));
}

function messageRows(markup: string): string[] {
  return markup.match(/<li\b[^>]*class="chat-message[^"]*"[^>]*>[\s\S]*?<\/li>/g) ?? [];
}

function renderedTime(row: string): string | undefined {
  return row.match(/<time\b[^>]*>([^<]+)<\/time>/)?.[1];
}

describe('ChatPanel', () => {
  it('renders messages oldest first with each author, body, and its own time', () => {
    const markup = render({ history });
    const rows = messageRows(markup);
    const { locale, timeZone } = new Intl.DateTimeFormat().resolvedOptions();

    expect(rows).toHaveLength(2);
    expect(rows[0]).toContain('Morgan Reader');
    expect(rows[0]).toContain('&lt;b&gt;Earlier message&lt;/b&gt;');
    expect(renderedTime(rows[0] ?? '')).toBe(formatMessageTime(history[1]!.createdAt, locale, timeZone));
    expect(rows[1]).toContain('<h3>Casey Collaborator</h3>');
    expect(rows[1]).toContain('Later message');
    expect(renderedTime(rows[1] ?? '')).toBe(formatMessageTime(history[0]!.createdAt, locale, timeZone));
  });

  it('visibly marks the reader’s own message', () => {
    const rows = messageRows(render({ history: [history[1]!] }));

    expect(rows[0]).toContain('chat-message--own');
    expect(rows[0]).toContain('data-current-reader="true"');
    expect(rows[0]).toContain('You');
  });

  it('shows a quiet empty state without any message rows', () => {
    const markup = render({ history: [] });

    expect(markup).toContain('No messages yet.');
    expect(markup).toContain('chat-empty');
    expect(messageRows(markup)).toHaveLength(0);
  });

  it('shows loading as a quiet status', () => {
    const markup = render({ loading: true });

    expect(markup).toContain('role="status"');
    expect(markup).toContain('Loading messages…');
    expect(messageRows(markup)).toHaveLength(0);
  });

  it('renders an error in a readable alert', () => {
    const markup = render({ error: 'The messages could not be loaded.' });

    expect(markup).toContain('role="alert"');
    expect(markup).toContain('The messages could not be loaded.');
  });

  it('renders the current draft in the message input', () => {
    const markup = render({ draft: 'Draft for the group' });

    expect(markup).toContain('value="Draft for the group"');
  });

  it('disables sending when the draft contains only whitespace', () => {
    const markup = render({ draft: '   ' });
    const sendButton = markup.match(/<button\b[^>]*>Send<\/button>/)?.[0] ?? '';

    expect(sendButton).toContain('disabled=""');
  });
});
