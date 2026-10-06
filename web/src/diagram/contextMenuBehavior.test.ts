import { describe, expect, it } from 'vitest';
import type { ClassifierKind } from './commands';
import type { RelationshipResource } from '../api/types';
import {
  CONTEXT_MENU_UNSUPPORTED_REASON,
  activateContextMenuItem,
  buildContextMenuItems,
  buildMultiplicitySubmenu,
  clampMenuPosition,
  contextMenuEndOrdinal,
  contextMenuStateFor,
  endOrdinalForLabelSide,
  isSeparator,
  multiplicityUpdateRequest,
  nearestRelationshipEndOrdinal,
  selectionFromTarget,
  type ContextMenuActions,
  type ContextMenuEntry,
  type ContextMenuItem,
  type ContextMenuRelationshipContext,
  type ContextMenuTarget,
} from './contextMenuBehavior';

describe('clampMenuPosition', () => {
  const viewport = { width: 1024, height: 768 };
  const menu = { width: 220, height: 320 };

  it('keeps an interior position unchanged', () => {
    expect(clampMenuPosition({ x: 200, y: 150 }, menu, viewport)).toEqual({ x: 200, y: 150 });
  });

  it('clamps x when the menu would overflow the right edge', () => {
    const clamped = clampMenuPosition({ x: 1000, y: 150 }, menu, viewport);
    expect(clamped.x).toBe(1024 - 220 - 8);
    expect(clamped.y).toBe(150);
  });

  it('clamps y when the menu would overflow the bottom edge', () => {
    const clamped = clampMenuPosition({ x: 200, y: 700 }, menu, viewport);
    expect(clamped.y).toBe(768 - 320 - 8);
  });

  it('clamps both axes for a cursor in the bottom-right corner', () => {
    const clamped = clampMenuPosition({ x: 1023, y: 767 }, menu, viewport);
    expect(clamped).toEqual({ x: 1024 - 220 - 8, y: 768 - 320 - 8 });
  });

  it('floors at the top-left corner instead of producing negative coordinates on a tiny viewport', () => {
    const clamped = clampMenuPosition({ x: 10, y: 10 }, { width: 220, height: 320 }, { width: 120, height: 100 });
    expect(clamped).toEqual({ x: 0, y: 0 });
  });

  it('treats a non-finite cursor coordinate as the clamped edge, never NaN', () => {
    const clamped = clampMenuPosition({ x: Number.NaN, y: Number.NaN }, menu, viewport);
    expect(Number.isFinite(clamped.x)).toBe(true);
    expect(Number.isFinite(clamped.y)).toBe(true);
  });
});

describe('contextMenuStateFor', () => {
  it('maps a node right-click to the classifier selection at the cursor position', () => {
    const state = contextMenuStateFor({ kind: 'classifier', classifierId: 'c1' }, { x: 40, y: 60 });
    expect(state.position).toEqual({ x: 40, y: 60 });
    expect(selectionFromTarget(state.target)).toEqual({ kind: 'classifier', classifierId: 'c1' });
  });

  it('maps an edge right-click to the relationship selection at the cursor position', () => {
    const state = contextMenuStateFor({ kind: 'relationship', relationshipId: 'r1' }, { x: 10, y: 20 });
    expect(state.position).toEqual({ x: 10, y: 20 });
    expect(selectionFromTarget(state.target)).toEqual({ kind: 'relationship', relationshipId: 'r1' });
  });

  it('maps a pane right-click to no selection', () => {
    const state = contextMenuStateFor({ kind: 'pane' }, { x: 10, y: 20 });
    expect(selectionFromTarget(state.target)).toBeNull();
  });
});

const classifierNames = new Map([
  ['c1', 'Desarrollo'],
  ['c2', 'Persona'],
]);

function relationshipFixture(overrides: Partial<RelationshipResource> = {}): RelationshipResource {
  return {
    id: 'r1',
    kind: 'ASSOCIATION',
    name: 'posee',
    constraints: [],
    ends: [
      { id: 'e1', classifierId: 'c1', role: 'dueño', multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 1 },
      { id: 'e2', classifierId: 'c2', role: null, multiplicity: 'ZERO_OR_MANY', navigability: 'NAVIGABLE', aggregationKind: 'NONE', ordinal: 2 },
    ],
    ...overrides,
  };
}

function relationshipContext(relationship: RelationshipResource, endOrdinal?: number): ContextMenuRelationshipContext {
  return {
    relationship,
    ...(endOrdinal === undefined ? {} : { endOrdinal }),
    classifierName: (classifierId: string) => classifierNames.get(classifierId),
  };
}

