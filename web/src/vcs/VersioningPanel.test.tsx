import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { VcsBranch, VcsStatus } from './api';
import {
  HISTORY_COUNT_LIMIT,
  nextVersioningTab,
  tabCountLabel,
  VersioningPanelView,
  VersioningStatusBar,
} from './VersioningPanel';

const status: VcsStatus = {
  branch: 'main',
  headCommitId: '11111111-1111-4111-8111-111111111111',
  dirty: false,
};

const headBranch: VcsBranch = {
  name: 'main',
  isProtected: true,
  headCommitId: status.headCommitId,
  headMessage: 'Add account diagram',
  headAuthorDisplayName: 'Avery Rivera',
  headCreatedAt: '2026-10-22T12:00:00Z',
};

function renderStatus(statusOverride: Partial<VcsStatus> = {}): string {
  return renderToStaticMarkup(createElement(VersioningStatusBar, {
    status: { ...status, ...statusOverride },
    headBranch,
    loading: false,
    error: null,
  }));
}

function renderVersioningTabs(): string {
  return renderToStaticMarkup(createElement(VersioningPanelView, {
    projectId: '55555555-5555-4555-8555-555555555555',
    status,
    headBranch,
    loading: false,
    error: null,
    onStatusChanged: vi.fn(),
    onModelCheckout: vi.fn(),
    historyDetailOpen: false,
    onHistoryDetailOpenChange: vi.fn(),
  }));
}

describe('versioning workspace', () => {
  it('shows one selected panel at a time and starts on History', () => {
    const markup = renderVersioningTabs();

    expect(markup).toContain('role="tablist"');
    expect(markup.match(/role="tab"/g)).toHaveLength(4);
    expect(markup.match(/aria-selected="true"/g)).toHaveLength(1);
    expect(markup).toContain('id="vcs-tab-history"');
    expect(markup.match(/role="tabpanel"/g)).toHaveLength(4);
    expect(markup.match(/hidden=""/g)).toHaveLength(3);
    expect(markup).toContain('aria-labelledby="vcs-tab-history"');
  });

  it('wraps roving keyboard navigation around all four tabs, in repository order', () => {
    expect(nextVersioningTab('history', 'previous')).toBe('permissions');
    expect(nextVersioningTab('history', 'next')).toBe('branches');
    expect(nextVersioningTab('branches', 'next')).toBe('reviews');
    expect(nextVersioningTab('permissions', 'next')).toBe('history');
  });

  it('heads the workspace like a repository, with a counter on each tab that has one', () => {
    const markup = renderToStaticMarkup(createElement(VersioningPanelView, {
      projectId: '55555555-5555-4555-8555-555555555555',
      projectName: 'Library',
      counts: { history: 12, branches: 2, reviews: 1 },
      status,
      headBranch,
      loading: false,
      error: null,
      onStatusChanged: vi.fn(),
      onModelCheckout: vi.fn(),
      historyDetailOpen: false,
      onHistoryDetailOpenChange: vi.fn(),
    }));

    expect(markup).toContain('Library / <strong>modelo-uml</strong>');
    expect(markup).toContain('<span class="vcs-repo-tag">main</span>');
    expect(markup.match(/id="vcs-tab-[a-z]+"/g)).toEqual([
      'id="vcs-tab-history"',
      'id="vcs-tab-branches"',
      'id="vcs-tab-reviews"',
      'id="vcs-tab-permissions"',
    ]);
    expect(markup).toContain('Historial<span class="vcs-tab-count">12</span>');
    expect(markup).toContain('Ramas<span class="vcs-tab-count">2</span>');
    expect(markup).toContain('Revisiones<span class="vcs-tab-count">1</span>');
    expect(markup).toContain('Permisos</button>');
  });

  it('caps the history counter at the number of commits it reads', () => {
    expect(tabCountLabel('history', { history: HISTORY_COUNT_LIMIT + 1 })).toBe(`${HISTORY_COUNT_LIMIT}+`);
    expect(tabCountLabel('history', { history: HISTORY_COUNT_LIMIT })).toBe(String(HISTORY_COUNT_LIMIT));
    expect(tabCountLabel('reviews', {})).toBeNull();
  });

  it('shows clean and uncommitted working-copy states with distinct chips', () => {
    const cleanMarkup = renderStatus({ dirty: false });
    const dirtyMarkup = renderStatus({ dirty: true });

    expect(cleanMarkup).toContain('class="vcs-dirty-indicator is-clean"');
    expect(cleanMarkup).toContain('>limpio</span>');
    expect(dirtyMarkup).toContain('class="vcs-dirty-indicator is-dirty"');
    expect(dirtyMarkup).toContain('cambios sin confirmar');
    expect(cleanMarkup).toContain('Rama actual');
    expect(cleanMarkup).toContain('Add account diagram');
    expect(cleanMarkup).toContain('Avery Rivera');
    expect(cleanMarkup).not.toContain(status.headCommitId);
  });
});
