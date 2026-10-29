import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ModelResource, ValidationDiagnosticResource } from '../api/types';
import ValidationStrip from './ValidationStrip';

const model: ModelResource = {
  id: 'm', projectId: 'p', revision: 1, versionId: 'v', views: [], classifiers: [], relationships: [],
};

describe('ValidationStrip', () => {
  it('shows no count until the validator answers, so an unanswered model never reads as clean', () => {
    const markup = renderToStaticMarkup(createElement(ValidationStrip, {
      model,
      loadDiagnostics: () => new Promise<ValidationDiagnosticResource[]>(() => undefined),
    }));

    expect(markup).toContain('Errores <strong>—</strong>');
    expect(markup).toContain('Advertencias <strong>—</strong>');
    expect(markup).not.toContain('Errores <strong>0</strong>');
  });
});