function multiplicityOptions(entries: ContextMenuItem[]): ContextMenuItem[] {
  return (entries[0]?.submenu ?? []).filter((entry) => entry.id !== 'multiplicity-end-header');
}

describe('buildContextMenuItems', () => {
  const actions: ContextMenuActions = {
    onEdit: () => undefined,
    onDelete: () => undefined,
    onAddClassifier: (_kind: ClassifierKind) => undefined,
    onSetMultiplicity: (_multiplicity: string) => undefined,
  };

  function findItem(items: ContextMenuEntry[], id: string): ContextMenuItem {
    const found = items.find((entry) => !isSeparator(entry) && entry.id === id);
    expect(found, `expected a "${id}" item`).toBeTruthy();
    return found as ContextMenuItem;
  }

  function menuItems(entries: ContextMenuEntry[]): ContextMenuItem[] {
    return entries.filter((entry): entry is ContextMenuItem => !isSeparator(entry));
  }

  it('offers Editar and Borrar as active items for a classifier', () => {
    const items = buildContextMenuItems({ kind: 'classifier', classifierId: 'c1' }, actions);
    const edit = findItem(items, 'edit');
    const remove = findItem(items, 'delete');
    expect(edit.disabled).toBeFalsy();
    expect(edit.onSelect).toBeTypeOf('function');
    expect(remove.disabled).toBeFalsy();
    expect(remove.onSelect).toBeTypeOf('function');
  });

  it('renders Duplicar for a classifier only as disabled with a reason (no duplicate command exists)', () => {
    const items = buildContextMenuItems({ kind: 'classifier', classifierId: 'c1' }, actions);
    const duplicate = findItem(items, 'duplicate');
    expect(duplicate.disabled).toBe(true);
    expect(duplicate.disabledReason).toBeTruthy();
  });

  it('renders Copiar como SVG and Copiar como Imagen disabled with a reason', () => {
    const items = buildContextMenuItems({ kind: 'classifier', classifierId: 'c1' }, actions);
    for (const id of ['copy-svg', 'copy-image']) {
      const item = findItem(items, id);
      expect(item.disabled).toBe(true);
      expect(item.disabledReason).toBeTruthy();
    }
  });

  it('renders the unsupported EA entries disabled with the model reason and no handler', () => {
    const items = buildContextMenuItems({ kind: 'classifier', classifierId: 'c1' }, actions);
    const unsupportedIds = [
      'cut', 'copy', 'bring-to-front', 'send-to-back', 'lock',
      'reference-points', 'connection-points', 'sticky-note', 'default-styles', 'edit-style',
    ];
    for (const id of unsupportedIds) {
      const item = findItem(items, id);
      expect(item.disabled).toBe(true);
      expect(item.disabledReason).toBe(CONTEXT_MENU_UNSUPPORTED_REASON);
      expect(item.onSelect).toBeUndefined();
    }
  });

  it('offers Editar and Borrar as active items for a relationship', () => {
    const items = buildContextMenuItems({ kind: 'relationship', relationshipId: 'r1' }, actions);
    expect(findItem(items, 'edit').disabled).toBeFalsy();
    expect(findItem(items, 'delete').disabled).toBeFalsy();
  });

  it('shows no Duplicar item for a relationship', () => {
    const items = buildContextMenuItems({ kind: 'relationship', relationshipId: 'r1' }, actions);
    expect(menuItems(items).some((item) => item.id === 'duplicate')).toBe(false);
  });

  it('fills the Multiplicidad submenu with the header plus the four mappable options', () => {
    const items = buildContextMenuItems(
      { kind: 'relationship', relationshipId: 'r1' },
      actions,
      relationshipContext(relationshipFixture()),
    );
    const multiplicity = findItem(items, 'multiplicity');
    expect(multiplicity.disabled).toBeFalsy();
    expect(multiplicity.submenu).toHaveLength(5);
  });

  it('defaults the submenu to the source end (ordinal 1) when the opener does not preselect one', () => {
    const items = buildContextMenuItems(
      { kind: 'relationship', relationshipId: 'r1' },
      actions,
      relationshipContext(relationshipFixture()),
    );
    const options = multiplicityOptions([findItem(items, 'multiplicity')]);
    expect(options.filter((option) => option.checked).map((option) => option.label)).toEqual(['1']);
  });

  it('preselects end B in the submenu when the opener threads ordinal 2 through target and context', () => {
    const items = buildContextMenuItems(
      { kind: 'relationship', relationshipId: 'r1', endOrdinal: 2 },
      actions,
      relationshipContext(relationshipFixture(), 2),
    );
    const multiplicity = findItem(items, 'multiplicity');
    const [header] = multiplicity.submenu ?? [];
    expect(header?.label).toBe('Extremo B — Persona');
    expect(multiplicityOptions([multiplicity]).filter((option) => option.checked).map((option) => option.label))
      .toEqual(['0..*']);
  });

  it('offers classifier creation actions on the pane', () => {
    const items = menuItems(buildContextMenuItems({ kind: 'pane' }, actions));
    const ids = items.map((item) => item.id);
    expect(ids).toEqual(['add-class', 'add-abstract-class', 'add-interface', 'add-package']);
    for (const item of items) {
      expect(item.disabled).toBeFalsy();
      expect(item.onSelect).toBeTypeOf('function');
    }
  });
});

