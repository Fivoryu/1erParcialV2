/**
 * Pure model for the canvas context menu (right-click), mirroring the
 * Modal/modalBehavior split: no React and no DOM access, so the menu content
 * and the anchor math stay unit-testable without a DOM environment.
 */
import type { ClassifierKind, RelationshipKind } from './commands';
import { buildRelationshipUpdateRequest, type ModelCommandRequest } from './commands';
import type { RelationshipEndResource, RelationshipResource } from '../api/types';
import { formatMultiplicity } from './uml-format';
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
  /** Rendered with a check mark: the entry reflects the currently held value. */
  checked?: boolean;
  /** Rendered as a non-interactive heading (used for submenu captions). */
  heading?: boolean;
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
 * For relationships, `endOrdinal` is the end the opener preselected (1 = end A
 * near the source anchor or its label, 2 = end B); absent when not determinable.
 */
export type ContextMenuTarget =
  | { kind: 'classifier'; classifierId: string }
  | { kind: 'relationship'; relationshipId: string; endOrdinal?: number }
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
  /** Dispatches a full `relationship.update` setting the edited end's multiplicity. */
  onSetMultiplicity: (multiplicity: string) => void;
}

/**
 * Everything the relationship submenu needs beyond the target id. `endOrdinal`
 * is the 1-based ordinal of the end being edited, preselected by the opener
 * (label right-click or nearest anchor on a line right-click); it falls back to
 * the source end when the opener could not determine one.
 */
export interface ContextMenuRelationshipContext {
  relationship: RelationshipResource;
  endOrdinal?: number;
  classifierName: (classifierId: string) => string | undefined;
}

/** The mappable multiplicities offered by the submenu, in menu order. */
export const CONTEXT_MENU_MULTIPLICITY_OPTIONS = ['EXACTLY_ONE', 'ZERO_OR_ONE', 'ONE_OR_MANY', 'ZERO_OR_MANY'] as const;

/** The end edited when the opener does not preselect one: the source end. */
export const CONTEXT_MENU_DEFAULT_END_ORDINAL = 1;

/**
 * Which relationship end a rendered label stands for: the source end label is
 * end A (ordinal 1) and the target end label is end B (ordinal 2).
 */
export function endOrdinalForLabelSide(side: 'source' | 'target'): 1 | 2 {
  return side === 'source' ? 1 : 2;
}

/**
 * The end the Multiplicidad submenu edits for a right-click target: the
 * preselected end when the opener determined one, otherwise the default source
 * end. Non-relationship targets carry no ends and always get the default.
 */
export function contextMenuEndOrdinal(target: ContextMenuTarget): number {
  return target.kind === 'relationship'
    ? target.endOrdinal ?? CONTEXT_MENU_DEFAULT_END_ORDINAL
    : CONTEXT_MENU_DEFAULT_END_ORDINAL;
}

/**
 * Nearest-end preselection for a right click on the relationship line. The
 * approximation is a plain planar distance test against each endpoint anchor
 * (squared, so no sqrt): it asks which end of the relationship the cursor is
 * closer to and ignores the polyline shape between the anchors, which is
 * enough to pick the end to edit. Ties — including both anchors collapsing to
 * the same point — keep the source end, mirroring the default ordinal.
 */
export function nearestRelationshipEndOrdinal(
  cursor: { x: number; y: number },
  source: { x: number; y: number },
  target: { x: number; y: number },
): 1 | 2 {
  const squaredDistance = (point: { x: number; y: number }): number =>
    (point.x - cursor.x) ** 2 + (point.y - cursor.y) ** 2;
  return squaredDistance(target) < squaredDistance(source) ? 2 : 1;
}

/** What activating a menu entry does; dispatch activations close the menu. */
export type ContextMenuItemActivation =
  | { kind: 'none' }
  | { kind: 'submenu'; openSubmenuId: string | null }
  | { kind: 'dispatch'; onSelect: () => void };

/**
 * Pure decision behind ContextMenu's click handling: what a click on an entry
 * means before any DOM effect. A dispatchable entry (no submenu of its own)
 * always yields a dispatch, and the menu closes after dispatching — choosing a
 * multiplicity option therefore closes the menu through this same path.
 */
