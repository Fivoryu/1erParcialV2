import type { EditorSectionId } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';

export type VisualTourStep = Readonly<{
  target: 'class-add' | 'class-name' | 'attribute-add' | 'attribute-name';
  instruction: string;
  missingHint: string;
  advanceOn: 'click' | 'manual';
}>;

export type TourStep = Readonly<{
  title: string;
  explanation: string;
  section?: EditorSectionId;
  visualSteps?: readonly VisualTourStep[];
}>;

const CLASS_VISUAL_STEPS: readonly VisualTourStep[] = [
  {
    target: 'class-add',
    instruction: 'Hacé clic en «Clase» para crear una clase en el lienzo.',
    missingHint: 'Buscá la barra de herramientas del editor o continuá con el siguiente paso.',
    advanceOn: 'click',
  },
  {
    target: 'class-name',
    instruction: 'Hacé doble clic en el nombre de la clase, escribí el nuevo nombre y presioná Enter.',
    missingHint: 'Primero creá o seleccioná una clase en el lienzo.',
    advanceOn: 'manual',
  },
  {
    target: 'attribute-add',
    instruction: 'En el panel de detalles, presioná «Agregar atributo».',
    missingHint: 'Seleccioná una clase y desplegá la sección «Atributos» del panel de detalles.',
    advanceOn: 'click',
  },
  {
    target: 'attribute-name',
    instruction: 'Hacé doble clic en el atributo del lienzo para cambiar su nombre y presioná Enter.',
    missingHint: 'Primero agregá o seleccioná un atributo de la clase.',
    advanceOn: 'manual',
  },
];

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
    ...(slug === 'draw-and-edit-classes' ? { visualSteps: CLASS_VISUAL_STEPS } : {}),
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
