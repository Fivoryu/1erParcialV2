import { describe, expect, it } from 'vitest';

import { EDITOR_SECTIONS } from '../pages/editorSections';
import {
  buildHelpGuideRequest,
  classifyHelpGuideError,
  getHelpGuideStatusText,
  parseHelpGuideResponse,
} from './helpGuide';
import type { HelpGuideResponse } from '../api/types';

const allowedSlugs = ['generate-a-backend', 'export-and-import-xmi'];

function response(overrides: Partial<HelpGuideResponse> = {}): HelpGuideResponse {
  return {
    schemaVersion: 1,
    answer: 'Open the generation section.',
    suggestedSlugs: ['generate-a-backend'],
    ...overrides,
  };
}

describe('help guide request and response logic', () => {
  it('maps a question, the shared corpus summary, and the current editor state to a request', () => {
    const request = buildHelpGuideRequest('  How do I generate a backend?  ', 'generation');

    expect(request.question).toBe('How do I generate a backend?');
    expect(request.topics).toEqual(expect.arrayContaining([
      expect.objectContaining({
        slug: 'generate-a-backend',
        title: 'Generar un backend',
        summary: expect.stringContaining('Spring Boot'),
      }),
    ]));
    expect(request.topics[0]).toEqual({
      slug: expect.any(String),
      title: expect.any(String),
      summary: expect.any(String),
    });
    expect(request.editorState).toEqual({
      openSection: 'generation',
      sections: EDITOR_SECTIONS.map((section) => section.id),
    });
  });

  it('parses the versioned response and keeps only unique slugs sent in the request', () => {
    const parsed = parseHelpGuideResponse({
      schemaVersion: 1,
      answer: 'Use the generation section.',
      suggestedSlugs: ['generate-a-backend', 'not-in-the-corpus', 'export-and-import-xmi', 'generate-a-backend'],
    }, allowedSlugs);

    expect(parsed).toEqual({
      schemaVersion: 1,
      answer: 'Use the generation section.',
      suggestedSlugs: ['generate-a-backend', 'export-and-import-xmi'],
    });
  });

  it('rejects unsupported response versions and empty answers', () => {
    expect(() => parseHelpGuideResponse({ ...response(), schemaVersion: 2 }, allowedSlugs)).toThrow();
    expect(() => parseHelpGuideResponse({ ...response(), answer: '  ' }, allowedSlugs)).toThrow();
  });
});

describe('help guide panel states', () => {
  it('describes idle, loading, error, unsupported, and answered states honestly', () => {
    expect(getHelpGuideStatusText({ kind: 'idle' })).toContain('temas de ayuda');
    expect(getHelpGuideStatusText({ kind: 'loading' })).toContain('Preparando');
    expect(getHelpGuideStatusText({ kind: 'error', message: 'Try again later.' })).toBe('Try again later.');
    expect(getHelpGuideStatusText({ kind: 'unsupported', message: 'Local AI is disabled.' }))
      .toBe('Local AI is disabled.');
    expect(getHelpGuideStatusText({ kind: 'answered', response: response() })).toBeNull();
  });

  it('distinguishes unavailable local AI support from other request errors', () => {
    expect(classifyHelpGuideError({ code: 'PROVIDER_DISABLED' })).toEqual({
      kind: 'unsupported',
      message: expect.stringContaining('no está disponible'),
    });
    expect(classifyHelpGuideError(new Error('The server could not be reached.'))).toEqual({
      kind: 'error',
      message: 'The server could not be reached.',
    });
  });
});
