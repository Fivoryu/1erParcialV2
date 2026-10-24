import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from '../api/client';
import type { VcsBranch, VcsStatus } from './api';
import {
  attemptBranchCheckout,
  BranchesPanelView,
  type BranchesPanelViewProps,
} from './BranchesPanel';

const mainBranch: VcsBranch = {
  name: 'main',
  isProtected: true,
  headCommitId: null,
  headMessage: null,
  headAuthorDisplayName: null,
  headCreatedAt: null,
};

const featureBranch: VcsBranch = {
  name: 'feature-orders',
  isProtected: false,
  headCommitId: '11111111-1111-4111-8111-111111111111',
  headMessage: 'Add orders',
  headAuthorDisplayName: 'Avery Rivera',
  headCreatedAt: '2026-10-22T12:00:00Z',
};

const status: VcsStatus = { branch: 'main', headCommitId: null, dirty: false };

function props(overrides: Partial<BranchesPanelViewProps> = {}): BranchesPanelViewProps {
  return {
    branches: [mainBranch, featureBranch],
    status,
    loading: false,
    busy: false,
    busyBranch: null,
    error: null,
    notice: null,
    onCreateBranch: vi.fn(),
    onCheckout: vi.fn(),
    onToggleProtection: vi.fn(),
    onDismissError: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<BranchesPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(BranchesPanelView, props(overrides)));
}

describe('version branches panel', () => {
  it('lists branch heads and exposes protected status and checkout controls', () => {
    const markup = render();

    expect(markup).toContain('vcs-branch-row--current');
    expect(markup).toContain('Current');
    expect(markup).toContain('Protected');
    expect(markup).toContain('feature-orders');
    expect(markup).toContain('Add orders');
    expect(markup).toContain('Avery Rivera');
    expect(markup).toContain('Protect main');
    expect(markup).toContain('Switch branch');
    expect(markup).toContain('Create branch');
  });

  it('does not allow checkout of the current branch', () => {
    const markup = render();

    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>Current branch<\/button>/);
  });

  it('keeps a dirty-working-copy checkout failure on an honest readable error path', async () => {
    const result = await attemptBranchCheckout(
      () => Promise.reject(new ApiTransportError(
        'WORKING_COPY_DIRTY',
        'Commit or discard changes before switching branches.',
        [],
        null,
        409,
      )),
      'main',
    );

    expect(result).toEqual({
      kind: 'error',
      currentBranch: 'main',
      message: 'Commit or discard changes before switching branches.',
    });
    expect(render({ error: result.kind === 'error' ? result.message : null })).toContain(
      'Commit or discard changes before switching branches.',
    );
  });

  it('distinguishes an empty branch list from a loading error', () => {
    expect(render({ loading: true, branches: [] })).toContain('Loading branches…');
    expect(render({ loading: false, branches: [] })).toContain('No branches are available; create a branch to start working.');
  });
});
