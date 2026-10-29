import type { EditorSectionId } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';

export type TourTarget =
  | 'class-add' | 'class-name' | 'attribute-add' | 'attribute-name'
  | 'operation-add' | 'relationship-select' | 'validation' | 'canvas' | 'project-list'
  | `section-${EditorSectionId}`
  | 'xmi-export' | 'xmi-file' | 'presence' | 'chat-session' | 'chat-input' | 'chat-suggest'
  | 'members-list' | 'members-invite' | 'members-role'
  | 'review-run' | 'review-results' | 'generation-new' | 'generation-target'
  | 'generation-run' | 'generation-deployment' | 'version-tabs';

export type VisualTourStep = Readonly<{
  target: TourTarget;
  instruction: string;
  missingHint: string;
  advanceOn: 'click' | 'manual';
}>;

export type TourStep = Readonly<{
  slug: string;
  title: string;
  explanation: string;
  section?: EditorSectionId;
  visualSteps: readonly VisualTourStep[];
}>;

const step = (
  target: TourTarget,
  instruction: string,
  missingHint: string,
  advanceOn: VisualTourStep['advanceOn'] = 'manual',
): VisualTourStep => ({ target, instruction, missingHint, advanceOn });

const openSection = (id: EditorSectionId, label: string): VisualTourStep => step(
  `section-${id}`,
  `Hacé clic en «${label}» para abrir este panel.`,
  'Si hay otra ventana abierta, cerrala primero para volver al editor.',
  'click',
);

const CLASS_VISUAL_STEPS: readonly VisualTourStep[] = [
  step('class-add', 'Hacé clic en «Clase» para crear una clase en el lienzo.',
    'Buscá la barra de herramientas del editor o continuá con el siguiente paso.', 'click'),
  step('class-name', 'Hacé doble clic en el nombre de la clase, escribí el nuevo nombre y presioná Enter.',
    'Primero creá o seleccioná una clase en el lienzo.'),
  step('attribute-add', 'En el panel de detalles, presioná «Agregar atributo».',
    'Seleccioná una clase y desplegá la sección «Atributos» del panel de detalles.', 'click'),
  step('attribute-name', 'Hacé doble clic en el atributo del lienzo para cambiar su nombre y presioná Enter.',
    'Primero agregá o seleccioná un atributo de la clase.'),
  step('operation-add', 'Seleccioná una clase y usá «Agregar operación» para añadir una operación.',
    'Seleccioná una clase y desplegá «Operaciones» en el panel de detalles.'),
  step('relationship-select', 'Para unir clases, elegí un tipo de relación con esta flecha y seguí las indicaciones del editor.',
    'Volvé al lienzo para encontrar la herramienta de relación.'),
  step('validation', 'Los contadores de validación muestran errores y advertencias del diagrama.',
    'Volvé al lienzo para ver la validación.'),
];

