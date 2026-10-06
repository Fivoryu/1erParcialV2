import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import ContextMenu from './ContextMenu';
import { buildMultiplicitySubmenu, type ContextMenuEntry, type ContextMenuItem } from './contextMenuBehavior';
import type { RelationshipResource } from '../api/types';

const items: ContextMenuItem[] = [
  { id: 'edit', label: 'Editar', onSelect: () => undefined },
  { id: 'duplicate', label: 'Duplicar', disabled: true, disabledReason: 'No existe un comando de duplicado en el modelo' },
];

const relationship: RelationshipResource = {
  id: 'r1',
  kind: 'ASSOCIATION',
  name: null,
  constraints: [],
  ends: [
    { id: 'e1', classifierId: 'c1', role: null, multiplicity: 'ZERO_OR_ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 1 },
    { id: 'e2', classifierId: 'c2', role: null, multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 2 },
  ],
};

function render(overrides: { x?: number; y?: number; items?: ContextMenuEntry[] } = {}): string {
  return renderToStaticMarkup(createElement(ContextMenu, {
    x: overrides.x ?? 40,
    y: overrides.y ?? 60,
    items: overrides.items ?? items,
    onClose: () => undefined,
  }));
}

describe('ContextMenu', () => {
  it('renders nothing when there are no items', () => {
    expect(render({ items: [] })).toBe('');
  });

  it('renders an accessible menu with the given item labels', () => {
    const markup = render();
    expect(markup).toContain('role="menu"');
    expect(markup).toContain('Editar');
    expect(markup).toContain('Duplicar');
    expect(markup).toContain('context-menu');
  });

  it('renders enabled items as clickable menu items without the disabled attribute', () => {
    const markup = render();
    const button = markup.match(/<button[^>]*>(?=<span class="context-menu-item-label">Editar<)/)?.[0] ?? '';
    expect(button).toContain('role="menuitem"');
    expect(button).not.toContain('disabled');
  });

  it('renders disabled items as non-clickable buttons carrying the reason as a tooltip', () => {
    const markup = render();
    const button = markup.match(/<button[^>]*>(?=<span class="context-menu-item-label">Duplicar<)/)?.[0] ?? '';
    expect(button).toContain('disabled');
    expect(button).toContain('aria-disabled="true"');
    expect(button).toContain('title="No existe un comando de duplicado en el modelo"');
  });

  it('positions the menu with fixed viewport coordinates', () => {
    const markup = render({ x: 128, y: 256 });
    expect(markup).toMatch(/left:\s?128px/);
    expect(markup).toMatch(/top:\s?256px/);
  });

  it('renders a submenu container for items that carry one, hidden until it is opened', () => {
    const markup = render({
      items: [
        { id: 'multiplicity', label: 'Multiplicidad', submenu: [{ id: 'one', label: '1', onSelect: () => undefined }] },
      ],
    });
    expect(markup).toContain('aria-haspopup="true"');
    expect(markup).toContain('role="menu"');
    expect(markup).toContain('context-menu-submenu');
    expect(markup).toContain('hidden');
  });

  it('renders a separator between item groups', () => {
    const markup = render({
      items: [
        { id: 'edit', label: 'Editar', onSelect: () => undefined },
        { separator: true },
        { id: 'copy', label: 'Copiar', disabled: true, disabledReason: 'No soportado por el modelo' },
      ],
    });
    expect(markup).toContain('role="separator"');
  });

  it('renders the Multiplicidad submenu with the four notation options and a check on the current value', () => {
    const markup = render({
      items: [
        {
          id: 'multiplicity',
          label: 'Multiplicidad',
          submenu: buildMultiplicitySubmenu(relationship, 1, (id) => (id === 'c1' ? 'Desarrollo' : 'Persona'), () => undefined),
        },
      ],
    });
    expect(markup).toContain('Extremo A — Desarrollo');
    expect(markup).toContain('context-menu-submenu-header');
    for (const notation of ['0..1', '1..*', '0..*']) {
      expect(markup).toContain(notation);
    }
    expect(markup).toMatch(/context-menu-item-check[^<]*✓/);
    // Only the currently set end multiplicity (0..1) carries the check.
    expect(markup.match(/context-menu-item-check/g)).toHaveLength(1);
  });
});
