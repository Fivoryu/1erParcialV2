/**
 * Pure model for the canvas context menu (right-click), mirroring the
 * Modal/modalBehavior split: no React and no DOM access, so the menu content
 * and the anchor math stay unit-testable without a DOM environment.
 */
import type { ClassifierKind } from './commands';
import type { CanvasSelection } from './selection';

/** One entry of the menu. `submenu` turns the entry into a nested menu parent. */
export interface ContextMenuItem {
  id: string;
  label: string;
  disabled?: boolean;
  /** Shown as a tooltip for disabled items; must explain why, never lie. */
  disabledReason?: string;
  onSelect?: () => void;
  submenu?: ContextMenuItem[];
}

/** Visual divider between item groups; carries no behaviour. */
export interface ContextMenuSeparator {
  separator: true;
}

export type ContextMenuEntry = ContextMenuItem | ContextMenuSeparator;

export function isSeparator(entry: ContextMenuEntry): entry is ContextMenuSeparator {
  return (entry as ContextMenuSeparator).separator === true;
}

/**
 * What was under the right click: the same selection shape the canvas already
 * uses, plus the empty pane. Attribute/operation/constraint selections cannot
 * be produced by right-clicking the canvas, so they are not menu targets.
 */
export type ContextMenuTarget =
  | { kind: 'classifier'; classifierId: string }
  | { kind: 'relationship'; relationshipId: string }
  | { kind: 'pane' };

/** Menu open state held by CanvasFrame. */
export interface ContextMenuState {
  target: ContextMenuTarget;
  position: { x: number; y: number };
}

/** Callbacks the menu can trigger; CanvasFrame supplies them from its props. */
export interface ContextMenuActions {
  onEdit: () => void;
  onDelete: () => void;
  onAddClassifier: (kind: ClassifierKind) => void;
}

/** Shared reason for every EA entry the model has no command for. */
export const CONTEXT_MENU_UNSUPPORTED_REASON = 'No soportado por el modelo';

/** Minimum distance from every viewport edge, in px (mirrors floatingPosition). */
export const CONTEXT_MENU_EDGE_INSET = 8;

/**
 * Size assumed before the real menu has been measured in the DOM; large enough
 * that the first paint errs towards clamping rather than overflowing.
 */
export const CONTEXT_MENU_FALLBACK_SIZE = { width: 232, height: 380 };

export type MenuSize = { width: number; height: number };
export type Viewport = { width: number; height: number };

/**
 * Keeps the whole menu inside the viewport, never closer to an edge than the
 * inset. Same degenerate case as floatingPosition.clampToViewport: a viewport
 * smaller than the menu floors at 0 (top-left corner) instead of producing
 * negative or NaN coordinates, so the menu always stays at least partially on
 * screen.
 */
export function clampMenuPosition(position: { x: number; y: number }, menuSize: MenuSize, viewport: Viewport): { x: number; y: number } {
  const maxX = viewport.width - menuSize.width - CONTEXT_MENU_EDGE_INSET;
  const maxY = viewport.height - menuSize.height - CONTEXT_MENU_EDGE_INSET;
  const clamp = (value: number, max: number): number => {
    const finite = Number.isFinite(value) ? value : max;
    const floor = Math.max(max, 0);
    return Math.min(Math.max(finite, CONTEXT_MENU_EDGE_INSET <= floor ? CONTEXT_MENU_EDGE_INSET : 0), floor);
  };
  return { x: clamp(position.x, maxX), y: clamp(position.y, maxY) };
}

/** Maps a right-click target to the existing canvas selection shape. */
export function selectionFromTarget(target: ContextMenuTarget): CanvasSelection | null {
  if (target.kind === 'classifier') {
    return { kind: 'classifier', classifierId: target.classifierId };
  }
  if (target.kind === 'relationship') {
    return { kind: 'relationship', relationshipId: target.relationshipId };
  }
  return null;
}

export function contextMenuStateFor(target: ContextMenuTarget, position: { x: number; y: number }): ContextMenuState {
  return { target, position };
}

function unsupportedItem(id: string, label: string): ContextMenuItem {
  return { id, label, disabled: true, disabledReason: CONTEXT_MENU_UNSUPPORTED_REASON };
}

/**
 * Entries every selection kind shares: the EA-style entries the model has no
 * command for, rendered disabled so the menu looks complete without lying.
 */
function unsupportedEntries(): ContextMenuEntry[] {
  return [{ separator: true },
    unsupportedItem('cut', 'Cortar'),
    unsupportedItem('copy', 'Copiar'),
    unsupportedItem('bring-to-front', 'Traer al frente'),
    unsupportedItem('send-to-back', 'Enviar al fondo'),
    unsupportedItem('lock', 'Bloquear'),
    unsupportedItem('reference-points', 'Puntos de referencia'),
    unsupportedItem('connection-points', 'Puntos de conexión'),
    unsupportedItem('sticky-note', 'Bloc de notas'),
    unsupportedItem('default-styles', 'Estilos predeterminados'),
    unsupportedItem('edit-style', 'Editar estilo')];
}

/**
 * Builds the menu for a right-click target.
 *
 * Active items are only those backed by an existing command or callback
 * (Editar → selection, Borrar → delete commands, classifier creation on the
 * pane). Duplicar stays disabled: `classifier.create` exists but its payload
 * produces a new empty classifier with an auto-generated name (members are not
 * copied and there is no composite duplicate command), so enabling it would
 * misrepresent the operation. Copiar como SVG/Imagen stay disabled: the canvas
 * mixes HTML nodes and SVG edges and has no reliable client-side
 * serialization. Multiplicidad is a present-but-empty placeholder until T2.
 */
export function buildContextMenuItems(target: ContextMenuTarget, actions: ContextMenuActions): ContextMenuEntry[] {
  if (target.kind === 'pane') {
    return ([
      ['add-class', 'Nueva clase', 'CLASS'],
      ['add-abstract-class', 'Nueva clase abstracta', 'ABSTRACT_CLASS'],
      ['add-interface', 'Nueva interfaz', 'INTERFACE'],
      ['add-package', 'Nuevo paquete', 'PACKAGE'],
    ] as const).map(([id, label, kind]) => ({
      id,
      label,
      onSelect: () => actions.onAddClassifier(kind),
    }));
  }

  const entries: ContextMenuEntry[] = [
    { id: 'edit', label: 'Editar', onSelect: actions.onEdit },
    ...(target.kind === 'classifier' ? [{
      id: 'duplicate',
      label: 'Duplicar',
      disabled: true,
      disabledReason: 'Copiar un clasificador requiere componer varios comandos; no existe un comando de duplicado en el modelo',
    } as const satisfies ContextMenuItem] : []),
    { id: 'delete', label: 'Borrar', onSelect: actions.onDelete },
    ...(target.kind === 'relationship' ? [{
      id: 'multiplicity',
      label: 'Multiplicidad',
      disabled: true,
      disabledReason: 'Se habilita en el siguiente incremento',
      submenu: [],
    } as const satisfies ContextMenuItem] : []),
    { separator: true },
    {
      id: 'copy-svg',
      label: 'Copiar como SVG',
      disabled: true,
      disabledReason: 'El lienzo no se puede serializar de forma fiable en el cliente',
    },
    {
      id: 'copy-image',
      label: 'Copiar como Imagen',
      disabled: true,
      disabledReason: 'El lienzo no se puede serializar de forma fiable en el cliente',
    },
  ];
  return [...entries, ...unsupportedEntries()];
}
