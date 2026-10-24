import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ChatMessageResource, ChatProposalResource } from '../api/types';
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

const sessionId = '77777777-7777-4777-8777-777777777777';
const card: ChatMessageResource = {
  id: '22222222-2222-4222-8222-222222222222',
  sessionId,
  kind: 'PROPOSAL',
  authorUserId: '33333333-3333-4333-8333-333333333333',
  authorDisplayName: 'Casey Collaborator',
  body: 'Suggested change: Add a Patient class',
  createdAt: '2026-02-01T15:10:00Z',
};
const pending: ChatProposalResource = {
  id: '11111111-1111-4111-8111-111111111111',
  messageId: card.id,
  sessionId,
  requestedByUserId: card.authorUserId ?? '',
  requestedByDisplayName: 'Casey Collaborator',
  utterance: 'Add a Patient class',
  operations: [{ type: 'classifier.create', targetId: null, args: { targetName: 'Patient' } }],
  baseRevision: 4,
  status: 'PENDING',
  decidedByUserId: null,
  decidedByDisplayName: null,
  decidedAt: null,
  resultSummary: null,
  createdAt: '2026-02-01T15:10:00Z',
};

function props(overrides: Partial<ChatPanelProps> = {}): ChatPanelProps {
  return {
    history: [card],
    userId: '11111111-1111-4111-8111-111111111111',
    loading: false,
    error: null,
    draft: '',
    busy: false,
    onDraftChange: () => undefined,
    onSend: () => undefined,
    onAskAi: () => undefined,
    sessions: [],
    sessionsLoading: false,
    activeSessionId: sessionId,
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
    proposals: [pending],
    ...overrides,
  };
}

function render(overrides: Partial<ChatPanelProps> = {}): string {
  return renderToStaticMarkup(createElement(ChatPanel, props(overrides)));
}

describe('chat suggestions', () => {
  it('shows the future change to every member, before anything is applied', () => {
    const markup = render();
    expect(markup).toContain('Suggested change');
    expect(markup).toContain('Waiting for an editor');
    expect(markup).toContain('If accepted, the diagram will change like this');
    expect(markup).toContain('Crear clase');
    expect(markup).toContain('Patient');
  });

  it('offers accept and reject to an editor, and only an explanation to a viewer', () => {
    const asEditor = render({ canDecideProposals: true });
    expect(asEditor).toContain('Accept and apply');
    expect(asEditor).toContain('Reject');

    const asViewer = render({ canDecideProposals: false });
    expect(asViewer).not.toContain('Accept and apply');
    expect(asViewer).toContain('Only an editor or owner can accept this suggestion.');
  });

  it('records the outcome and removes the actions once decided', () => {
    const markup = render({
      canDecideProposals: true,
      proposals: [{
        ...pending,
        status: 'ACCEPTED',
        decidedByDisplayName: 'Morgan Reader',
        resultSummary: '1 of 1 change applied.',
      }],
    });
    expect(markup).toContain('Applied');
    expect(markup).toContain('The suggested change:');
    expect(markup).not.toContain('If accepted');
    expect(markup).toContain('Morgan Reader: 1 of 1 change applied.');
    expect(markup).not.toContain('Accept and apply');
  });

  it('offers Suggest change only to editors, and only with text to send', () => {
    expect(render({ canDecideProposals: true, onSuggest: () => true, draft: 'Add Patient' }))
      .toContain('Suggest change');
    expect(render({ canDecideProposals: false, onSuggest: () => true, draft: 'Add Patient' }))
      .not.toContain('Suggest change');
    expect(render({ canDecideProposals: true, draft: 'Add Patient' })).not.toContain('Suggest change');
  });

  it('surfaces why a suggestion could not be made', () => {
    expect(render({ proposalNotice: 'The local AI is not enabled on this deployment.' }))
      .toContain('The local AI is not enabled on this deployment.');
  });
});
