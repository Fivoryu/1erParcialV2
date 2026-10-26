import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type {
  VcsBranch,
  VcsCommit,
  VcsCommitDetail,
  VcsStatus,
} from './api';
import { HistoryPanelView, type HistoryPanelViewProps } from './HistoryPanel';

const branch: VcsBranch = {
  name: 'main',
  isProtected: true,
  headCommitId: '11111111-1111-4111-8111-111111111111',
  headMessage: 'Add account diagram',
  headAuthorDisplayName: 'Avery Rivera',
  headCreatedAt: '2026-10-22T12:00:00Z',
};

const status: VcsStatus = { branch: 'main', headCommitId: branch.headCommitId, dirty: false };

const commit: VcsCommit = {
  commitId: '22222222-2222-4222-8222-222222222222',
  branchName: 'main',
  kind: 'MANUAL',
  origin: 'MANUAL',
  message: 'Add account diagram',
  authorUserId: '33333333-3333-4333-8333-333333333333',
  authorDisplayName: 'Avery Rivera',
  parentCommitId: null,
  secondParentCommitId: null,
  stateHash: 'a'.repeat(64),
  createdAt: '2026-10-22T12:00:00Z',
};

const commitDetail: VcsCommitDetail = {
  ...commit,
  changes: [{
    changeKind: 'ADDED',
    targetType: 'CLASSIFIER',
    targetId: '44444444-4444-4444-8444-444444444444',
    targetName: 'Account',
  }],
};

function props(overrides: Partial<HistoryPanelViewProps> = {}): HistoryPanelViewProps {
  return {
    branches: [branch],
    status,
    selectedBranch: branch.name,
    commits: [commit],
    commitDetail: null,
    loading: false,
    detailLoading: false,
    busy: false,
    error: null,
    notice: null,
    onSelectBranch: vi.fn(),
    onCommit: vi.fn(),
    onOpenCommit: vi.fn(),
    onCloseDetail: vi.fn(),
    onDismissError: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<HistoryPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(HistoryPanelView, props(overrides)));
}

describe('version history panel', () => {
  it('shows the selected branch, author, commit message, and clean working-copy chip', () => {
    const markup = render();

    expect(markup).toContain('Rama del historial');
    expect(markup).toContain('main');
    expect(markup).toContain('Avery Rivera');
    expect(markup).toContain('Add account diagram');
    expect(markup).toContain('Copia de trabajo limpia');
    expect(markup).toContain('vcs-commit-list');
    expect(markup).toContain('vcs-kind-chip');
    expect(markup).toContain('vcs-origin-chip');
    expect(markup).toContain('Confirmar cambios');
  });

  it('renders changed elements in the selected commit details', () => {
    const markup = render({ commitDetail });

    expect(markup).toContain('Detalles de la confirmación');
    expect(markup).toContain('Elementos modificados');
    expect(markup).toContain('Account');
    expect(markup).toContain('Agregado');
  });

  it('shows an observable error alert without hiding the history controls', () => {
    const markup = render({ error: 'The branch is protected.' });

    expect(markup).toContain('role="alert"');
    expect(markup).toContain('The branch is protected.');
    expect(markup).toContain('Descartar');
    expect(markup).toContain('Confirmar cambios');
  });

  it('distinguishes loading and empty histories', () => {
    expect(render({ loading: true, commits: [] })).toContain('Cargando el historial…');
    expect(render({ loading: false, commits: [] })).toContain('Aún no hay confirmaciones. Haz un cambio y guárdalo para iniciar el historial.');
  });
});
