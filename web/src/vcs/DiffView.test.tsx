import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { VcsCommitAuthor, VcsElementChange } from './api';
import { AuthorsList, DiffView } from './DiffView';

const modifiedAttribute: VcsElementChange = {
  changeKind: 'MODIFIED',
  targetType: 'ATTRIBUTE',
  targetId: '11111111-1111-4111-8111-111111111111',
  targetName: 'total',
  ownerName: 'Order',
  fields: [{ field: 'type', before: 'String', after: 'Integer' }],
};

const addedClass: VcsElementChange = {
  changeKind: 'ADDED',
  targetType: 'CLASSIFIER',
  targetId: '22222222-2222-4222-8222-222222222222',
  targetName: 'Invoice',
  ownerName: null,
  fields: [{ field: 'name', before: null, after: 'Invoice' }],
};

const removedOperation: VcsElementChange = {
  changeKind: 'REMOVED',
  targetType: 'OPERATION',
  targetId: '33333333-3333-4333-8333-333333333333',
  targetName: 'cancel',
  ownerName: 'Order',
  fields: [{ field: 'visibility', before: 'PUBLIC', after: null }],
};

function renderDiff(changes: VcsElementChange[]): string {
  return renderToStaticMarkup(createElement(DiffView, { changes }));
}

function renderAuthors(authors: VcsCommitAuthor[]): string {
  return renderToStaticMarkup(createElement(AuthorsList, { authors }));
}

describe('field-level diff', () => {
  it('shows the element type, its owner and the before and after value of the changed field', () => {
    const markup = renderDiff([modifiedAttribute]);

    expect(markup).toContain('vcs-diff-modified');
    expect(markup).toContain('Atributo');
    expect(markup).toContain('de Order');
    expect(markup).toContain('total');
    expect(markup).toContain('Tipo');
    expect(markup).toContain('<del>String</del>');
    expect(markup).toContain('<ins>Integer</ins>');
  });

  it('marks added and removed elements and only prints the side that exists', () => {
    const markup = renderDiff([addedClass, removedOperation]);

    expect(markup).toContain('vcs-diff-added');
    expect(markup).toContain('<ins>Invoice</ins>');
    expect(markup).not.toContain('<del>Invoice</del>');
    expect(markup).toContain('vcs-diff-removed');
    expect(markup).toContain('<del>PUBLIC</del>');
    expect(markup).not.toContain('<ins>PUBLIC</ins>');
  });

  it('says so when nothing differs and escapes element names', () => {
    expect(renderDiff([])).toContain('Sin diferencias en los elementos.');
    const hostile: VcsElementChange = { ...addedClass, targetName: '<img src=x onerror=alert(1)>' };
    const markup = renderDiff([hostile]);
    expect(markup).not.toContain('<img');
    expect(markup).toContain('&lt;img');
  });
});

describe('commit authors', () => {
  const authors: VcsCommitAuthor[] = [
    { userId: 'a', displayName: 'Avery Rivera', role: 'MERGED_BY' },
    { userId: 'b', displayName: 'Blake Ortiz', role: 'CONTRIBUTOR' },
  ];

  it('lists every person with what they did', () => {
    const markup = renderAuthors(authors);

    expect(markup).toContain('Avery Rivera');
    expect(markup).toContain('Fusionó');
    expect(markup).toContain('Blake Ortiz');
    expect(markup).toContain('Aportó cambios');
  });

  it('renders nothing without authors', () => {
    expect(renderAuthors([])).toBe('');
  });
});
