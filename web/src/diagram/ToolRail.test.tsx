import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ClassifierResource, ModelResource } from '../api/types';
import RelationSetup from './RelationSetup';
import ToolRail from './ToolRail';

function rail(relationActive: boolean): string {
  return renderToStaticMarkup(createElement(ToolRail, {
    relationKind: 'COMPOSITION',
    relationActive,
    onSelectTool: () => undefined,
    onAddClassifier: () => undefined,
    onToggleRelation: () => undefined,
    onRelationKindChange: () => undefined,
  }));
}

function classifier(id: string, name: string, kind = 'CLASS'): ClassifierResource {
  return { id, name, kind, isAbstract: false, namespaceId: null, attributes: [], operations: [], constraints: [] };
}

const model: ModelResource = {
  id: 'm',
  projectId: 'p',
  revision: 1,
  versionId: 'v',
  views: [],
  relationships: [],
  classifiers: [classifier('a', 'Student'), classifier('b', 'Course'), classifier('c', 'Enrollment'), classifier('i', 'Shape', 'INTERFACE')],
};

describe('ToolRail', () => {
  it('shows the current relation kind on the relation tool, with the types folded away', () => {
    const markup = rail(false);

    expect(markup).toContain('<span>Composición</span>');
    expect(markup).toContain('aria-label="Elegir tipo de relación"');
    expect(markup).toContain('aria-expanded="false"');
    expect(markup).not.toContain('id="relation-picker"');
  });

  it('presses Seleccionar unless the relation tool is on', () => {
    expect(rail(false)).toMatch(/aria-pressed="true" class="tool-button"[^>]*>.*Seleccionar/);
    expect(rail(true)).toMatch(/aria-pressed="true" class="tool-button relation-tool"/);
  });
});

describe('RelationSetup', () => {
  it('asks for the association class among classes other than both ends', () => {
    const markup = renderToStaticMarkup(createElement(RelationSetup, {
      model,
      kind: 'ASSOCIATION_CLASS',
      sourceId: 'a',
      targetId: 'b',
      onEndsChange: () => undefined,
      onCommand: () => undefined,
      onExit: () => undefined,
    }));

    expect(markup).toContain('Clase de asociación');
    const classOptions = markup.slice(markup.indexOf('id="relation-association-class"'));
    expect(classOptions).toContain('>Enrollment</option>');
    expect(classOptions).not.toContain('>Shape</option>');
    expect(classOptions).not.toContain('>Student</option>');
  });

  it('does not ask for a class for the other kinds', () => {
    const markup = renderToStaticMarkup(createElement(RelationSetup, {
      model,
      kind: 'GENERALIZATION',
      sourceId: '',
      targetId: '',
      onEndsChange: () => undefined,
      onCommand: () => undefined,
      onExit: () => undefined,
    }));

    expect(markup).toContain('Generalización');
    expect(markup).not.toContain('relation-association-class');
  });
});
