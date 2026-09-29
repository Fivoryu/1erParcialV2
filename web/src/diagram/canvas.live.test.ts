import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { createApiClient } from '../api/client';
import type { ClassifierKind } from './commands';
import { buildClassifierCreateEnvelope } from './commands';
import UmlCanvas, { buildFlowNodes } from './UmlCanvas';
import { UmlClassCard } from './UmlClassNode';

const liveBaseUrl = import.meta.env.VITE_API_BASE_URL;

describe.skipIf(!liveBaseUrl)('live UML canvas', () => {
  it('creates a classifier through the acceptance backend and renders the returned model', async () => {
    const api = createApiClient(liveBaseUrl);
    const suffix = new Date().toISOString();
    const project = await api.createProject({
      name: `T8 live canvas ${suffix}`,
      description: 'Created by the opt-in live canvas test.',
    });
    const model = await api.getModel(project.id);
    const envelope = buildClassifierCreateEnvelope(model, 'CLASS' satisfies ClassifierKind);
    const outcome = await api.executeCommand(project.id, envelope);

    expect(outcome.kind).toBe('accepted');
    const updatedModel = await api.getModel(project.id);
    const created = updatedModel.classifiers.find((classifier) => classifier.id === envelope.payload.id);
    expect(created).toBeDefined();
    if (!created) {
      throw new Error('The live backend did not return the newly created classifier.');
    }

    const flowNode = buildFlowNodes(updatedModel, [], () => undefined, () => undefined)
      .find((node) => node.id === created.id);
    expect(flowNode).toBeDefined();
    if (!flowNode) {
      throw new Error('The canvas did not create a flow node for the live classifier.');
    }
    expect(flowNode.position).toEqual({ x: 56, y: 56 });
    const renderedNode = renderToStaticMarkup(createElement(UmlClassCard, { classifier: flowNode.data.classifier }));
    const canvas = renderToStaticMarkup(createElement(UmlCanvas, {
      model: updatedModel,
      selection: null,
      onSelect: () => undefined,
      onCommand: () => undefined,
      onAddClassifier: () => undefined,
      onDelete: () => undefined,
    }));
    expect(renderedNode).toContain('uml-class-card');
    expect(renderedNode).toContain(created.name);
    expect(renderedNode).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    expect(canvas).toContain('React Flow');
    expect(canvas).toContain('react-flow__background');
  });
});