describe('buildMultiplicitySubmenu', () => {
  const onSetMultiplicity = (_multiplicity: string) => undefined;

  it('offers exactly the four mappable multiplicities with their notation labels, in order', () => {
    const submenu = buildMultiplicitySubmenu(relationshipFixture(), 1, () => undefined, onSetMultiplicity);
    const options = submenu.filter((entry) => entry.id !== 'multiplicity-end-header');
    expect(options.map((option) => option.label)).toEqual(['1', '0..1', '1..*', '0..*']);
    for (const option of options) {
      expect(option.disabled).toBeFalsy();
      expect(option.onSelect).toBeTypeOf('function');
    }
  });

  it('names the edited end in a non-interactive header using its role', () => {
    const [header] = buildMultiplicitySubmenu(relationshipFixture(), 1, () => undefined, onSetMultiplicity);
    expect(header?.heading).toBe(true);
    expect(header?.disabled).toBe(true);
    expect(header?.label).toBe('Extremo A — dueño');
  });

  it('falls back to the classifier name when the edited end has no role', () => {
    const [header] = buildMultiplicitySubmenu(relationshipFixture(), 2, (id) => (id === 'c2' ? 'Persona' : undefined), onSetMultiplicity);
    expect(header?.heading).toBe(true);
    expect(header?.label).toBe('Extremo B — Persona');
  });

  it('marks the currently set multiplicity of the edited end and only that one', () => {
    const options = multiplicityOptions([
      { id: 'wrapper', label: '', submenu: buildMultiplicitySubmenu(relationshipFixture(), 2, () => undefined, onSetMultiplicity) },
    ]);
    const marked = options.filter((option) => option.checked);
    expect(marked.map((option) => option.label)).toEqual(['0..*']);
  });

  it('marks the target end instead when ordinal 2 is passed for T3 preselection', () => {
    const options = multiplicityOptions([
      { id: 'wrapper', label: '', submenu: buildMultiplicitySubmenu(relationshipFixture(), 2, () => undefined, onSetMultiplicity) },
    ]);
    expect(options.filter((option) => option.checked).map((option) => option.label)).toEqual(['0..*']);
  });

  it('dispatches the chosen multiplicity value through the callback', () => {
    const seen: string[] = [];
    const submenu = buildMultiplicitySubmenu(relationshipFixture(), 1, () => undefined, (multiplicity) => seen.push(multiplicity));
    const option = submenu.find((entry) => entry.label === '1..*');
    option?.onSelect?.();
    expect(seen).toEqual(['ONE_OR_MANY']);
  });
});

