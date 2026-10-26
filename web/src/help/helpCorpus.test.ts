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

  it('explains memory automation, natural-language operations, and shared cross-diagram access', () => {
    const topic = HELP_TOPICS.find((candidate) => candidate.slug === 'diagram-chat');
    expect(topic).toBeDefined();
    const copy = [topic?.summary, ...(topic?.steps ?? [])].join(' ');

    expect(copy).toContain('se extraen automáticamente conocimientos útiles');
    expect(copy).toContain('lea, guarde o revise un recuerdo');
    expect(copy).toContain('versión más reciente del UML');
    expect(copy).toContain('accesibles para todas las personas colaboradoras activas');
  });

  it('explains version history, branches, reviews, and per-person access in user language', () => {
    const topic = HELP_TOPICS.find((candidate) => candidate.slug === 'versioning-and-branches');
    expect(topic).toBeDefined();
    expect(topic?.section).toBe('vcs');
    expect(topic?.steps).toHaveLength(5);
    const copy = [topic?.summary, ...(topic?.steps ?? [])].join(' ');

    expect(copy).toContain('Guarda un cambio con un mensaje claro');
    expect(copy).toContain('quién lo hizo, qué cambió y cuándo');
    expect(copy).toContain('Crea una rama');
    expect(copy).toContain('cambiar entre ramas');
    expect(copy).toContain('Abre una solicitud de revisión entre dos ramas');
    expect(copy).toContain('fusiónala');
    expect(copy).toContain('La persona creadora del diagrama puede otorgar');
    expect(copy).not.toMatch(/\b(?:HTTP|JSON|API|UUID|hash)\b/i);
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
      'versioning-and-branches',
    ]) {
      expect(slugs, `missing topic "${expected}"`).toContain(expected);
    }
  });

  it('sends the assistant to the shared team chat and no longer mentions a separate assistant panel', () => {
    const assistant = HELP_TOPICS.find((topic) => topic.slug === 'assistant-with-natural-language');

    expect(assistant?.section).toBe('collaboration');
    expect(assistant?.steps.join(' ')).toContain('Sugerir cambio');
    for (const topic of HELP_TOPICS) {
      expect(topic.steps.join(' ').toLowerCase(), topic.slug).not.toContain('assistant panel');
    }
  });

  it('tells a reviewer that Explain with AI and Propose a fix open the team chat with a draft', () => {
    const review = HELP_TOPICS.find((topic) => topic.slug === 'review-the-diagram');

    expect(review?.steps.join(' ')).toContain('chat del equipo');
  });
});
