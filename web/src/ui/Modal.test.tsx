import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import Modal, { type ModalProps } from './Modal';

function render(overrides: Partial<ModalProps> = {}): string {
  const props: ModalProps = {
    open: true,
    title: 'Project collaboration',
    onClose: () => undefined,
    children: createElement('p', null, 'Panel content'),
    ...overrides,
  };
  return renderToStaticMarkup(createElement(Modal, props));
}

describe('Modal', () => {
  it('renders nothing when closed, not even the children', () => {
    const markup = render({ open: false });

    expect(markup).toBe('');
    expect(markup).not.toContain('Panel content');
    expect(markup).not.toContain('Project collaboration');
  });

  it('renders a dialog with the title and children when open', () => {
    const markup = render();

    expect(markup).toContain('role="dialog"');
    expect(markup).toContain('aria-modal="true"');
    expect(markup).toContain('Project collaboration');
    expect(markup).toContain('Panel content');
    expect(markup).toContain('editor-modal-backdrop');
    expect(markup).toContain('editor-modal-body');
  });

  it('wires aria-labelledby to the id of the rendered title heading', () => {
    const markup = render();

    const labelledBy = markup.match(/aria-labelledby="([^"]+)"/)?.[1];
    const heading = markup.match(/<h2 class="editor-modal-title" id="([^"]+)"/)?.[1];

    expect(labelledBy).toBeTruthy();
    expect(heading).toBeTruthy();
    expect(labelledBy).toBe(heading);
  });

  it('wires aria-describedby to the id of the rendered description', () => {
    const markup = render({ description: 'Everything about this project.' });

    const describedBy = markup.match(/aria-describedby="([^"]+)"/)?.[1];
    const description = markup.match(/<p class="modal-description" id="([^"]+)"/)?.[1];

    expect(describedBy).toBeTruthy();
    expect(description).toBeTruthy();
    expect(describedBy).toBe(description);
    expect(markup).toContain('Everything about this project.');
  });

  it('omits aria-describedby when there is no description', () => {
    expect(render()).not.toContain('aria-describedby');
  });

  it('renders the eyebrow line only when given', () => {
    expect(render({ eyebrow: 'Collaboration' })).toContain('<p class="eyebrow">Collaboration</p>');
    expect(render()).not.toContain('class="eyebrow"');
  });

  it('renders an enabled close button by default and a disabled one when not dismissible', () => {
    const defaultButton = render().match(/<button type="button" class="modal-close"[^>]*>/)?.[0] ?? '';
    expect(defaultButton).not.toContain('disabled');
    expect(defaultButton).toContain('aria-label="Cerrar diálogo"');

    const lockedButton = render({ dismissible: false })
      .match(/<button type="button" class="modal-close"[^>]*>/)?.[0] ?? '';
    expect(lockedButton).toContain('disabled=""');
  });

  it('defaults to the md size and follows the size prop for sm and lg', () => {
    expect(render()).toMatch(/class="editor-modal editor-modal-md"/);
    expect(render({ size: 'sm' })).toMatch(/class="editor-modal editor-modal-sm"/);
    expect(render({ size: 'lg' })).toMatch(/class="editor-modal editor-modal-lg"/);
  });

  it('renders the title block by default with aria-labelledby and no aria-label on the dialog', () => {
    const markup = render();
    const dialogTag = markup.match(/<div class="editor-modal [^"]*"[^>]*>/)?.[0] ?? '';

    expect(markup).toContain('<h2 class="editor-modal-title"');
    expect(dialogTag).toContain('aria-labelledby=');
    expect(dialogTag).not.toContain('aria-label=');
  });

  it('renders no title heading at all when titleVisible={false}, keeping the close button', () => {
    const markup = render({ titleVisible: false });

    expect(markup).not.toContain('editor-modal-title');
    expect(markup).not.toContain('<h2');
    expect(markup).toContain('class="modal-close"');
    expect(markup).toContain('class="editor-modal-header"');
  });

  it('names the dialog with aria-label equal to the title when titleVisible={false}', () => {
    const markup = render({ titleVisible: false });
    const dialogTag = markup.match(/<div class="editor-modal [^"]*"[^>]*>/)?.[0] ?? '';

    expect(dialogTag).toContain('aria-label="Project collaboration"');
    expect(dialogTag).not.toContain('aria-labelledby=');
  });

  it('omits eyebrow and description when titleVisible={false}', () => {
    const markup = render({
      titleVisible: false,
      eyebrow: 'Collaboration',
      description: 'Everything about this project.',
    });

    expect(markup).not.toContain('class="eyebrow"');
    expect(markup).not.toContain('modal-description');
    expect(markup).not.toContain('Everything about this project.');
  });
});
