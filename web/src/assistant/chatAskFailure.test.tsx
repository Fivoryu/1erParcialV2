import { createElement, isValidElement, type ReactElement, type ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from '../api/client';
import type { ChatMessageResource } from '../api/types';
import ChatPanel, { type ChatPanelProps } from '../collaboration/ChatPanel';
import { loginErrorMessage } from '../pages/LoginPage';
import { chatAskErrorMessage, PROVIDER_DISABLED_MESSAGE } from './chatAskFailure';

const speechState = vi.hoisted(() => ({
  supported: true,
  listening: false,
  transcript: '',
  interim: '',
  error: null as string | null,
  reset: vi.fn(),
}));

vi.mock('react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react')>();
  return {
    ...actual,
    useEffect: (effect: () => void) => { effect(); },
    useRef: (current: unknown) => ({ current }),
    useState: (initial: unknown) => [initial, vi.fn()],
  };
});

vi.mock('./useSpeechRecognition', () => ({
  useSpeechRecognition: () => speechState,
}));

const readerId = '11111111-1111-4111-8111-111111111111';
const session = {
  id: '77777777-7777-4777-8777-777777777777',
  viewId: '66666666-6666-4666-8666-666666666666',
  title: 'General',
  createdByUserId: readerId,
  createdAt: '2026-02-01T14:00:00Z',
};
function askErrorMessage(error: unknown): string {
  return chatAskErrorMessage(error, loginErrorMessage);
}

const dictatedMessage: ChatMessageResource = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  kind: 'HUMAN',
  authorUserId: readerId,
  authorDisplayName: 'Morgan Reader',
  body: 'Dictated consultation query',
  createdAt: '2026-02-01T15:10:00Z',
};

function panelProps(overrides: Partial<ChatPanelProps> = {}): ChatPanelProps {
  return {
    history: [dictatedMessage],
    userId: readerId,
    loading: false,
    error: null,
    draft: '',
    busy: false,
    onDraftChange: vi.fn(),
    onSend: vi.fn(),
    onAskAi: vi.fn(),
    sessions: [session],
    sessionsLoading: false,
    activeSessionId: session.id,
    sessionActionBusy: false,
    onSessionChange: vi.fn(),
    onCreateSession: vi.fn(),
    aiStatus: null,
    contextMeter: null,
    contextMeterLoading: false,
    contextError: null,
    compactBusy: false,
    compactMessage: null,
    onCompact: vi.fn(),
    memories: [],
    memoriesLoading: false,
    memoryError: null,
    memoryMessage: null,
    extractBusy: false,
    onExtractMemories: vi.fn(),
    canManageMemory: false,
    onForgetMemory: vi.fn(),
    onForgetAllMemories: vi.fn(),
    canDeleteHistory: false,
    onDeleteHistory: vi.fn(),
    historyMessage: null,
    attachmentUrls: {},
    ...overrides,
  };
}

interface ButtonProps {
  children?: ReactNode;
  className?: string;
  onClick?: (event: never) => void;
  disabled?: boolean;
}

function findButton(node: ReactNode, matches: (props: ButtonProps) => boolean): ReactElement<ButtonProps> | null {
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findButton(child, matches);
      if (found) return found;
    }
    return null;
  }
  if (!isValidElement(node)) return null;
  const element = node as ReactElement<ButtonProps>;
  if (element.type === 'button' && matches(element.props)) return element;
  return findButton(element.props.children, matches);
}

function renderMarkup(overrides: Partial<ChatPanelProps> = {}): string {
  return renderToStaticMarkup(createElement(ChatPanel, panelProps(overrides)));
}

function micButton(markup: string): string {
  return markup.match(/<button\b[^>]*class="button chat-dictate"[^>]*>/)?.[0] ?? '';
}

describe('chat ask failure feedback', () => {
  it('explains that a disabled local provider is an operator configuration issue', () => {
    const error = new ApiTransportError(
      'PROVIDER_DISABLED',
      'The AI assistant is not enabled on this deployment.',
      [],
      null,
      503,
    );

    expect(askErrorMessage(error)).toBe(PROVIDER_DISABLED_MESSAGE);
    expect(askErrorMessage(error)).not.toMatch(/503|PROVIDER_DISABLED|stack/i);
  });

  it('uses the same explanation for a structured 503 and keeps other errors generic', () => {
    const structuredUnavailable = new ApiTransportError('AI_UNAVAILABLE', 'Unavailable.', [], null, 503);
    const unstructuredUnavailable = new ApiTransportError(
      'HTTP_ERROR',
      'The server returned HTTP 503.',
      [],
      null,
      503,
    );
    const unrelated = new ApiTransportError(
      'HTTP_ERROR',
      'The server returned HTTP 500.',
      [],
      null,
      500,
    );

    expect(askErrorMessage(structuredUnavailable)).toBe(PROVIDER_DISABLED_MESSAGE);
    expect(askErrorMessage(unstructuredUnavailable)).toBe(
      'Your request could not be completed. Check the connection and try again.',
    );
    expect(askErrorMessage(unrelated)).toBe(
      'Your request could not be completed. Check the connection and try again.',
    );
  });

  it('restores a failed dictated ask to the input and leaves the microphone usable', async () => {
    const onAskAi = vi.fn().mockResolvedValue(false);
    const onDraftChange = vi.fn();
    speechState.transcript = '';
    speechState.listening = false;
    speechState.reset.mockClear();

    const tree = ChatPanel(panelProps({ onAskAi, onDraftChange }));
    const askButton = findButton(tree, (props) => props.children === 'Ask AI');

    expect(askButton).not.toBeNull();
    await askButton?.props.onClick?.(undefined as never);

    expect(onAskAi).toHaveBeenCalledExactlyOnceWith(dictatedMessage.id);
    expect(onDraftChange).toHaveBeenCalledWith(dictatedMessage.body);
    const markup = renderMarkup({ draft: dictatedMessage.body });
    expect(markup).toContain(`value="${dictatedMessage.body}"`);
    expect(micButton(markup)).not.toContain('disabled=""');
  });

  it('appends completed dictation and resets the speech state', () => {
    const onDraftChange = vi.fn();
    speechState.transcript = 'new dictated text';
    speechState.listening = false;
    speechState.reset.mockClear();

    const markup = renderMarkup({ draft: 'Existing draft', onDraftChange });

    expect(onDraftChange).toHaveBeenCalledWith('Existing draft new dictated text');
    expect(speechState.reset).toHaveBeenCalledOnce();
    expect(micButton(markup)).not.toContain('disabled=""');
    speechState.transcript = '';
  });
});
