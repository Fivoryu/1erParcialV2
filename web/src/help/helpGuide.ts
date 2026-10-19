import type {
  HelpGuideRequest,
  HelpGuideResponse,
} from '../api/types';
import { EDITOR_SECTIONS, type EditorSectionId } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';

export type HelpGuidePanelState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'answered'; response: HelpGuideResponse }
  | { kind: 'error'; message: string }
  | { kind: 'unsupported'; message: string };

export function buildHelpGuideRequest(
  question: string,
  openSection: EditorSectionId | null,
): HelpGuideRequest {
  return {
    question: question.trim(),
    topics: HELP_TOPICS.map(({ slug, title, summary }) => ({ slug, title, summary })),
    editorState: {
      openSection,
      sections: EDITOR_SECTIONS.map((section) => section.id),
    },
  };
}

export function parseHelpGuideResponse(value: unknown, allowedSlugs: readonly string[]): HelpGuideResponse {
  if (!isRecord(value)
    || value.schemaVersion !== 1
    || typeof value.answer !== 'string'
    || value.answer.trim().length === 0
    || !Array.isArray(value.suggestedSlugs)) {
    throw new Error('The help guide returned an invalid response.');
  }

  const allowed = new Set(allowedSlugs);
  const suggestedSlugs = [...new Set(value.suggestedSlugs.filter(
    (slug): slug is string => typeof slug === 'string' && allowed.has(slug),
  ))];
  return {
    schemaVersion: 1,
    answer: value.answer.trim(),
    suggestedSlugs,
  };
}

export function classifyHelpGuideError(error: unknown): Extract<HelpGuidePanelState, { kind: 'error' | 'unsupported' }> {
  if (isRecord(error) && error.code === 'PROVIDER_DISABLED') {
    return {
      kind: 'unsupported',
      message: 'The help guide is not available on this deployment because local AI support is disabled.',
    };
  }
  return {
    kind: 'error',
    message: error instanceof Error && error.message.trim().length > 0
      ? error.message
      : 'The help guide could not answer just now. Please try again.',
  };
}

export function getHelpGuideStatusText(state: HelpGuidePanelState): string | null {
  switch (state.kind) {
    case 'idle':
      return 'Ask about features described in the app’s help topics.';
    case 'loading':
      return 'Preparing an answer…';
    case 'answered':
      return null;
    case 'error':
    case 'unsupported':
      return state.message;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
