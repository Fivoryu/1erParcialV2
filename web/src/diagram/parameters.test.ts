import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ModelResource, OperationEnvelope } from '../api/types';
import PropertiesPanel from './PropertiesPanel';
import {
  applyOptimisticCommand,
  buildModelCommandEnvelope,
  buildParameterAddRequest,
  buildParameterRemoveRequest,
  buildParameterUpdateRequest,
  type ParameterUpdateValues,
} from './commands';
import { formatOperation } from './uml-format';

const ownerId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const accountId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const operationId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const accountParameterId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const retriesParameterId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';

const model: ModelResource = {
  id: '11111111-1111-4111-8111-111111111111',
  projectId: '22222222-2222-4222-8222-222222222222',
  revision: 8,
  views: [],
  classifiers: [
    {
      id: accountId,
      name: 'Account',
      kind: 'CLASS',
      isAbstract: false,
      namespaceId: null,
      attributes: [],
      operations: [],
      constraints: [],
    },
    {
      id: ownerId,
      name: 'Service',
      kind: 'CLASS',
      isAbstract: false,
      namespaceId: null,
      attributes: [],
      operations: [{
        id: operationId,
        name: 'confirm',
        visibility: 'PUBLIC',
        isAbstract: false,
        isStatic: false,
        returnTypeReferenceId: null,
        returnType: 'boolean',
        ordinal: 0,
        parameters: [
          {
            id: retriesParameterId,
            name: 'retries',
            direction: 'OUT',
            typeReferenceId: null,
            type: 'Integer',
            ordinal: 1,
          },
          {
            id: accountParameterId,
            name: 'account',
            direction: 'IN',
            typeReferenceId: null,
            type: 'Account',
            ordinal: 0,
          },
        ],
      }],
      constraints: [],
    },
  ],
  relationships: [],
};

function sequenceUuidFactory(...values: string[]) {
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

function parameterEnvelope(payload: ParameterUpdateValues): OperationEnvelope {
  return buildModelCommandEnvelope(model, buildParameterUpdateRequest(retriesParameterId, payload), sequenceUuidFactory(
    '99999999-9999-4999-8999-999999999991',
    '99999999-9999-4999-8999-999999999992',
    '99999999-9999-4999-8999-999999999993',
  ));
}

describe('operation parameters', () => {
  it('formats a two-parameter signature in ordinal order', () => {
    const operation = model.classifiers.find((classifier) => classifier.id === ownerId)?.operations[0];
    expect(operation).toBeDefined();
    expect(formatOperation(operation!)).toBe('+ confirm(account: Account, retries: Integer): boolean');
  });

  it('builds an add request with a client-generated parameter id', () => {
    const request = buildParameterAddRequest(operationId, {
      name: 'token',
      type: { primitiveName: 'String' },
      direction: 'IN',
      ordinal: 2,
    });
    const envelope = buildModelCommandEnvelope(model, request, sequenceUuidFactory(
      '99999999-9999-4999-8999-999999999991',
      '99999999-9999-4999-8999-999999999992',
      '99999999-9999-4999-8999-999999999993',
    ));

    expect(envelope.type).toBe('parameter.add');
    expect(envelope.targets).toEqual([{ scope: 'operation', id: operationId }]);
    expect(envelope.payload).toEqual({
      name: 'token',
      type: { primitiveName: 'String' },
      direction: 'IN',
      ordinal: 2,
      id: '99999999-9999-4999-8999-999999999992',
    });
    expect(envelope.payload.id).not.toBe(envelope.operationId);
  });

  it('adds and removes parameters in the rendered signature optimistically', () => {
    const addEnvelope = buildModelCommandEnvelope(model, buildParameterAddRequest(operationId, {
      name: 'token',
      type: { primitiveName: 'String' },
      direction: 'IN',
      ordinal: 2,
    }), sequenceUuidFactory(
      '99999999-9999-4999-8999-999999999991',
      '99999999-9999-4999-8999-999999999992',
      '99999999-9999-4999-8999-999999999993',
    ));
    const withAddedParameter = applyOptimisticCommand(model, addEnvelope);
    const operationWithAddition = withAddedParameter.classifiers.find((classifier) => classifier.id === ownerId)
      ?.operations[0];
    expect(operationWithAddition && formatOperation(operationWithAddition))
      .toBe('+ confirm(account: Account, retries: Integer, token: String): boolean');

    const removeEnvelope = buildModelCommandEnvelope(model, buildParameterRemoveRequest(retriesParameterId),
      sequenceUuidFactory(
        '99999999-9999-4999-8999-999999999991',
        '99999999-9999-4999-8999-999999999992',
        '99999999-9999-4999-8999-999999999993',
      ));
    const withoutRemovedParameter = applyOptimisticCommand(model, removeEnvelope);
    const operationWithoutRemoval = withoutRemovedParameter.classifiers.find((classifier) => classifier.id === ownerId)
      ?.operations[0];
    expect(operationWithoutRemoval && formatOperation(operationWithoutRemoval))
      .toBe('+ confirm(account: Account): boolean');
  });

  it('builds exact update payloads for rename, retype, and reorder', () => {
    const rename = buildParameterUpdateRequest(accountParameterId, { name: 'customer' });
    const retype = buildParameterUpdateRequest(accountParameterId, { type: { classifierId: accountId } });
    const reorder = buildParameterUpdateRequest(retriesParameterId, { ordinal: 0 });

    expect(rename).toEqual({
      type: 'parameter.update',
      targets: [{ scope: 'parameter', id: accountParameterId }],
      payload: { name: 'customer' },
    });
    expect(retype).toEqual({
      type: 'parameter.update',
      targets: [{ scope: 'parameter', id: accountParameterId }],
      payload: { type: { classifierId: accountId } },
    });
    expect(reorder).toEqual({
      type: 'parameter.update',
      targets: [{ scope: 'parameter', id: retriesParameterId }],
      payload: { ordinal: 0 },
    });
  });

  it('builds a remove command with no payload fields', () => {
    expect(buildParameterRemoveRequest(retriesParameterId)).toEqual({
      type: 'parameter.remove',
      targets: [{ scope: 'parameter', id: retriesParameterId }],
      payload: {},
    });
  });

  it('updates the rendered signature optimistically before a page reload', () => {
    const updated = applyOptimisticCommand(model, parameterEnvelope({
      name: 'code',
      type: { primitiveName: 'String' },
      ordinal: 0,
    }));
    const operation = updated.classifiers.find((classifier) => classifier.id === ownerId)?.operations[0];

    expect(operation).toBeDefined();
    expect(formatOperation(operation!)).toBe('+ confirm(code: String, account: Account): boolean');
    expect(operation?.parameters.map((parameter) => parameter.ordinal)).toEqual([0, 1]);
  });

  it('shows editable parameter controls only inside the selected operation panel', () => {
    const markup = renderToStaticMarkup(createElement(PropertiesPanel, {
      model,
      selection: { kind: 'operation', classifierId: ownerId, elementId: operationId },
      onCommand: () => null,
      onDelete: () => undefined,
      onSelect: () => undefined,
    }));

    expect(markup).toContain('Parameters');
    expect(markup).toContain('Add parameter');
    expect(markup).toContain('Move up');
    expect(markup).toContain('Move down');
    expect(markup).toContain('Remove parameter');
    expect(markup).toContain('Save parameter');
    expect(markup).toContain('Direction');
    expect(markup).toContain('>In</option>');
    expect(markup).toContain('>Out</option>');
    expect(markup).toContain('>Inout</option>');
    expect(markup).not.toContain('INOUT</option>');
    expect(markup).not.toContain('kind="parameter"');
  });
});
