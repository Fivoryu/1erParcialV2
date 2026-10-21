import { createElement, isValidElement, type ReactElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ChatMessageResource } from '../api/types';
import { formatMessageTime } from './messageTime';
import ChatPanel, { type ChatPanelProps } from './ChatPanel';

vi.mock('react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react')>();
  return {
    ...actual,
    useEffect: () => undefined,
    useRef: (current: unknown) => ({ current }),
    useState: (initial: unknown) => [initial, () => undefined],
  };
});

vi.mock('../assistant/useSpeechRecognition', () => ({
  useSpeechRecognition: () => ({
    supported: false,
    listening: false,
    transcript: '',
    interim: '',
    error: null,
    start: () => undefined,
    stop: () => undefined,
    reset: () => undefined,
  }),
}));

const readerId = '11111111-1111-4111-8111-111111111111';
const session = {
  id: '77777777-7777-4777-8777-777777777777',
  viewId: '66666666-6666-4666-8666-666666666666',
  title: 'General',
  createdByUserId: readerId,
  createdAt: '2026-02-01T14:00:00Z',
};
const meter = {
  contextWindow: 4096,
  reservedForResponse: 512,
  promptBudget: 3584,
  instructions: 80,
  diagramData: 300,
  memories: 40,
  history: 2900,
  images: 600,
  totalUsed: 3920,
  promptThreshold: 2868,
  compactionPending: true,
  percent: 109,
};
const memory = {
  memoryId: '88888888-8888-4888-8888-888888888888',
  kind: 'DECISION' as const,
  status: 'ACTIVE' as const,
  content: 'Keep the actor outside the system boundary.',
  sourceSessionId: session.id,
  sourceMessageId: '99999999-9999-4999-8999-999999999999',
  replacedBy: null,
  createdAt: '2026-02-01T14:30:00Z',
};
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

function panelProps(overrides: Partial<ChatPanelProps> = {}): ChatPanelProps {
  return {
    history: [],
    userId: readerId,
    loading: false,
    error: null,
    draft: '',
    busy: false,
    onDraftChange: () => undefined,
    onSend: () => undefined,
    onAskAi: () => undefined,
    sessions: [session],
    sessionsLoading: false,
    activeSessionId: session.id,
    sessionActionBusy: false,
    onSessionChange: () => undefined,
    onCreateSession: () => undefined,
    aiStatus: null,
    contextMeter: null,
    contextMeterLoading: false,
    contextError: null,
    compactBusy: false,
    compactMessage: null,
    onCompact: () => undefined,
    memories: [],
    memoriesLoading: false,
    memoryError: null,
    memoryMessage: null,
    extractBusy: false,
    onExtractMemories: () => undefined,
    canManageMemory: false,
    onForgetMemory: () => undefined,
    onForgetAllMemories: () => undefined,
    canDeleteHistory: false,
    onDeleteHistory: () => undefined,
    historyMessage: null,
    attachmentUrls: {},
    ...overrides,
  };
}

function render(overrides: Partial<ChatPanelProps> = {}): string {
  return renderToStaticMarkup(createElement(ChatPanel, panelProps(overrides)));
}

function messageRows(markup: string): string[] {
  return markup.match(/<li\b[^>]*class="chat-message[^"]*"[^>]*>[\s\S]*?<\/li>/g) ?? [];
}

function renderedTime(row: string): string | undefined {
  return row.match(/<time\b[^>]*>([^<]+)<\/time>/)?.[1];
}

function askButtonMarkup(markup: string): string {
  return markup.match(/<button\b[^>]*>Ask AI<\/button>/)?.[0] ?? '';
}

function findAskButton(node: ReactNode): ReactElement<{
  children?: ReactNode;
  onClick?: (event: never) => void;
  disabled?: boolean;
}> | null {
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findAskButton(child);
      if (found) return found;
    }
    return null;
  }
  if (!isValidElement(node)) return null;
  const element = node as ReactElement<{
    children?: ReactNode;
    onClick?: (event: never) => void;
    disabled?: boolean;
  }>;
  if (element.type === 'button' && element.props.children === 'Ask AI') return element;
  return findAskButton(element.props.children);
}

