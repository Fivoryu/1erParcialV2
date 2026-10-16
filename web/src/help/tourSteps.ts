import type { EditorSectionId } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';

export type TourStep = Readonly<{
  title: string;
  explanation: string;
  section?: EditorSectionId;
}>;

const TOUR_TOPIC_SLUGS = [
  'draw-and-edit-classes',
  'context-menu-and-multiplicity',
  'export-and-import-xmi',
  'collaborate-in-real-time',
  'invite-and-manage-members',
  'generate-a-backend',
  'deploy-the-generated-backend',
] as const;

function topicToTourStep(slug: (typeof TOUR_TOPIC_SLUGS)[number]): TourStep {
  const topic = HELP_TOPICS.find((candidate) => candidate.slug === slug);
  if (!topic) {
    throw new Error(`Missing help topic for editor tour: ${slug}`);
  }

  return {
    title: topic.title,
    explanation: topic.summary,
    ...(topic.section ? { section: topic.section } : {}),
  };
}

/** Fixed onboarding content selected from the versioned inventory of real UI surfaces. */
export const TOUR_STEPS: readonly TourStep[] = Object.freeze(TOUR_TOPIC_SLUGS.map(topicToTourStep));

export function clampTourStepIndex(index: number): number {
  if (Number.isNaN(index)) {
    return 0;
  }
  return Math.min(Math.max(Math.trunc(index), 0), TOUR_STEPS.length - 1);
}

export function getTourStep(index: number): TourStep {
  return TOUR_STEPS[clampTourStepIndex(index)]!;
}

export function previousTourStepIndex(index: number): number {
  return Math.max(clampTourStepIndex(index) - 1, 0);
}

export function nextTourStepIndex(index: number): number {
  return Math.min(clampTourStepIndex(index) + 1, TOUR_STEPS.length - 1);
}
