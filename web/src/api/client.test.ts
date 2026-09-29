import { describe, expect, it, vi } from 'vitest';
import { buildOperationEnvelope, type UuidFactory } from './envelope';
import { ApiTransportError, createApiClient } from './client';
import type { ModelResource, OperationEnvelope } from './types';
import recordedModelFixture from './fixtures/model-response.json';

// Captured verbatim from GET /projects/{id}/model during this task's live acceptance run.
const recordedModel: ModelResource = recordedModelFixture;
const projectId = '22222222-2222-4222-8222-222222222222';
const modelId = '33333333-3333-4333-8333-333333333333';

function sequenceUuidFactory(...values: string[]): UuidFactory {
  let index = 0;
  return () => {
    const value = values[index];
    if (value === undefined) {
      throw new Error('The test UUID sequence is exhausted.');
    }
    index += 1;
    return value;
  };
}

function classifierCreateEnvelope(uuidFactory: UuidFactory): OperationEnvelope {
  return buildOperationEnvelope({
    type: 'classifier.create',
    projectId,
    modelId,
    baseRevision: 4,
    payload: {
      id: 'must-be-replaced-by-client-id',
      name: 'Customer',
      kind: 'CLASS',
      namespaceId: null,
    },
  }, uuidFactory);
}

describe('operation envelope builder', () => {
  it('builds every required field and creates distinct client-owned ids on each call', () => {
    const uuidFactory = sequenceUuidFactory(
      '00000000-0000-4000-8000-000000000001',
      '00000000-0000-4000-8000-000000000002',
      '00000000-0000-4000-8000-000000000003',
      '00000000-0000-4000-8000-000000000004',
      '00000000-0000-4000-8000-000000000005',
      '00000000-0000-4000-8000-000000000006',
    );

    const first = classifierCreateEnvelope(uuidFactory);
    const second = classifierCreateEnvelope(uuidFactory);

    expect(first).toEqual({
      operationId: '00000000-0000-4000-8000-000000000001',
      type: 'classifier.create',
      author: {
        userId: '00000000-0000-0000-0000-000000000001',
        externalSubject: 'seed-owner-local',
      },
      projectId,
      modelId,
      baseRevision: 4,
      targets: [],
      preconditions: [],
      payload: {
        id: '00000000-0000-4000-8000-000000000002',
        name: 'Customer',
        kind: 'CLASS',
        namespaceId: null,
      },
      origin: 'MANUAL',
      causalParentOperationId: null,
      idempotencyKey: '00000000-0000-4000-8000-000000000003',
    });

    const generatedIds = [
      first.operationId,
      first.payload.id,
      first.idempotencyKey,
      second.operationId,
      second.payload.id,
      second.idempotencyKey,
    ];
    expect(new Set(generatedIds).size).toBe(generatedIds.length);
    expect(second.operationId).not.toBe(first.operationId);
    expect(second.payload.id).not.toBe(first.payload.id);
  });
});

