import { describe, expect, it, vi } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ChatMessageResource } from '../api/types';
import ChatPanel, { type ChatPanelProps } from './ChatPanel';

/**
 * T7/L47 dictation contract: the mic button exists only when the browser supports speech
 * recognition, and its accessible name documents what recognition actually is (cloud-processed
 * in Chromium, not local). The transcript itself is appended to the draft through the shared
 * `appendTranscript` helper (tested in assistantPanel.test.ts).
 */
const speechState = vi.hoisted(() => ({ supported: false, transcript: '' }));

vi.mock('../assistant/useSpeechRecognition', () => ({
  useSpeechRecognition: () => ({
    supported: speechState.supported,
    listening: false,
    transcript: speechState.transcript,
    error: null,
    start: () => undefined,
    stop: () => undefined,
    reset: () => undefined,
    dispose: () => undefined,
  }),
}));

function render(overrides: Partial<ChatPanelProps> = {}): string {
  const props: ChatPanelProps = {
    history: [] as ChatMessageResource[],
    userId: '11111111-1111-4111-8111-111111111111',
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

describe('ChatPanel dictation', () => {
  it('renders no mic button when the browser does not support speech recognition', () => {
    speechState.supported = false;
    speechState.transcript = '';
    expect(render().includes('chat-dictate')).toBe(false);
  });

  it('renders a mic button whose accessible name documents cloud-processed recognition', () => {
    speechState.supported = true;
    speechState.transcript = '';
    const markup = render();
    expect(markup.includes('chat-dictate')).toBe(true);
    expect(markup.includes('nube del proveedor del navegador')).toBe(true);
  });
});
