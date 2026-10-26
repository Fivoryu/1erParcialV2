import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, expectTypeOf, it } from 'vitest';
import type { PresencePerson } from '../api/types';
import PresencePanel from './PresencePanel';

const readerId = '11111111-1111-4111-8111-111111111111';
const entries: PresencePerson[] = [
  {
    userId: readerId,
    displayName: 'Diagram Reader',
    color: '#2f6fdb',
    targetElementId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    cursorX: null,
    cursorY: null,
  },
  {
    userId: '22222222-2222-4222-8222-222222222222',
    displayName: 'Remote Collaborator',
    color: '#d45d79',
    targetElementId: null,
    cursorX: null,
    cursorY: null,
  },
];

function render(presence: PresencePerson[], userId: string | null): string {
  return renderToStaticMarkup(createElement(PresencePanel, { entries: presence, userId }));
}

describe('project presence panel', () => {
  it('requires nullable cursor coordinates in each presence person', () => {
    expectTypeOf<Pick<PresencePerson, 'cursorX'>>().toEqualTypeOf<{ cursorX: number | null }>();
    expectTypeOf<Pick<PresencePerson, 'cursorY'>>().toEqualTypeOf<{ cursorY: number | null }>();
  });

  it('renders each person with their color and marks the current reader', () => {
    const markup = render(entries, readerId);
    const rows = markup.match(/<li\b[^>]*>[\s\S]*?<\/li>/g) ?? [];

    expect(rows).toHaveLength(2);
    expect(rows.find((row) => row.includes('<h3>Diagram Reader</h3>')))
      .toContain('style="background-color:#2f6fdb"');
    expect(rows.find((row) => row.includes('<h3>Remote Collaborator</h3>')))
      .toContain('style="background-color:#d45d79"');
    expect(markup).toContain('data-current-reader="true"');
    expect(markup).toContain('>Tú</p>');
  });

  it('shows a distinct quiet empty state when nobody is present', () => {
    const emptyMarkup = render([], readerId);
    const populatedMarkup = render(entries, readerId);

    expect(emptyMarkup).toContain('No hay nadie en el diagrama en este momento.');
    expect(emptyMarkup).toContain('presence-empty');
    expect(populatedMarkup).not.toContain('No hay nadie en el diagrama en este momento.');
    expect(populatedMarkup).toContain('presence-list');
    expect(emptyMarkup).not.toBe(populatedMarkup);
  });
});