describe('typed API client', () => {
  it('surfaces an HTTP 200 REJECTED command as a rejection with its ErrorCode', async () => {
    const resultBody = {
      operationId: '44444444-4444-4444-8444-444444444444',
      status: 'REJECTED',
      resultRevision: null,
      diagnostics: [{
        code: 'PRECONDITION_FAILED',
        message: 'Classifier name is already in use.',
        scope: null,
        id: null,
        reason: null,
      }],
      authorizationClassification: null,
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(resultBody), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    const client = createApiClient('https://diagram.test', fetcher);
    const envelope = classifierCreateEnvelope(sequenceUuidFactory(
      '55555555-5555-4555-8555-555555555551',
      '55555555-5555-4555-8555-555555555552',
      '55555555-5555-4555-8555-555555555553',
    ));

    const outcome = await client.executeCommand(projectId, envelope);

    expect(outcome.kind).toBe('rejected');
    if (outcome.kind !== 'rejected') {
      throw new Error('Expected the command to be rejected.');
    }
    expect(outcome.result.status).toBe('REJECTED');
    expect(outcome.diagnostics[0]?.code).toBe('PRECONDITION_FAILED');
    expect(fetcher).toHaveBeenCalledWith(
      `https://diagram.test/projects/${projectId}/commands`,
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('surfaces the section 3.2 error body as a distinct transport failure', async () => {
    const errorBody = {
      code: 'PRECONDITION_FAILED',
      message: 'The model has an unresolved name collision.',
      details: [{ scope: 'name_collision', id: '66666666-6666-4666-8666-666666666666', reason: 'name.duplicate' }],
      operationId: '77777777-7777-4777-8777-777777777777',
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(errorBody), {
      status: 409,
      headers: { 'Content-Type': 'application/json' },
    }));
    const client = createApiClient('https://diagram.test', fetcher);
    const request = client.getProject(projectId);

    await expect(request).rejects.toBeInstanceOf(ApiTransportError);
    await expect(request).rejects.toMatchObject({
      kind: 'transport',
      code: 'PRECONDITION_FAILED',
      message: 'The model has an unresolved name collision.',
      details: errorBody.details,
      operationId: errorBody.operationId,
      status: 409,
    });
  });

  it('keeps the recorded live model response aligned with the resource types', () => {
    expect(recordedModel.projectId).toBe('b475cd3a-a203-4230-947a-1d42cd878ea2');
    expect(recordedModel.views[0]?.is_default).toBe(true);
    expect(recordedModel.views[0]?.element_geometry).toHaveLength(2);
    expect(recordedModel.views[0]?.connector_path).toHaveLength(2);
    expect(recordedModel.classifiers[0]?.attributes[0]?.type).toBe('String');
    expect(recordedModel.classifiers[0]?.operations[0]?.parameters).toEqual([]);
    expect(recordedModel.relationships[0]?.ends[1]?.aggregationKind).toBe('NONE');
  });
});

const liveBaseUrl = import.meta.env.VITE_API_BASE_URL;
describe.skipIf(!liveBaseUrl)('live backend client contract', () => {
  it('creates, reads, and commands a project through the typed client', async () => {
    const commandStatuses: number[] = [];
    const liveFetcher: typeof fetch = async (input, init) => {
      const response = await fetch(input, init);
      if (String(input).endsWith('/commands')) {
        commandStatuses.push(response.status);
      }
      return response;
    };
    const client = createApiClient(liveBaseUrl, liveFetcher);
    const project = await client.createProject({
      name: `T7 browser client verification ${new Date().toISOString()}`,
      description: 'Created by the opt-in web client integration test.',
    });

    expect(project.name).toContain('T7 browser client verification');
    expect((await client.listProjects()).some((item) => item.id === project.id)).toBe(true);
    expect(await client.getProject(project.id)).toEqual(project);

    const model = await client.getModel(project.id);
    expect(model.projectId).toBe(project.id);
    expect(model.views.some((view) => view.is_default)).toBe(true);

    const classifierCreate = buildOperationEnvelope({
      type: 'classifier.create',
      projectId: project.id,
      modelId: model.id,
      baseRevision: model.revision,
      payload: { name: 'Order', kind: 'CLASS', namespaceId: null },
    });
    expect(classifierCreate.operationId).not.toBe(classifierCreate.payload.id);
    if (import.meta.env.VITE_PRINT_SAMPLE_ENVELOPE === 'true') {
      console.log(JSON.stringify(classifierCreate, null, 2));
    }

    const accepted = await client.executeCommand(project.id, classifierCreate);
    expect(accepted.kind).toBe('accepted');
    const updatedModel = await client.getModel(project.id);
    expect(updatedModel.classifiers[0]?.id).toBe(classifierCreate.payload.id);

    const duplicate = await client.executeCommand(project.id, buildOperationEnvelope({
      type: 'classifier.create',
      projectId: project.id,
      modelId: updatedModel.id,
      baseRevision: updatedModel.revision,
      payload: { name: 'Order', kind: 'CLASS', namespaceId: null },
    }));
    expect(duplicate.kind).toBe('rejected');
    if (duplicate.kind !== 'rejected') {
      throw new Error('The duplicate classifier should be rejected by the live backend.');
    }
    expect(duplicate.diagnostics[0]?.code).toBe('PRECONDITION_FAILED');
    expect(commandStatuses).toEqual([200, 200]);
  });
});
