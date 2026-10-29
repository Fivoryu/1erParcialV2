import { describe, expect, it } from 'vitest';

import { EDITOR_SECTIONS } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';
import {
  clampTourStepIndex,
  getTourStep,
  nextTourStepIndex,
  previousTourStepIndex,
  TOUR_STEPS,
} from './tourSteps';

const expectedTopicSlugs = [
  'draw-and-edit-classes',
  'context-menu-and-multiplicity',
  'export-and-import-xmi',
  'collaborate-in-real-time',
  'invite-and-manage-members',
  'generate-a-backend',
  'deploy-the-generated-backend',
];

describe('editor tour steps', () => {
  it('follows a deterministic sequence based on existing help topics', () => {
    const expectedTopics = expectedTopicSlugs.map((slug) => {
      const topic = HELP_TOPICS.find((candidate) => candidate.slug === slug);
      expect(topic, `missing help topic: ${slug}`).toBeDefined();
      return topic!;
    });

    expect(TOUR_STEPS.map((step) => step.title)).toEqual(expectedTopics.map((topic) => topic.title));
    expect(TOUR_STEPS.map((step) => step.explanation)).toEqual(expectedTopics.map((topic) => topic.summary));
    expect(TOUR_STEPS.map((step) => step.section)).toEqual(expectedTopics.map((topic) => topic.section));
  });

  it('guides a beginner through creating, naming, and adding an attribute to a class', () => {
    expect(getTourStep(0)).toMatchObject({
      visualSteps: [
        { target: 'class-add', advanceOn: 'click' },
        { target: 'class-name', advanceOn: 'manual' },
        { target: 'attribute-add', advanceOn: 'click' },
        { target: 'attribute-name', advanceOn: 'manual' },
      ],
    });
  });

  it('associates tour sections only with sections that exist in the editor', () => {
    const validSections = EDITOR_SECTIONS.map((section) => section.id);
    for (const step of TOUR_STEPS) {
      if (step.section !== undefined) {
        expect(validSections).toContain(step.section);
      }
    }
  });

  it('clamps step access and navigation to the first and last step', () => {
    const lastIndex = TOUR_STEPS.length - 1;

    expect(clampTourStepIndex(-3)).toBe(0);
    expect(clampTourStepIndex(lastIndex + 4)).toBe(lastIndex);
    expect(clampTourStepIndex(Number.NaN)).toBe(0);
    expect(getTourStep(-1)).toBe(TOUR_STEPS[0]);
    expect(getTourStep(lastIndex + 1)).toBe(TOUR_STEPS[lastIndex]);
    expect(previousTourStepIndex(-1)).toBe(0);
    expect(nextTourStepIndex(lastIndex)).toBe(lastIndex);
    expect(previousTourStepIndex(2)).toBe(1);
    expect(nextTourStepIndex(2)).toBe(3);
  });
});
