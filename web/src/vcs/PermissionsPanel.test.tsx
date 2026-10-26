import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { VCS_PERMISSION_ACTIONS, type VcsProjectPermission } from './api';
import { PermissionsPanelView, type PermissionsPanelViewProps } from './PermissionsPanel';

const permission: VcsProjectPermission = {
  userId: '11111111-1111-4111-8111-111111111111',
  username: 'diagram.editor',
  displayName: 'Avery Rivera',
  actions: ['VIEW', 'ADD', 'MERGE'],
};

function props(overrides: Partial<PermissionsPanelViewProps> = {}): PermissionsPanelViewProps {
  return {
    permissions: [permission],
    currentUserId: null,
    loading: false,
    busyUserId: null,
    error: null,
    onToggleAction: vi.fn(),
    onDismissError: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<PermissionsPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(PermissionsPanelView, props(overrides)));
}

describe('version permissions panel', () => {
  it('renders one row per returned user and all actions as checked-state checkboxes', () => {
    const markup = render({ permissions: [permission, { ...permission, userId: '22222222-2222-4222-8222-222222222222', displayName: 'Morgan Lee' }] });

    expect(markup).toContain('<table class="vcs-permission-table">');
    expect(markup).toContain('scope="col">Administrar permisos</th>');
    expect(markup).toContain('Avery Rivera');
    expect(markup).toContain('Morgan Lee');
    expect(markup.match(/type="checkbox"/g)).toHaveLength(VCS_PERMISSION_ACTIONS.length * 2);
    expect(markup.match(/checked=""/g)).toHaveLength(permission.actions.length * 2);
    expect(markup).toContain('Ver para Avery Rivera');
    expect(markup).toContain('Administrar permisos para Avery Rivera');
    expect(markup).toContain('Al guardar un cambio, se fija el conjunto de permisos efectivo para esa persona.');
    expect(markup).not.toContain(permission.userId);
  });

  it('disables permission controls while that user update is in flight', () => {
    const markup = render({ busyUserId: permission.userId });

    expect(markup.match(/disabled=""/g)).toHaveLength(VCS_PERMISSION_ACTIONS.length);
  });

  it('keeps the current user visible but disables every action with a clear hint', () => {
    const otherPermission = {
      ...permission,
      userId: '22222222-2222-4222-8222-222222222222',
      displayName: 'Morgan Lee',
    };
    const markup = render({
      permissions: [permission, otherPermission],
      currentUserId: permission.userId,
    });

    expect(markup).toContain('Avery Rivera');
    expect(markup).toContain('Morgan Lee');
    expect(markup).toContain('No puedes cambiar tus propios permisos; pídele a otra persona propietaria que lo haga.');
    expect(markup.match(/disabled=""/g)).toHaveLength(VCS_PERMISSION_ACTIONS.length);
    expect(markup.match(/type="checkbox"/g)).toHaveLength(VCS_PERMISSION_ACTIONS.length * 2);
    expect(markup.match(/checked=""/g)).toHaveLength(permission.actions.length * 2);
  });

  it('shows the API failure in an accessible alert', () => {
    const markup = render({ error: 'You do not have permission to manage project permissions.' });

    expect(markup).toContain('role="alert"');
    expect(markup).toContain('You do not have permission to manage project permissions.');
    expect(markup).toContain('Descartar');
  });

  it('distinguishes loading and an empty permissions list', () => {
    expect(render({ loading: true, permissions: [] })).toContain('Cargando los permisos…');
    expect(render({ loading: false, permissions: [] })).toContain('No hay permisos de proyecto disponibles. Agrega a una persona al proyecto antes de cambiar sus acciones.');
  });
});
