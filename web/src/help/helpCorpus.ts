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
    title: 'Create and open projects',
    summary:
      'Projects hold your models. Each project keeps its diagram, its members and its history across sessions.',
    steps: [
      'From the home page, use the create-project action to start a new project.',
      'Open a project from the list to enter the editor.',
      'Everything you draw is saved server-side: close the tab and the diagram comes back with the same layout.',
    ],
    keywords: ['project', 'create', 'open', 'save', 'home', 'list'],
  },
  {
    slug: 'draw-and-edit-classes',
    title: 'Draw and edit classes',
    summary:
      'The canvas is the editor: create classes, name them, add attributes and operations, and move them freely.',
    steps: [
      'Use the canvas toolbar to create a class; it appears on the canvas ready to name.',
      'Select a class to edit its name, mark it abstract, or delete it.',
      'Drag a class to move it; the layout persists exactly where you leave it.',
      'Double-click or select a class to open its properties and add attributes and operations with visibility, type and multiplicity.',
    ],
    keywords: ['class', 'canvas', 'attribute', 'operation', 'move', 'abstract', 'edit'],
  },
  {
    slug: 'context-menu-and-multiplicity',
    title: 'Context menu and multiplicity',
    summary:
      'Right-click a class, a relationship or the canvas to reach the actions of that element, Enterprise Architect style.',
    steps: [
      'Right-click a class to rename it, add members or delete it.',
      'Right-click a relationship to change its type or its multiplicity.',
      'In the multiplicity submenu choose one of: 1, 0..1, 1..*, 0..*.',
      'Pick which end (A or B) you are editing; the end is preselected from where you clicked on the line.',
    ],
    keywords: ['context menu', 'right click', 'multiplicity', 'relationship', 'end'],
  },
  {
    slug: 'export-and-import-xmi',
    title: 'XMI interchange',
    summary:
      'Exchange the model with Enterprise Architect: export the current diagram as an XMI file, or import one made in EA.',
    steps: [
      'Open the XMI interchange section from the editor option bar.',
      'To export, use the export action in the panel and download the generated file with its loss report.',
      'To import, choose an XMI file; the model is proposed and applied as a batch of operations you can review.',
      'Exports are blocked while another collaborator holds the editing lease.',
    ],
    keywords: ['xmi', 'export', 'import', 'enterprise architect', 'ea', 'interchange', 'file'],
    section: 'xmi',
  },
  {
    slug: 'collaborate-in-real-time',
    title: 'Collaborate in real time',
    summary:
      'Several people can be in the same diagram: each one is visible with a color, a cursor and the element they are touching.',
    steps: [
      'Open the collaboration section to see who is present and their colors.',
      'Every change from another person appears on your canvas without reloading.',
      'While someone edits, the export and generation actions are blocked with their name shown; you can claim control when their lease expires.',
      'Use the diagram chat to talk with the collaborators of this diagram.',
    ],
    keywords: ['collaboration', 'presence', 'cursor', 'lease', 'real time', 'sync'],
    section: 'collaboration',
  },
  {
    slug: 'invite-and-manage-members',
    title: 'Invite and manage members',
    summary:
      'Project owners invite people and assign roles: viewers read, editors draw, owners administer.',
    steps: [
      'Open the members section from the editor option bar.',
      'Invite a person by name; they accept the invitation from their own account.',
      'Change a role from the member list, or revoke an invitation; revoking keeps the history.',
    ],
    keywords: ['member', 'invite', 'role', 'owner', 'editor', 'viewer', 'revoke'],
    section: 'members',
  },
  {
    slug: 'review-the-diagram',
    title: 'Review the diagram',
    summary:
      'Check UML validation findings and backend readiness before generating; review is read-only and destination-specific checks stay in Generation.',
    steps: [
      'Open Review from the editor option bar and use Run review to refresh UML validation and backend readiness.',
      'Read the grouped errors and warnings, then check the blocking diagnostics for the displayed generation profile.',
      'Editors and owners can choose Explain with AI or Propose a fix; the assistant opens with a draft, and Consultar is always a separate action.',
      'Review any proposed plan before choosing Aplicar cambios; nothing is sent or changed automatically.',
      'Choose Generation for destination-specific checks such as the database engine.',
    ],
    keywords: ['review', 'validation', 'readiness', 'issues', 'diagram', 'uml'],
    section: 'review',
  },
  {
    slug: 'generate-a-backend',
    title: 'Generate a backend',
    summary:
      'Turn the current model into a Spring Boot backend with PostgreSQL, ready to compile and run.',
    steps: [
      'Open the generation section from the editor option bar.',
      'Create or choose a destination for the generation.',
      'Start the run and read the state and the diagnostics; a blocking diagnostic names the unsupported feature.',
      'When the run completes without blockers, download the ZIP; it compiles offline and starts with its own database.',
    ],
    keywords: ['generation', 'backend', 'spring', 'zip', 'destination', 'run', 'diagnostics'],
    section: 'generation',
  },
  {
    slug: 'deploy-the-generated-backend',
    title: 'Deploy the generated backend',
    summary:
      'From the editor you can leave the generated backend running, with its own database, reachable from your network.',
    steps: [
      'Generate a backend first; the deploy action appears once the run completed.',
      'Press the deploy action and wait for the health check to pass.',
      'The panel shows the network address other devices can use.',
      'Stop the deployment from the same panel when you are done.',
    ],
    keywords: ['deploy', 'backend', 'docker', 'health', 'network', 'stop'],
    section: 'generation',
  },
  {
    slug: 'assistant-with-natural-language',
    title: 'Assistant with natural language',
    summary:
      'Describe a change in plain language and the assistant proposes a plan you review before anything is applied.',
    steps: [
      'Open the assistant panel from the editor.',
      'Write the change you want, for example to create a class with an attribute and relate it to another.',
      'Review the proposed plan; you can accept it, reject it or ask for clarification.',
      'Accepted changes go through the normal command pipeline, so you can undo them like manual edits.',
    ],
    keywords: ['assistant', 'ai', 'natural language', 'plan', 'preview', 'confirm', 'undo'],
  },
  {
    slug: 'diagram-chat',
    title: 'Diagram chat',
    summary:
      'Chat history stays session-scoped; the AI uses current UML and shared diagram memory, and searches other diagrams only within the chat audience’s shared access.',
    steps: [
      'Open the chat panel inside the editor.',
      'Create or select a session; sessions of the same diagram keep their own history.',
      'Useful diagram knowledge is extracted automatically after AI requests. Extract memories remains available for a manual run.',
      'Ask the assistant explicitly; each request uses the latest UML and active memories for this diagram. Ask naturally in chat to read, save, or revise a memory.',
      'Ask explicitly to search other diagrams; shared answers name their sources and use only projects every active collaborator can access.',
      'Dictate with the microphone button, or attach an image to ask about it; sending is always explicit.',
    ],
    keywords: ['chat', 'message', 'session', 'assistant', 'memory', 'memories', 'read', 'save', 'revise', 'other diagrams', 'shared access', 'uml', 'voice', 'dictation', 'image'],
  },
];
