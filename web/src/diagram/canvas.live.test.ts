import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { createApiClient, type ApiClient } from '../api/client';
import type { ModelResource } from '../api/types';
import type { ClassifierKind, GeometryUpdate, ModelCommandRequest } from './commands';
import {
  buildClassifierCreateEnvelope,
  buildGeometrySetRequest,
  buildModelCommandEnvelope,
  buildParameterAddRequest,
  buildParameterUpdateRequest,
  buildRelationshipCreateRequest,
} from './commands';
import { createGeometryCoalescer } from './geometry-coalescer';
import { formatOperation } from './uml-format';
import UmlCanvas, { buildFlowEdges, buildFlowNodes } from './UmlCanvas';
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

  it('creates a relationship and round-trips the visible cut with persisted layout', async () => {
    const api = createApiClient(liveBaseUrl);
    const suffix = new Date().toISOString();
    const project = await api.createProject({
      name: `T9 live cut ${suffix}`,
      description: 'Opt-in live test for connectors and persisted layout.',
    });
    let model = await api.getModel(project.id);
    const view = model.views.find((candidate) => candidate.is_default);
    if (!view) {
      throw new Error('The live backend did not provide a default diagram view.');
    }

    const customerCreate = buildClassifierCreateEnvelope(model, 'CLASS');
    customerCreate.payload.name = `Customer ${suffix}`;
    model = await applyEnvelope(api, project.id, customerCreate);
    const customerId = String(customerCreate.payload.id);

    const subscriptionCreate = buildClassifierCreateEnvelope(model, 'CLASS');
    subscriptionCreate.payload.name = `Subscription ${suffix}`;
    model = await applyEnvelope(api, project.id, subscriptionCreate);
    const subscriptionId = String(subscriptionCreate.payload.id);

    model = await applyRequest(api, project.id, model, {
      type: 'attribute.add',
      targets: [{ scope: 'classifier', id: customerId }],
      payload: {
        name: 'email',
        type: { primitiveName: 'String' },
        visibility: 'PRIVATE',
        multiplicity: 'EXACTLY_ONE',
      },
    });
    model = await applyRequest(api, project.id, model, {
      type: 'operation.add',
      targets: [{ scope: 'classifier', id: customerId }],
      payload: {
        name: 'validate',
        returnType: { primitiveName: 'boolean' },
        visibility: 'PUBLIC',
        isAbstract: false,
        isStatic: false,
      },
    });

    const relationshipRequest = buildRelationshipCreateRequest(customerId, subscriptionId, 'ASSOCIATION');
    relationshipRequest.payload = {
      ...relationshipRequest.payload,
      name: 'subscriptions',
      source: {
        classifierId: customerId,
        role: 'customer',
        multiplicity: 'EXACTLY_ONE',
        navigability: 'NAVIGABLE',
      },
      target: {
        classifierId: subscriptionId,
        role: 'subscriptions',
        multiplicity: 'ZERO_OR_MANY',
        navigability: 'NOT_NAVIGABLE',
      },
    };
    model = await applyRequest(api, project.id, model, relationshipRequest);
    const createdRelationship = model.relationships.find((candidate) => candidate.name === 'subscriptions');
    if (!createdRelationship) {
      throw new Error('The live backend did not return the new relationship.');
    }

    const geometryCommands: GeometryUpdate[] = [];
    const coalescer = createGeometryCoalescer<GeometryUpdate>((_, payload) => {
      geometryCommands.push(payload);
    });
    const geometry = (targetId: string, x: number, y: number, w: number, h: number, zOrder: number) => ({
      targetType: 'CLASSIFIER' as const,
      viewId: view.id,
      targetId,
      x,
      y,
      w,
      h,
      zOrder,
    });

    model = await applyRequest(api, project.id, model, buildGeometrySetRequest(geometry(customerId, 80, 100, 240, 160, 1)));
    model = await applyRequest(api, project.id, model, buildGeometrySetRequest(geometry(subscriptionId, 420, 100, 260, 180, 2)));
    for (let index = 0; index < 50; index += 1) {
      coalescer.schedule(`classifier:${customerId}`, geometry(customerId, 104 + index, 148 + index, 240, 160, 1));
    }
    coalescer.flush(`classifier:${customerId}`);
    expect(geometryCommands).toHaveLength(1);
    model = await applyRequest(api, project.id, model, buildGeometrySetRequest(geometryCommands[0]!));
    model = await applyRequest(api, project.id, model, buildGeometrySetRequest({
      targetType: 'RELATIONSHIP',
      viewId: view.id,
      targetId: createdRelationship.id,
      waypoints: [{ x: 360, y: 168 }, { x: 420, y: 168 }],
    }));

    const beforeReload = await api.getModel(project.id);
    const afterReload = await api.getModel(project.id);
    const expectedCustomerGeometry = { x: 153, y: 197, w: 240, h: 160, zOrder: 1 };
    const expectedSubscriptionGeometry = { x: 420, y: 100, w: 260, h: 180, zOrder: 2 };
    const expectedWaypoints = [{ x: 360, y: 168 }, { x: 420, y: 168 }];
    const flowNodes = buildFlowNodes(afterReload, [], () => undefined, () => undefined);
    const flowEdges = buildFlowEdges(afterReload);
    const customerGeometry = beforeReload.views[0]?.element_geometry.find((item) => item.targetId === customerId);
    const subscriptionGeometry = beforeReload.views[0]?.element_geometry.find((item) => item.targetId === subscriptionId);
    const customerNode = flowNodes.find((node) => node.id === customerId);
    const subscriptionNode = flowNodes.find((node) => node.id === subscriptionId);
    const relationshipEdge = flowEdges.find((edge) => edge.id === createdRelationship.id);

    expect(beforeReload.revision).toBe(afterReload.revision);
    expect(customerGeometry).toMatchObject(expectedCustomerGeometry);
    expect(subscriptionGeometry).toMatchObject(expectedSubscriptionGeometry);
    expect(customerNode?.position).toEqual({ x: expectedCustomerGeometry.x, y: expectedCustomerGeometry.y });
    expect(subscriptionNode?.position).toEqual({ x: expectedSubscriptionGeometry.x, y: expectedSubscriptionGeometry.y });
    expect(relationshipEdge?.data?.waypoints).toEqual(expectedWaypoints);
    expect(afterReload.classifiers.find((classifier) => classifier.id === customerId)?.attributes.map((item) => item.name))
      .toContain('email');
    expect(afterReload.classifiers.find((classifier) => classifier.id === customerId)?.operations.map((item) => item.name))
      .toContain('validate');
    expect(afterReload.relationships.find((item) => item.id === createdRelationship.id)?.kind).toBe('ASSOCIATION');
  });

  it('creates, reorders, renames, and reads back a two-parameter operation', async () => {
    const api = createApiClient(liveBaseUrl);
    const suffix = new Date().toISOString();
    const project = await api.createProject({
      name: `T3 live parameters ${suffix}`,
      description: 'Opt-in live test for operation parameters.',
    });
    let model = await api.getModel(project.id);

    const classifierEnvelope = buildClassifierCreateEnvelope(model, 'CLASS');
    classifierEnvelope.payload.name = `ParameterOwner ${suffix}`;
    model = await applyEnvelope(api, project.id, classifierEnvelope);
    const classifierId = String(classifierEnvelope.payload.id);

    const operationEnvelope = buildModelCommandEnvelope(model, {
      type: 'operation.add',
      targets: [{ scope: 'classifier', id: classifierId }],
      payload: { name: 'confirm', returnType: { primitiveName: 'boolean' }, visibility: 'PUBLIC' },
    });
    model = await applyEnvelope(api, project.id, operationEnvelope);
    const operationId = String(operationEnvelope.payload.id);

    const firstParameterEnvelope = buildModelCommandEnvelope(model, buildParameterAddRequest(operationId, {
      name: 'account',
      type: { primitiveName: 'String' },
      direction: 'IN',
      ordinal: 0,
    }));
    model = await applyEnvelope(api, project.id, firstParameterEnvelope);
    const firstParameterId = String(firstParameterEnvelope.payload.id);

    const secondParameterEnvelope = buildModelCommandEnvelope(model, buildParameterAddRequest(operationId, {
      name: 'retries',
      type: { primitiveName: 'Integer' },
      direction: 'OUT',
      ordinal: 1,
    }));
    model = await applyEnvelope(api, project.id, secondParameterEnvelope);
    const secondParameterId = String(secondParameterEnvelope.payload.id);

    const reorderEnvelope = buildModelCommandEnvelope(model,
      buildParameterUpdateRequest(secondParameterId, { ordinal: 0 }));
    expect(reorderEnvelope.baseRevision).toBe(model.revision);
    model = await applyEnvelope(api, project.id, reorderEnvelope);
    expect(model.revision).toBe(reorderEnvelope.baseRevision + 1);

    const renameEnvelope = buildModelCommandEnvelope(model,
      buildParameterUpdateRequest(secondParameterId, { name: 'limit' }));
    expect(renameEnvelope.baseRevision).toBe(model.revision);
    await applyEnvelope(api, project.id, renameEnvelope);

    const reloaded = await api.getModel(project.id);
    const operation = reloaded.classifiers.find((classifier) => classifier.id === classifierId)
      ?.operations.find((candidate) => candidate.id === operationId);
    expect(operation?.parameters.map((parameter) => ({
      id: parameter.id,
      name: parameter.name,
      type: parameter.type,
      direction: parameter.direction,
      ordinal: parameter.ordinal,
    }))).toEqual([
      { id: secondParameterId, name: 'limit', type: 'Integer', direction: 'OUT', ordinal: 0 },
      { id: firstParameterId, name: 'account', type: 'String', direction: 'IN', ordinal: 1 },
    ]);
    expect(operation && formatOperation(operation)).toBe('+ confirm(limit: Integer, account: String): boolean');
  });
});

async function applyRequest(
  api: ApiClient,
  projectId: string,
  model: ModelResource,
  request: ModelCommandRequest,
): Promise<ModelResource> {
  return applyEnvelope(api, projectId, buildModelCommandEnvelope(model, request));
}

async function applyEnvelope(
  api: ApiClient,
  projectId: string,
  envelope: ReturnType<typeof buildClassifierCreateEnvelope>,
): Promise<ModelResource> {
  const outcome = await api.executeCommand(projectId, envelope);
  if (outcome.kind !== 'accepted') {
    throw new Error(`The live backend rejected ${envelope.type}.`);
  }
  return api.getModel(projectId);
}