const ownHumanMessage: ChatMessageResource = {
  ...history[1]!,
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  kind: 'HUMAN',
  body: 'My own question',
};
const otherHumanMessage: ChatMessageResource = {
  ...history[0]!,
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  kind: 'HUMAN',
};
const assistantMessage: ChatMessageResource = {
  ...ownHumanMessage,
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  kind: 'AI',
  authorUserId: null,
  authorDisplayName: 'Assistant',
  body: 'AI answer',
};

describe('ChatPanel', () => {
  it('uses the conversation eyebrow and two-column conversation/context layout', () => {
    const markup = render();

    expect(markup).toContain('class="chat-layout"');
    expect(markup).toContain('class="chat-main"');
    expect(markup).toContain('class="chat-side"');
    expect(markup).toContain('<p class="eyebrow">Conversation</p>');
    expect(markup).not.toContain('Live collaboration');
  });

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

  it('renders AI markdown while keeping HUMAN message bodies as plain text', () => {
    const human = { ...ownHumanMessage, body: '**human answer**' };
    const assistant = { ...assistantMessage, body: '**assistant answer**\n- detail' };
    const markup = render({ history: [human, assistant] });

    expect(markup).toContain('chat-message-body chat-message-body--markdown');
    expect(markup).toContain('<strong>assistant answer</strong>');
    expect(markup).toContain('<ul><li>detail</li></ul>');
    expect(markup).not.toContain('**assistant answer**');
    expect(markup).toContain('<p class="chat-message-body">**human answer**</p>');
    expect(markup).not.toContain('<strong>human answer</strong>');
    expect(markup).not.toContain('<p class="chat-message-body chat-message-body--markdown">**human answer**</p>');
  });

  it('renders Ask AI only on the reader’s HUMAN message and clicking passes its exact id', () => {
    const markup = render({ history: [ownHumanMessage, otherHumanMessage, assistantMessage] });
    const rows = messageRows(markup);
    const ownRow = rows.find((row) => row.includes(ownHumanMessage.body)) ?? '';
    const otherRow = rows.find((row) => row.includes(otherHumanMessage.body)) ?? '';
    const aiRow = rows.find((row) => row.includes(assistantMessage.body)) ?? '';

    expect(ownRow).toContain('>Ask AI</button>');
    expect(otherRow).not.toContain('Ask AI');
    expect(aiRow).not.toContain('Ask AI');

    const onAskAi = vi.fn();
    const button = findAskButton(ChatPanel(panelProps({ history: [ownHumanMessage], onAskAi })));
    expect(button).not.toBeNull();
    button?.props.onClick?.(undefined as never);
    expect(onAskAi).toHaveBeenCalledExactlyOnceWith(ownHumanMessage.id);
  });

  it('disables Ask AI while the chat is busy or there is no active session', () => {
    const busyButton = askButtonMarkup(render({ history: [ownHumanMessage], busy: true }));
    const noSessionButton = askButtonMarkup(render({
      history: [ownHumanMessage],
      sessions: [],
      activeSessionId: null,
    }));

    expect(busyButton).toContain('disabled=""');
    expect(noSessionButton).toContain('disabled=""');
  });

  it('disables Ask AI for QUEUED and RUNNING states', () => {
    for (const state of ['QUEUED', 'RUNNING'] as const) {
      const button = askButtonMarkup(render({
        history: [ownHumanMessage],
        aiStatus: { sessionId: session.id, state },
      }));
      expect(button).toContain('disabled=""');
    }
  });

  it('keeps Ask AI enabled for COMPLETED, FAILED, CANCELLED, and null states', () => {
    for (const state of ['COMPLETED', 'FAILED', 'CANCELLED', null] as const) {
      const button = askButtonMarkup(render({
        history: [ownHumanMessage],
        aiStatus: state ? { sessionId: session.id, state } : null,
      }));
      expect(button).toContain('>Ask AI</button>');
      expect(button).not.toContain('disabled=""');
    }
  });

  it('shows a quiet empty state without any message rows', () => {
    const markup = render({ history: [] });

    expect(markup).toContain('No messages yet.');
    expect(markup).toContain('Start the conversation or ask the AI about the diagram.');
    expect(markup).toContain('class="chat-conversation-area"');
    expect(markup).toContain('class="chat-empty"');
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

  it('renders the session selector with the active session and a create action', () => {
    const review = { ...session, id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', title: 'Review' };
    const markup = render({ sessions: [session, review] });

    expect(markup).toContain('for="chat-session-selector">Chat session</label>');
    expect(markup).toContain('<option value="77777777-7777-4777-8777-777777777777" selected="">General</option>');
    expect(markup).toContain('<option value="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa">Review</option>');
    expect(markup).toContain('New session');
  });

  it('shows the actual AI request state and reason', () => {
    const markup = render({ aiStatus: { sessionId: session.id, state: 'FAILED', reason: 'Provider unavailable' } });

    expect(markup).toContain('AI request failed: Provider unavailable');
    expect(markup).toContain('chat-ai-status--failed');
  });

  it('renders context budget components, threshold state, and the explicit compact action', () => {
    const markup = render({ contextMeter: meter });

    expect(markup).toContain('109% of the input budget (3920 / 3584 tokens)');
    expect(markup).toContain('<details class="context-meter-details"><summary>Details</summary>');
    expect(markup).toContain('<dt>Images</dt><dd>600</dd>');
    expect(markup).toContain('<dt>Compaction threshold</dt><dd>2868</dd>');
    expect(markup).toContain('data-threshold-reached="true"');
    expect(markup).toContain('accent-color:#b42318');
    expect(markup).toContain('Compact this session context');
  });

  it('lists memory kind, status, and evidence with distinct owner-only forget controls', () => {
    const markup = render({ memories: [memory], canManageMemory: true, canDeleteHistory: true });

    expect(markup).toContain('DECISION');
    expect(markup).toContain('ACTIVE');
    expect(markup).toContain(`session ${session.id}`);
    expect(markup).toContain('Sources:');
    expect(markup).toContain('Extract memories');
    expect(markup).toContain('Forget this memory');
    expect(markup).toContain('Forget all diagram memories');
    expect(markup).toContain('Delete history for this session');
  });

  it('does not expose forget controls to non-owners and keeps history deletion separate', () => {
    const markup = render({ memories: [memory], canManageMemory: false, canDeleteHistory: false });

    expect(markup).toContain('Only the project OWNER can forget diagram memories.');
    expect(markup).not.toContain('Forget this memory');
    expect(markup).not.toContain('Forget all diagram memories');
    expect(markup).not.toContain('Delete history for this session');
    expect(markup).toContain('Deleting chat history does not forget memory.');
  });

  it('explains automatic memory, natural-language operations, current UML, and shared search scope', () => {
    const markup = render();

    expect(markup).toContain('Useful design knowledge is extracted automatically after AI requests');
    expect(markup).toContain('Ask naturally in chat to read, save, or revise memories');
    expect(markup).toContain('Each AI request uses the latest UML for this diagram');
    expect(markup).toContain('Shared results come only from projects every active collaborator can access');
  });

  it('renders authorized image bytes as a thumbnail on the associated message', () => {
    const imageMessage: ChatMessageResource = {
      ...history[0]!,
      attachments: ['bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'],
    };
    const markup = render({
      history: [imageMessage],
      attachmentUrls: { 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb': 'blob:chat-image' },
    });

    expect(markup).toContain('src="blob:chat-image"');
    expect(markup).toContain('alt="Image attached by Casey Collaborator"');
    expect(markup).toContain('Attach images');
    expect(markup).toContain('accept="image/jpeg,image/png,image/webp"');
  });
});
