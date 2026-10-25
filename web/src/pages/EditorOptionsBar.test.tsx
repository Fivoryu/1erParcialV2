import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { EditorSectionId } from './editorSections';
import EditorOptionsBar from './EditorOptionsBar';

function render(
  activeSection: EditorSectionId | null,
  badges?: Partial<Record<EditorSectionId, number>>,
): string {
  return renderToStaticMarkup(
    createElement(EditorOptionsBar, {
      activeSection,
      onOpenSection: () => undefined,
      ...(badges === undefined ? {} : { badges }),
    }),
  );
}

describe('EditorOptionsBar', () => {
  it('renders one dialog trigger per section in the frozen order', () => {
    const markup = render(null);

    expect(markup).toContain('role="toolbar"');
    expect(markup).toContain('aria-label="Editor options"');
    expect(markup.match(/aria-haspopup="dialog"/g)).toHaveLength(6);
    const order = markup.match(/data-section="[a-z]+"/g) ?? [];
    expect(order).toEqual([
      'data-section="xmi"',
      'data-section="collaboration"',
      'data-section="members"',
      'data-section="review"',
      'data-section="generation"',
      'data-section="vcs"',
    ]);
  });

  it('marks exactly the active section as expanded', () => {
    const markup = render('members');

    expect(markup.match(/aria-expanded="true"/g)).toHaveLength(1);
    expect(markup.match(/aria-expanded="false"/g)).toHaveLength(5);
    const membersButton = markup.match(/<button[^>]*data-section="members"[^>]*>/) ?? [];
    expect(membersButton[0]).toContain('aria-expanded="true"');
  });

  it('marks no section as expanded when nothing is open', () => {
    const markup = render(null);

    expect(markup).not.toContain('aria-expanded="true"');
    expect(markup.match(/aria-expanded="false"/g)).toHaveLength(6);
  });

  it('renders a badge when a count is provided, including zero', () => {
    const markup = render(null, { members: 3, generation: 0 });

    expect(markup).toContain('<span class="options-bar-badge">3</span>');
    expect(markup).toContain('<span class="options-bar-badge">0</span>');
  });

  it('renders no badge for omitted sections', () => {
    const markup = render(null, { members: 3 });

    expect(markup).not.toContain('options-bar-badge">0');
  });

  it('labels each button with its section label', () => {
    const markup = render(null);

    expect(markup).toContain('XMI interchange');
    expect(markup).toContain('Collaboration');
    expect(markup).toContain('Members');
    expect(markup).toContain('Review');
    expect(markup).toContain('Generation');
    expect(markup).toContain('Versioning');
  });
});
