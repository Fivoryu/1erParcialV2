import type { EditorSectionId } from '../pages/editorSections';

/**
 * Closed, versioned knowledge base for the in-app help guide.
 *
 * Every entry describes a surface that actually exists in the editor today.
 * The tests in `helpCorpus.test.ts` pin this corpus to `editorSections.ts`:
 * a section removed from the UI breaks the corpus tests, and a help topic
 * naming an unknown section fails them too. When the UI changes, this corpus
 * changes in the same work unit.
 *
 * Presentation rule (inherited from the generation panel): help text never
 * contains identifiers, technical names, or hashes. Tests enforce it.
 */
export type HelpTopic = {
  slug: string;
  title: string;
  summary: string;
  steps: readonly string[];
  keywords: readonly string[];
  /** Editor section this topic navigates to, when one applies. */
  section?: EditorSectionId;
};

export const HELP_TOPICS: readonly HelpTopic[] = [
  {
    slug: 'create-and-open-projects',
    title: 'Crear y abrir proyectos',
    summary:
      'Los proyectos contienen tus modelos. Cada proyecto conserva su diagrama, sus integrantes y su historial entre sesiones.',
    steps: [
      'En la página de inicio, usa la opción para crear un proyecto nuevo.',
      'Abre un proyecto de la lista para entrar al editor.',
      'Todo lo que dibujes se guarda en el servidor: cierra la pestaña y el diagrama volverá con la misma distribución.',
    ],
    keywords: ['project', 'create', 'open', 'save', 'home', 'list'],
  },
  {
    slug: 'draw-and-edit-classes',
    title: 'Dibujar y editar clases',
    summary:
      'El lienzo es el editor: crea clases, ponles nombre, agrega atributos y operaciones y muévelas libremente.',
    steps: [
      'Usa la barra de herramientas del lienzo para crear una clase; aparecerá lista para que le pongas nombre.',
      'Selecciona una clase para cambiarle el nombre, marcarla como abstracta o eliminarla.',
      'Arrastra una clase para moverla; la distribución se conserva tal como la dejes.',
      'Haz doble clic en una clase o selecciónala para abrir sus propiedades y agregar atributos y operaciones con visibilidad, tipo y multiplicidad.',
    ],
    keywords: ['class', 'canvas', 'attribute', 'operation', 'move', 'abstract', 'edit'],
  },
  {
    slug: 'context-menu-and-multiplicity',
    title: 'Menú contextual y multiplicidad',
    summary:
      'Haz clic derecho en una clase, una relación o el lienzo para ver sus acciones, al estilo de Enterprise Architect.',
    steps: [
      'Haz clic derecho en una clase para cambiarle el nombre, agregar elementos o eliminarla.',
      'Haz clic derecho en una relación para cambiar su tipo o multiplicidad.',
      'En el submenú de multiplicidad, elige entre: 1, 0..1, 1..*, 0..*.',
      'Elige qué extremo (A o B) quieres editar; se selecciona automáticamente según dónde hayas hecho clic en la línea.',
    ],
    keywords: ['context menu', 'right click', 'multiplicity', 'relationship', 'end'],
  },
  {
    slug: 'export-and-import-xmi',
    title: 'Intercambio XMI',
    summary:
      'Intercambia el modelo con Enterprise Architect: exporta el diagrama actual como archivo XMI o importa uno creado en EA.',
    steps: [
      'Abre la sección de intercambio XMI desde la barra de opciones del editor.',
      'Para exportar, usa la opción de exportación del panel y descarga el archivo generado junto con su informe de pérdidas.',
      'Para importar, elige un archivo XMI; podrás revisar el modelo propuesto antes de aplicarlo como un conjunto de operaciones.',
      'Las exportaciones se bloquean mientras otra persona colaboradora tenga el permiso de edición.',
    ],
    keywords: ['xmi', 'export', 'import', 'enterprise architect', 'ea', 'interchange', 'file'],
    section: 'xmi',
  },
  {
    slug: 'collaborate-in-real-time',
    title: 'Colaborar en tiempo real',
    summary:
      'Varias personas pueden estar en el mismo diagrama: cada una aparece con un color, un cursor y el elemento que está editando.',
    steps: [
      'Abre la sección de colaboración para ver quién está presente y qué color tiene.',
      'Cada cambio de otra persona aparece en tu lienzo sin necesidad de recargar.',
      'Mientras alguien edita, se bloquean las opciones de exportación y generación y se muestra su nombre; puedes recuperar el control cuando expire su permiso.',
      'Usa el chat del diagrama para conversar con las demás personas colaboradoras.',
    ],
    keywords: ['collaboration', 'presence', 'cursor', 'lease', 'real time', 'sync'],
    section: 'collaboration',
  },
  {
    slug: 'invite-and-manage-members',
    title: 'Invitar y administrar integrantes',
    summary:
      'Las personas propietarias del proyecto invitan a otras y asignan roles: quienes tienen rol de lector pueden leer, quienes editan pueden dibujar y las personas propietarias administran.',
    steps: [
      'Abre la sección de integrantes desde la barra de opciones del editor.',
      'Invita a una persona por su nombre de usuario; podrá aceptar la invitación desde su propia cuenta.',
      'Cambia un rol desde la lista de integrantes o revoca una invitación; al revocarla, se conserva el historial.',
    ],
    keywords: ['member', 'invite', 'role', 'owner', 'editor', 'viewer', 'revoke'],
    section: 'members',
  },
  {
    slug: 'review-the-diagram',
    title: 'Revisar el diagrama',
    summary:
      'Revisa los resultados de validación UML y la disponibilidad del backend antes de generarlo; la revisión es de solo lectura y las comprobaciones específicas del destino están en Generación.',
    steps: [
      'Abre Revisión desde la barra de opciones del editor y usa Ejecutar revisión para actualizar la validación UML y la disponibilidad del backend.',
      'Lee los errores y las advertencias agrupados y luego revisa los diagnósticos bloqueantes del perfil de generación mostrado.',
      'Quienes editan y las personas propietarias pueden elegir Explicar con IA o Proponer una corrección; se abre el chat del equipo con un borrador, y enviarlo o sugerirlo siempre es una acción aparte.',
      'El cambio propuesto aparece como una tarjeta en el chat del equipo para que todos lo vean; nada cambia hasta que una persona editora presiona Aceptar y aplicar.',
      'Elige Generación para ver comprobaciones específicas del destino, como el motor de base de datos.',
    ],
    keywords: ['review', 'validation', 'readiness', 'issues', 'diagram', 'uml'],
    section: 'review',
  },
  {
    slug: 'generate-a-backend',
    title: 'Generar un backend',
    summary:
      'Convierte el modelo actual en un backend de Spring Boot con PostgreSQL, listo para compilarse y ejecutarse.',
    steps: [
      'Abre la sección de generación desde la barra de opciones del editor.',
      'Crea o elige un destino para la generación.',
      'Inicia la ejecución y revisa su estado y sus diagnósticos; cada diagnóstico bloqueante indica qué función no es compatible.',
      'Cuando la ejecución termine sin bloqueos, descarga el ZIP; se compila sin conexión y se inicia con su propia base de datos.',
    ],
    keywords: ['generation', 'backend', 'spring', 'zip', 'destination', 'run', 'diagnostics'],
    section: 'generation',
  },
  {
    slug: 'deploy-the-generated-backend',
    title: 'Implementar el backend generado',
    summary:
      'Desde el editor puedes dejar el backend generado en ejecución, con su propia base de datos y accesible desde tu red.',
    steps: [
      'Primero genera un backend; la opción para implementarlo aparece cuando termina la ejecución.',
      'Presiona la opción de implementación y espera a que se complete la comprobación de estado.',
      'El panel muestra la dirección de red que pueden usar otros dispositivos.',
      'Cuando termines, detén la implementación desde el mismo panel.',
    ],
    keywords: ['deploy', 'backend', 'docker', 'health', 'network', 'stop'],
    section: 'generation',
  },
  {
    slug: 'assistant-with-natural-language',
    title: 'Asistente con lenguaje natural',
    summary:
      'Describe un cambio con tus propias palabras en el chat del equipo y el asistente publicará una propuesta que todos podrán ver antes de que se aplique.',
    steps: [
      'Presiona el botón del asistente o abre Colaboración: el asistente está en el chat del equipo, compartido con todas las personas colaboradoras del diagrama.',
      'Escribe el cambio que quieres, por ejemplo, crear una clase con un atributo, relacionarla con otra o hacer que una clase use otra mediante una dependencia «use», y presiona Sugerir cambio.',
      'El asistente publica una tarjeta con el cambio propuesto en el chat para que todos lo vean; una persona editora puede presionar Aceptar y aplicar o Rechazar.',
      'Los cambios aceptados pasan por el flujo normal de comandos, así que puedes deshacerlos como cualquier edición manual.',
    ],
    section: 'collaboration',
    keywords: ['assistant', 'ai', 'natural language', 'plan', 'preview', 'confirm', 'undo', 'team chat', 'stereotype', 'dependency'],
  },
  {
    slug: 'diagram-chat',
    title: 'Chat del diagrama',
    summary:
      'El historial del chat pertenece a cada sesión; la IA usa el UML actual y los recuerdos compartidos del diagrama, y solo busca en otros diagramas a los que todas las personas del chat tengan acceso.',
    steps: [
      'Abre el panel del chat dentro del editor.',
      'Crea o elige una sesión; cada sesión del mismo diagrama conserva su propio historial.',
      'Después de las solicitudes a la IA se extraen automáticamente conocimientos útiles del diagrama. También puedes usar Extraer recuerdos para hacerlo manualmente.',
      'Pide ayuda al asistente explícitamente; cada solicitud usa la versión más reciente del UML y los recuerdos activos de este diagrama. También puedes pedir en el chat que lea, guarde o revise un recuerdo.',
      'Pide explícitamente buscar en otros diagramas; las respuestas compartidas indican sus fuentes y solo consultan proyectos accesibles para todas las personas colaboradoras activas.',
      'Dicta con el botón del micrófono o adjunta una imagen para preguntar sobre ella; el envío siempre es explícito.',
      'Quienes editan pueden escribir un cambio y presionar Sugerir cambio: el asistente publica una tarjeta con el cambio previsto para todo el chat, y nada cambia hasta que una persona editora presiona Aceptar y aplicar o Rechazar.',
    ],
    keywords: ['chat', 'message', 'session', 'assistant', 'memory', 'memories', 'read', 'save', 'revise', 'other diagrams', 'shared access', 'uml', 'voice', 'dictation', 'image', 'suggest', 'suggestion', 'proposal', 'accept'],
  },
  {
    slug: 'versioning-and-branches',
    title: 'Historial, ramas y revisiones',
    summary:
      'Guarda cambios con mensajes claros, consulta quién cambió qué y cuándo, y revisa el trabajo por separado antes de incorporarlo a la rama principal.',
    steps: [
      'Abre Versionado desde las opciones del editor para ver la rama actual y su historial.',
      'Guarda un cambio con un mensaje claro; en el historial podrás ver quién lo hizo, qué cambió y cuándo. Abre un cambio con «Ver cambio» para ver las personas que figuran como autoras y, elemento por elemento, qué campos cambiaron, con su valor anterior y el nuevo. Con «Comparar confirmaciones» elige dos del historial para ver qué cambió de una a otra, quiénes participaron y las confirmaciones que hay entre ellas.',
      'Crea una rama para trabajar por separado y luego usa el selector para cambiar entre ramas.',
      'Abre una solicitud de revisión entre dos ramas y usa «Ver cambios» para ver los commits que aporta, quiénes participaron y las diferencias por campo. Si la rama de destino cambió desde que la abriste, los conflictos que se muestran son los actuales; fusiónala cuando el trabajo esté listo.',
      'La persona creadora del diagrama puede otorgar distintas capacidades, como ver, editar, guardar cambios, crear ramas, fusionar revisiones y administrar el acceso.',
    ],
    keywords: ['history', 'message', 'author', 'authors', 'changes', 'diff', 'difference', 'commit', 'branch', 'switch', 'review', 'merge', 'conflict', 'permissions'],
    section: 'vcs',
  },
];
