import { describe, expect, it } from 'vitest';

import { EDITOR_SECTIONS } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const TECHNICAL_NAMES = [
  'run_id',
  'target_id',
  'profile_id',
  'template_id',
  'model_version',
  'artifact_set_hash',
  'session_id',
];

const topicStrings = (): string[] =>
  HELP_TOPICS.flatMap((topic) => [
    topic.title,
    topic.summary,
    ...topic.steps,
    ...topic.keywords,
  ]);

describe('help corpus', () => {
  it('covers every editor section with at least one topic', () => {
    for (const section of EDITOR_SECTIONS) {
      const topics = HELP_TOPICS.filter((topic) => topic.section === section.id);
      expect(
        topics,
        `section "${section.id}" must be covered by at least one topic`,
      ).not.toHaveLength(0);
    }
  });

  it('never names a section that does not exist', () => {
    const validSections = EDITOR_SECTIONS.map((section) => section.id);
    for (const topic of HELP_TOPICS) {
      if (topic.section !== undefined) {
        expect(
          validSections,
          `topic "${topic.slug}" names section "${topic.section}" that must exist`,
        ).toContain(topic.section);
      }
    }
  });

  it('has unique slugs and complete entries', () => {
    const slugs = HELP_TOPICS.map((topic) => topic.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
    for (const topic of HELP_TOPICS) {
      expect(topic.title.trim().length).toBeGreaterThan(0);
      expect(topic.summary.trim().length).toBeGreaterThan(0);
      expect(topic.steps.length).toBeGreaterThan(0);
      for (const step of topic.steps) {
        expect(step.trim().length).toBeGreaterThan(0);
      }
      expect(topic.keywords.length).toBeGreaterThanOrEqual(2);
    }
  });

  it('never puts an identifier or a technical name in the help text', () => {
    for (const text of topicStrings()) {
      expect(text, `UUID pattern leaked into: "${text}"`).not.toMatch(UUID_PATTERN);
      for (const technical of TECHNICAL_NAMES) {
        expect(text.toLowerCase(), `"${technical}" leaked into: "${text}"`).not.toContain(
          technical,
        );
      }
    }
  });

  it('covers the core user journeys', () => {
    const slugs = HELP_TOPICS.map((topic) => topic.slug);
    for (const expected of [
      'create-and-open-projects',
      'draw-and-edit-classes',
      'context-menu-and-multiplicity',
      'export-and-import-xmi',
      'collaborate-in-real-time',
      'invite-and-manage-members',
      'review-the-diagram',
      'generate-a-backend',
      'deploy-the-generated-backend',
      'assistant-with-natural-language',
      'diagram-chat',
    ]) {
      expect(slugs, `missing topic "${expected}"`).toContain(expected);
    }
  });
});
