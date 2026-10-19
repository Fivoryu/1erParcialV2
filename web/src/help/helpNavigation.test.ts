import { describe, expect, it } from 'vitest';

import { EDITOR_SECTIONS } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';
import {
  getHelpSectionForSlug,
  getHelpNavigationSuggestions,
} from './helpNavigation';

describe('help guide navigation suggestions', () => {
  it('maps only corpus topics with a real editor section', () => {
    expect(getHelpSectionForSlug('generate-a-backend')).toBe('generation');
    expect(getHelpSectionForSlug('deploy-the-generated-backend')).toBe('generation');
    expect(getHelpSectionForSlug('export-and-import-xmi')).toBe('xmi');
    expect(getHelpSectionForSlug('diagram-chat')).toBeNull();
    expect(getHelpSectionForSlug('unknown-topic')).toBeNull();

    for (const topic of HELP_TOPICS) {
      const section = getHelpSectionForSlug(topic.slug);
      if (topic.section === undefined) {
        expect(section).toBeNull();
      } else {
        expect(EDITOR_SECTIONS.map(({ id }) => id)).toContain(section);
        expect(section).toBe(topic.section);
      }
    }
  });

  it('filters non-navigable suggestions and deduplicates topics for the same section', () => {
    expect(getHelpNavigationSuggestions([
      'diagram-chat',
      'generate-a-backend',
      'unknown-topic',
      'deploy-the-generated-backend',
      'export-and-import-xmi',
    ], null)).toEqual([
      { section: 'generation', label: 'Open Generation' },
      { section: 'xmi', label: 'Open XMI interchange' },
    ]);
  });

  it('omits a navigation button when its section is already open', () => {
    expect(getHelpNavigationSuggestions([
      'generate-a-backend',
      'export-and-import-xmi',
    ], 'generation')).toEqual([
      { section: 'xmi', label: 'Open XMI interchange' },
    ]);
  });
});
