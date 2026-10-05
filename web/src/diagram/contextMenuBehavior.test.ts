import { describe, expect, it } from 'vitest';
import type { ClassifierKind } from './commands';
import {
  CONTEXT_MENU_UNSUPPORTED_REASON,
  buildContextMenuItems,
  clampMenuPosition,
  contextMenuStateFor,
  isSeparator,
  selectionFromTarget,
  type ContextMenuActions,
  type ContextMenuEntry,
  type ContextMenuItem,
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

describe('buildContextMenuItems', () => {
  const actions: ContextMenuActions = {
    onEdit: () => undefined,
    onDelete: () => undefined,
    onAddClassifier: (_kind: ClassifierKind) => undefined,
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

  it('keeps a present-but-empty Multiplicidad submenu placeholder for T2', () => {
    const items = buildContextMenuItems({ kind: 'relationship', relationshipId: 'r1' }, actions);
    const multiplicity = findItem(items, 'multiplicity');
    expect(multiplicity.submenu).toEqual([]);
    expect(multiplicity.disabled).toBe(true);
    expect(multiplicity.disabledReason).toBeTruthy();
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