describe('multiplicityUpdateRequest', () => {
  function requestFor(relationship: RelationshipResource, endOrdinal: number, multiplicity: string) {
    const request = multiplicityUpdateRequest(relationship, endOrdinal, multiplicity);
    expect(request, 'a two-ended relationship always dispatches').not.toBeNull();
    return request as NonNullable<ReturnType<typeof multiplicityUpdateRequest>>;
  }

  it('sends a relationship.update targeting the relationship with a full positional payload', () => {
    const request = requestFor(relationshipFixture(), 1, 'ZERO_OR_ONE');
    expect(request.type).toBe('relationship.update');
    expect(request.targets).toEqual([{ scope: 'relationship', id: 'r1' }]);
    expect(Object.keys(request.payload ?? {}).sort()).toEqual(['kind', 'name', 'source', 'target']);
  });

  it('replaces the chosen end multiplicity and preserves every other field byte-identically', () => {
    const relationship = relationshipFixture();
    const payload = requestFor(relationship, 1, 'ZERO_OR_ONE').payload ?? {};
    expect(payload.name).toBe('posee');
    expect(payload.kind).toBe('ASSOCIATION');
    expect(JSON.stringify(payload.source)).toBe(JSON.stringify({ role: 'dueño', multiplicity: 'ZERO_OR_ONE', navigability: 'UNSPECIFIED' }));
    expect(JSON.stringify(payload.target)).toBe(JSON.stringify({ role: null, multiplicity: 'ZERO_OR_MANY', navigability: 'NAVIGABLE' }));
  });

  it('replaces the target end when ordinal 2 is given, preserving the source end', () => {
    const relationship = relationshipFixture();
    const payload = requestFor(relationship, 2, '1..*').payload ?? {};
    expect(JSON.stringify(payload.source)).toBe(JSON.stringify({ role: 'dueño', multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED' }));
    expect(JSON.stringify(payload.target)).toBe(JSON.stringify({ role: null, multiplicity: '1..*', navigability: 'NAVIGABLE' }));
  });

  it('preserves a null relationship name exactly as the relationship holds it', () => {
    const relationship = relationshipFixture({ name: null });
    const payload = requestFor(relationship, 1, '0..*').payload ?? {};
    expect(payload.name).toBeNull();
  });
});

describe('contextMenuEndOrdinal', () => {
  it('reads the end preselected by the opener from a relationship target', () => {
    const target: ContextMenuTarget = { kind: 'relationship', relationshipId: 'r1', endOrdinal: 2 };
    expect(contextMenuEndOrdinal(target)).toBe(2);
  });

  it('falls back to the default source end when the opener could not determine one', () => {
    expect(contextMenuEndOrdinal({ kind: 'relationship', relationshipId: 'r1' })).toBe(1);
  });

  it('returns the default for non-relationship targets, which carry no ends', () => {
    expect(contextMenuEndOrdinal({ kind: 'classifier', classifierId: 'c1' })).toBe(1);
    expect(contextMenuEndOrdinal({ kind: 'pane' })).toBe(1);
  });
});

describe('nearestRelationshipEndOrdinal', () => {
  const source = { x: 0, y: 0 };
  const target = { x: 100, y: 0 };

  it('picks the source end when the cursor is nearer to the source anchor', () => {
    expect(nearestRelationshipEndOrdinal({ x: 20, y: 10 }, source, target)).toBe(1);
  });

  it('picks the target end when the cursor is nearer to the target anchor', () => {
    expect(nearestRelationshipEndOrdinal({ x: 85, y: 5 }, source, target)).toBe(2);
  });

  it('resolves off-line cursors by planar proximity alone', () => {
    expect(nearestRelationshipEndOrdinal({ x: 0, y: 40 }, source, target)).toBe(1);
    expect(nearestRelationshipEndOrdinal({ x: 100, y: 40 }, source, target)).toBe(2);
  });

  it('defaults to the source end when both anchors are equidistant', () => {
    expect(nearestRelationshipEndOrdinal({ x: 50, y: 0 }, source, target)).toBe(1);
  });

  it('defaults to the source end when both anchors collapse to the same point', () => {
    expect(nearestRelationshipEndOrdinal({ x: 50, y: 7 }, source, source)).toBe(1);
  });
});

describe('endOrdinalForLabelSide', () => {
  it('maps the source end label to ordinal 1 (end A)', () => {
    expect(endOrdinalForLabelSide('source')).toBe(1);
  });

  it('maps the target end label to ordinal 2 (end B)', () => {
    expect(endOrdinalForLabelSide('target')).toBe(2);
  });
});

describe('activateContextMenuItem', () => {
  it('produces a dispatch activation for a submenu option, the path that closes the menu', () => {
    const onSetMultiplicity = () => undefined;
    const submenu = buildMultiplicitySubmenu(relationshipFixture(), 1, () => undefined, onSetMultiplicity);
    const option = submenu.find((entry) => entry.label === '0..1');
    const activation = activateContextMenuItem(option as ContextMenuItem, null);
    expect(activation).toEqual({ kind: 'dispatch', onSelect: option?.onSelect });
  });

  it('toggles a submenu parent open instead of dispatching', () => {
    const parent: ContextMenuItem = { id: 'multiplicity', label: 'Multiplicidad', submenu: [] };
    expect(activateContextMenuItem(parent, null)).toEqual({ kind: 'submenu', openSubmenuId: 'multiplicity' });
    expect(activateContextMenuItem(parent, 'multiplicity')).toEqual({ kind: 'submenu', openSubmenuId: null });
  });

  it('ignores separators, disabled items, and entries without a handler', () => {
    expect(activateContextMenuItem({ separator: true }, null)).toEqual({ kind: 'none' });
    expect(activateContextMenuItem({ id: 'copy', label: 'Copiar', disabled: true }, null)).toEqual({ kind: 'none' });
    expect(activateContextMenuItem({ id: 'header', label: 'x', disabled: true, heading: true }, null)).toEqual({ kind: 'none' });
  });
});