const VISUAL_STEPS: Readonly<Record<string, readonly VisualTourStep[]>> = {
  'draw-and-edit-classes': CLASS_VISUAL_STEPS,
  'context-menu-and-multiplicity': [
    step('class-name', 'Seleccioná una clase y hacé clic derecho sobre ella para ver sus acciones.',
      'Primero creá o seleccioná una clase en el lienzo.'),
    step('relationship-select', 'Para crear una relación, elegí su tipo aquí y conectá dos clases.',
      'Volvé al lienzo para encontrar la herramienta de relación.'),
    step('canvas', 'Hacé clic derecho sobre una relación para cambiar su tipo o la multiplicidad de un extremo.',
      'Necesitás una relación en el lienzo para abrir su menú.'),
  ],
  'export-and-import-xmi': [
    openSection('xmi', 'Intercambio XMI'),
    step('xmi-export', '«Exportar XMI» descarga el diagrama; no cambia el proyecto.',
      'Abrí Intercambio XMI. La exportación puede estar temporalmente ocupada o bloqueada.'),
    step('xmi-file', 'Elegí un archivo para revisar su vista previa antes de importar.',
      'Abrí Intercambio XMI para seleccionar un archivo.'),
    step('xmi-file', 'La importación solo se aplica cuando la confirmás. Reemplazar elimina el contenido anterior y no se puede deshacer.',
      'Si no querés importar, no selecciones ni confirmes ningún archivo.'),
  ],
  'collaborate-in-real-time': [
    openSection('collaboration', 'Colaboración'),
    step('presence', 'Acá podés ver quién está presente y quién tiene el permiso de edición.',
      'Abrí Colaboración para ver la presencia.'),
    step('chat-input', 'El chat permite conversar con las demás personas del diagrama.',
      'Abrí Colaboración y elegí o creá una sesión de chat.'),
  ],
  'invite-and-manage-members': [
    openSection('members', 'Integrantes'),
    step('members-list', 'Revisá la lista y los roles de quienes participan en el proyecto.',
      'Abrí Integrantes para ver la lista.'),
    step('members-invite', 'Si sos propietario, escribí el usuario de la persona que querés invitar.',
      'El formulario está disponible para propietarios cuando la lista termina de cargar.'),
    step('members-role', 'Elegí el rol antes de enviar la invitación. La guía no envía ni cambia permisos.',
      'Solo los propietarios pueden administrar invitaciones y roles.'),
  ],
  'generate-a-backend': [
    openSection('generation', 'Generación'),
    step('generation-new', 'Abrí «Nuevo destino» para indicar el nombre del servicio y de su base de datos.',
      'Abrí Generación para configurar un destino.'),
    step('generation-target', 'Seleccioná un destino y un perfil; después podés generar el backend.',
      'Creá un destino y esperá a que el perfil esté disponible.'),
    step('generation-run', '«Generar backend» inicia el proceso. Cuando esté listo, podés descargar el ZIP.',
      'Elegí un destino y un perfil; la generación puede estar bloqueada por otra edición.'),
  ],
  'deploy-the-generated-backend': [
    openSection('generation', 'Generación'),
    step('generation-run', 'Primero generá el backend y esperá a que termine.',
      'Elegí un destino y un perfil para generar el backend.'),
    step('generation-deployment', 'En Implementación podés iniciar o detener el backend y consultar su dirección de red.',
      'La implementación aparece después de iniciar una generación. La guía no la ejecuta.'),
  ],
  'review-the-diagram': [
    openSection('review', 'Revisar diagrama'),
    step('review-run', 'Usá «Ejecutar revisión» para actualizar los resultados del diagrama.',
      'Abrí Revisar diagrama y esperá a que termine cualquier revisión en curso.'),
    step('review-results', 'Leé los errores y advertencias antes de generar. Las propuestas de IA requieren una acción y aceptación separadas.',
      'Abrí Revisar diagrama para consultar los resultados.'),
  ],
  'assistant-with-natural-language': [
    openSection('collaboration', 'Colaboración'),
    step('chat-input', 'Escribí el cambio que querés describir en el chat del equipo.',
      'Abrí Colaboración y elegí o creá una sesión de chat.'),
    step('chat-suggest', 'Una persona editora puede elegir «Sugerir cambio». Nada se aplica hasta que una persona editora acepte la propuesta.',
      'Esta opción depende de tu rol, de una sesión y de la disponibilidad de la IA.'),
  ],
  'diagram-chat': [
    openSection('collaboration', 'Colaboración'),
    step('chat-session', 'Elegí una sesión para consultar su conversación, o creá una nueva.',
      'Abrí Colaboración y esperá a que carguen las sesiones.'),
    step('chat-input', 'Escribí un mensaje; enviarlo, adjuntar imágenes o pedir ayuda a la IA son acciones explícitas.',
      'Abrí Colaboración y elegí o creá una sesión de chat.'),
  ],
  'versioning-and-branches': [
    openSection('vcs', 'Versionado'),
    step('version-tabs', 'Usá estas pestañas para consultar el historial, las ramas y las revisiones.',
      'Abrí Versionado para ver sus pestañas.'),
    step('version-tabs', 'Crear ramas, guardar o fusionar depende de tus permisos y requiere tu confirmación.',
      'Abrí Versionado para consultar las opciones disponibles.'),
  ],
  'create-and-open-projects': [
    step('project-list', 'Volvé a «Proyectos» para crear o abrir otro proyecto. El recorrido se cierra al salir del editor.',
      'El enlace a Proyectos está en la parte superior del editor.'),
  ],
};

const TOUR_TOPIC_SLUGS = [
  'draw-and-edit-classes',
  'context-menu-and-multiplicity',
  'export-and-import-xmi',
  'collaborate-in-real-time',
  'invite-and-manage-members',
  'generate-a-backend',
  'deploy-the-generated-backend',
  'review-the-diagram',
  'assistant-with-natural-language',
  'diagram-chat',
  'versioning-and-branches',
  'create-and-open-projects',
] as const;

function topicToTourStep(slug: (typeof TOUR_TOPIC_SLUGS)[number]): TourStep {
  const topic = HELP_TOPICS.find((candidate) => candidate.slug === slug);
  const visualSteps = VISUAL_STEPS[slug];
  if (!topic || !visualSteps || visualSteps.length === 0) {
    throw new Error(`Missing help topic or visual steps for editor tour: ${slug}`);
  }

  return {
    slug,
    title: topic.title,
    explanation: topic.summary,
    ...(topic.section ? { section: topic.section } : {}),
    visualSteps,
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
