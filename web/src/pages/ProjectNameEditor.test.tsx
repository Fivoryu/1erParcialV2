import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import ProjectNameEditor, { projectNameError } from './ProjectNameEditor';

describe('ProjectNameEditor', () => {
  it('shows the project name as the page title with a rename action', () => {
    const markup = renderToStaticMarkup(
      createElement(ProjectNameEditor, { name: 'Biblioteca', onRename: async () => undefined }),
    );

    expect(markup).toContain('<h1 id="project-title">Biblioteca</h1>');
    expect(markup).toContain('aria-label="Renombrar proyecto"');
  });
});

describe('projectNameError', () => {
  it('rejects a blank name in Spanish', () => {
    expect(projectNameError('   ')).toBe('Escribe un nombre de proyecto para continuar.');
  });

  it('accepts a non-blank name', () => {
    expect(projectNameError('Biblioteca')).toBeNull();
  });
});