export function activateContextMenuItem(entry: ContextMenuEntry, openSubmenuId: string | null): ContextMenuItemActivation {
  if (isSeparator(entry) || entry.disabled) {
    return { kind: 'none' };
  }
  if (entry.submenu) {
    return { kind: 'submenu', openSubmenuId: openSubmenuId === entry.id ? null : entry.id };
  }
  if (!entry.onSelect) {
    return { kind: 'none' };
  }
  return { kind: 'dispatch', onSelect: entry.onSelect };
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
 * The relationship end being edited, resolved by 1-based ordinal and falling
 * back to the first end, mirroring how PropertiesPanel reads ends[0]/ends[1].
 */
function editedEnd(relationship: RelationshipResource, endOrdinal: number): RelationshipEndResource | undefined {
  return relationship.ends.find((end) => end.ordinal === endOrdinal) ?? relationship.ends[0];
}

function endSideLabel(endOrdinal: number): string {
  return endOrdinal === 1 ? 'Extremo A' : 'Extremo B';
}

/** Header caption for the submenu: which end is being edited, by role or classifier. */
export function multiplicityEndHeaderLabel(
  relationship: RelationshipResource,
  endOrdinal: number,
  classifierName: (classifierId: string) => string | undefined,
): string {
  const end = editedEnd(relationship, endOrdinal);
  const name = end ? end.role?.trim() || classifierName(end.classifierId) || '' : '';
  return `${endSideLabel(endOrdinal)} — ${name}`;
}

/**
 * The Multiplicidad submenu: a header naming the edited end plus the four
 * mappable options, with the end's currently held multiplicity marked.
 */
export function buildMultiplicitySubmenu(
  relationship: RelationshipResource,
  endOrdinal: number,
  classifierName: (classifierId: string) => string | undefined,
  onSetMultiplicity: (multiplicity: string) => void,
): ContextMenuItem[] {
  const current = editedEnd(relationship, endOrdinal)?.multiplicity;
  const header: ContextMenuItem = {
    id: 'multiplicity-end-header',
    label: multiplicityEndHeaderLabel(relationship, endOrdinal, classifierName),
    disabled: true,
    heading: true,
  };
  const options = CONTEXT_MENU_MULTIPLICITY_OPTIONS.map((multiplicity): ContextMenuItem => ({
    id: `multiplicity-${multiplicity.toLowerCase()}`,
    label: formatMultiplicity(multiplicity),
    checked: current === multiplicity,
    onSelect: () => onSetMultiplicity(multiplicity),
  }));
  return [header, ...options];
}

/**
 * The exact request a multiplicity choice dispatches: the same full positional
 * payload PropertiesPanel.save() sends, with only the edited end's multiplicity
 * replaced and every other field preserved as the relationship holds it.
 * Returns null for a relationship that does not carry both ends, which the
 * caller must treat as "nothing to dispatch" rather than send a partial payload.
 */
export function multiplicityUpdateRequest(
  relationship: RelationshipResource,
  endOrdinal: number,
  multiplicity: string,
): ModelCommandRequest | null {
  const end = (end: RelationshipEndResource) => {
    const edited = end.ordinal === endOrdinal;
    // Bounds travel only for an end that is, or just stopped being, a RANGE: the menu offers the
    // fixed kinds, so the edited end sends explicit nulls, and every other payload stays unchanged.
    const bounds = end.multiplicity !== 'RANGE' ? {}
      : edited ? { lowerBound: null, upperBound: null }
        : { lowerBound: end.lowerBound ?? null, upperBound: end.upperBound ?? null };
    return {
      role: end.role,
      multiplicity: edited ? multiplicity : end.multiplicity,
      ...bounds,
      navigability: end.navigability,
    };
  };
  const sourceEnd = relationship.ends.find((candidate) => candidate.ordinal === 1) ?? relationship.ends[0];
  const targetEnd = relationship.ends.find((candidate) => candidate.ordinal === 2) ?? relationship.ends[1];
  if (!sourceEnd || !targetEnd) {
    return null;
  }
  return buildRelationshipUpdateRequest(relationship, {
    name: relationship.name,
    kind: relationship.kind as RelationshipKind,
    source: end(sourceEnd),
    target: end(targetEnd),
  });
}

/**
 * Builds the menu for a right-click target.
 *
 * Active items are only those backed by an existing command or callback
 * (Editar → selection, Borrar → delete commands, classifier creation on the
 * pane, Multiplicidad → relationship.update). Duplicar stays disabled:
 * `classifier.create` exists but its payload produces a new empty classifier
 * with an auto-generated name (members are not copied and there is no composite
 * duplicate command), so enabling it would misrepresent the operation. Copiar
 * como SVG/Imagen stay disabled: the canvas mixes HTML nodes and SVG edges and
 * has no reliable client-side serialization. A relationship right-click fills
 * the Multiplicidad submenu from the relationship's current ends; without the
 * relationship context it stays a disabled placeholder.
 */
export function buildContextMenuItems(
  target: ContextMenuTarget,
  actions: ContextMenuActions,
  relationshipContext?: ContextMenuRelationshipContext,
): ContextMenuEntry[] {
  if (target.kind === 'pane') {
    return ([
      ['add-class', 'Nueva clase', 'CLASS'],
      ['add-abstract-class', 'Nueva clase abstracta', 'ABSTRACT_CLASS'],
      ['add-interface', 'Nueva interfaz', 'INTERFACE'],
      ['add-enumeration', 'Nueva enumeración', 'ENUMERATION'],
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
    ...(target.kind === 'relationship'
      ? [relationshipContext
        ? {
          id: 'multiplicity',
          label: 'Multiplicidad',
          submenu: buildMultiplicitySubmenu(
            relationshipContext.relationship,
            relationshipContext.endOrdinal ?? CONTEXT_MENU_DEFAULT_END_ORDINAL,
            relationshipContext.classifierName,
            actions.onSetMultiplicity,
          ),
        } as const satisfies ContextMenuItem
        : {
          id: 'multiplicity',
          label: 'Multiplicidad',
          disabled: true,
          disabledReason: 'La relación ya no existe en el modelo',
          submenu: [],
        } as const satisfies ContextMenuItem]
      : []),
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
