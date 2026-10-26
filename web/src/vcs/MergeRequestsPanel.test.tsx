import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from '../api/client';
import type { VcsBranch, VcsMergeConflict, VcsMergeRequest } from './api';
import {
  mergeRequestErrorMessage,
  MergeRequestsPanelView,
  type MergeRequestsPanelViewProps,
} from './MergeRequestsPanel';

const mainBranch: VcsBranch = {
  name: 'main', isProtected: true, headCommitId: null, headMessage: null,
  headAuthorDisplayName: null, headCreatedAt: null,
};
const sourceBranch: VcsBranch = {
  name: 'feature-orders', isProtected: false, headCommitId: null, headMessage: null,
  headAuthorDisplayName: null, headCreatedAt: null,
};

const conflicts: VcsMergeConflict[] = [
  {
    kind: 'FIELD_CONFLICT',
    targetType: 'CLASSIFIER',
    targetId: '11111111-1111-4111-8111-111111111111',
    targetName: 'Order',
    field: 'name',
    baseValue: 'Order',
    sourceValue: 'PurchaseOrder',
    targetValue: 'SalesOrder',
    message: 'The name changed on both branches.',
  },
  {
    kind: 'DELETE_MODIFY',
    targetType: 'CLASSIFIER',
    targetId: '22222222-2222-4222-8222-222222222222',
    targetName: 'LegacyInvoice',
    field: null,
    baseValue: { name: 'LegacyInvoice' },
    sourceValue: null,
    targetValue: { name: 'LegacyInvoice', abstract: true },
    message: 'The element was deleted on one branch and modified on the other.',
  },
  {
    kind: 'NAME_COLLISION',
    targetType: 'CLASSIFIER',
    targetId: '33333333-3333-4333-8333-333333333333',
    targetName: 'Customer',
    field: 'name',
    baseValue: null,
    sourceValue: 'Customer',
    targetValue: 'Customer',
    message: 'An element with this name already exists on the target branch.',
  },
];

const openRequest: VcsMergeRequest = {
  mergeRequestId: '44444444-4444-4444-8444-444444444444',
  title: 'Orders review',
  state: 'OPEN',
  sourceBranch: sourceBranch.name,
  targetBranch: mainBranch.name,
  sourceCommitId: null,
  targetCommitId: null,
  mergeBaseCommitId: null,
  hasConflicts: true,
  conflicts,
  openedByDisplayName: 'Avery Rivera',
  decidedByDisplayName: null,
  createdAt: '2026-10-22T12:00:00Z',
};

function props(overrides: Partial<MergeRequestsPanelViewProps> = {}): MergeRequestsPanelViewProps {
  return {
    branches: [mainBranch, sourceBranch],
    mergeRequests: [openRequest],
    loading: false,
    busy: false,
    busyMergeRequestId: null,
    error: null,
    notice: null,
    onOpen: vi.fn(),
    onMerge: vi.fn(),
    onClose: vi.fn(),
    onDismissError: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<MergeRequestsPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(MergeRequestsPanelView, props(overrides)));
}

describe('merge requests panel', () => {
  it('renders source, target, title form fields and merge-request state', () => {
    const markup = render({
      mergeRequests: [
        openRequest,
        { ...openRequest, mergeRequestId: '55555555-5555-4555-8555-555555555555', state: 'MERGED' },
        { ...openRequest, mergeRequestId: '66666666-6666-4666-8666-666666666666', state: 'CLOSED' },
      ],
    });

    expect(markup).toContain('Rama de origen');
    expect(markup).toContain('Rama de destino');
    expect(markup).toContain('Describe esta revisión');
    expect(markup).toContain('Orders review');
    expect(markup).toContain('feature-orders</strong> → <strong>main');
    expect(markup).toContain('vcs-state-chip--open');
    expect(markup).toContain('vcs-state-chip--merged');
    expect(markup).toContain('vcs-state-chip--closed');
    expect(markup).toContain('Abierta');
    expect(markup).toContain('Fusionada');
    expect(markup).toContain('Cerrada');
    expect(markup).toContain('Cerrar solicitud');
  });

  it('shows element, field, and base/source/target values for every conflict kind', () => {
    const markup = render();

    expect(markup).toContain('vcs-conflict-section');
    expect(markup).toContain('vcs-conflict-values');
    expect(markup).toContain('Conflicto de campo · Order');
    expect(markup).toContain('name');
    expect(markup).toContain('PurchaseOrder');
    expect(markup).toContain('SalesOrder');
    expect(markup).toContain('Conflicto de eliminación y modificación · LegacyInvoice');
    expect(markup).toContain('{&quot;name&quot;:&quot;LegacyInvoice&quot;}');
    expect(markup).toContain('{&quot;name&quot;:&quot;LegacyInvoice&quot;,&quot;abstract&quot;:true}');
    expect(markup).toContain('Conflicto de nombres · Customer');
    expect(markup).toContain('Base</dt><dd>—</dd>');
    expect(markup).toContain('Origen</dt><dd>Customer</dd>');
    expect(markup).toContain('Destino</dt><dd>Customer</dd>');
  });

  it('disables merge while conflicts exist but leaves the close action available', () => {
    const markup = render();

    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>Fusionar<\/button>/);
    expect(markup).toMatch(/<button[^>]*>Cerrar solicitud<\/button>/);
    expect(markup).toContain('The name changed on both branches.');
  });

  it('allows merge when an open request has no conflicts', () => {
    const markup = render({
      mergeRequests: [{ ...openRequest, hasConflicts: false, conflicts: [] }],
    });

    expect(markup).toMatch(/<button[^>]*>Fusionar<\/button>/);
    expect(markup).not.toMatch(/<button[^>]*disabled=""[^>]*>Fusionar<\/button>/);
  });

  it('says a server-reported conflict did not merge and shows the refreshed request conflicts', () => {
    const errorMessage = mergeRequestErrorMessage(new ApiTransportError(
      'MERGE_CONFLICTS',
      'Conflict detected.',
      [],
      null,
      409,
    ), true);
    const markup = render({
      error: errorMessage,
      mergeRequests: [openRequest],
    });

    expect(markup).toContain('No se completó la fusión');
    expect(markup).toContain('Se actualizó la solicitud de fusión');
    expect(markup).toContain('role="alert"');
    expect(markup).not.toContain('La solicitud de fusión se completó.');
  });

  it('preserves the server message for other merge-request errors', () => {
    expect(mergeRequestErrorMessage(new ApiTransportError(
      'MERGE_REQUEST_EXISTS',
      'An open request already exists for these branches.',
      [],
      null,
      409,
    ))).toBe('An open request already exists for these branches.');
  });

  it('distinguishes loading from an empty request list', () => {
    expect(render({ loading: true, mergeRequests: [] })).toContain('Cargando las solicitudes de fusión…');
    expect(render({ loading: false, mergeRequests: [] })).toContain('Aún no hay solicitudes de fusión. Abre una para pedir la revisión de una rama.');
  });
});
