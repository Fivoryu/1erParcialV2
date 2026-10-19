import { describe, expect, it } from 'vitest';
import type { AssistantPlanOperationResource } from '../api/assistant';
import type { ModelResource } from '../api/types';
import { createDiagramNames, describeAssistantOperation } from './assistantNames';

const PRODUCT_ID = '11111111-1111-4111-8111-111111111111';
const SUPPLIER_ID = '22222222-2222-4222-8222-222222222222';
const ATTRIBUTE_ID = '33333333-3333-4333-8333-333333333333';
const OPERATION_ID = '44444444-4444-4444-8444-444444444444';
const RELATIONSHIP_ID = '55555555-5555-4555-8555-555555555555';
const UUID_PATTERN = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i;

const model = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  revision: 7,
  versionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  views: [],
  classifiers: [
    {
      id: PRODUCT_ID,
      name: 'Producto',
      kind: 'CLASS',
      isAbstract: false,
      namespaceId: null,
      attributes: [{
        id: ATTRIBUTE_ID,
        name: 'nombre',
        kind: 'ATTRIBUTE',
        visibility: 'PUBLIC',
        multiplicity: '1',
        defaultValue: null,
        typeReferenceId: null,
        type: 'String',
        ordinal: 0,
      }],
      operations: [{
        id: OPERATION_ID,
        name: 'guardar',
        visibility: 'PUBLIC',
        isAbstract: false,
        isStatic: false,
        returnTypeReferenceId: null,
        returnType: 'void',
        ordinal: 0,
        parameters: [],
      }],
      constraints: [],
    },
    {
      id: SUPPLIER_ID,
      name: 'Proveedor',
      kind: 'CLASS',
      isAbstract: false,
      namespaceId: null,
      attributes: [],
      operations: [],
      constraints: [],
    },
  ],
  relationships: [{
    id: RELATIONSHIP_ID,
    kind: 'ASSOCIATION',
    name: null,
    ends: [
      {
        id: '66666666-6666-4666-8666-666666666666',
        classifierId: PRODUCT_ID,
        role: null,
        multiplicity: '1',
        navigability: 'UNSPECIFIED',
        aggregationKind: 'NONE',
        ordinal: 0,
      },
      {
        id: '77777777-7777-4777-8777-777777777777',
        classifierId: SUPPLIER_ID,
        role: null,
        multiplicity: '1',
        navigability: 'UNSPECIFIED',
        aggregationKind: 'NONE',
        ordinal: 1,
      },
    ],
    constraints: [],
  }],
} as ModelResource;

function operation(
  type: string,
  targetId: string | null = null,
  args: Record<string, unknown> = {},
): AssistantPlanOperationResource {
  return { type, targetId, args };
}

describe('createDiagramNames', () => {
  it('indexes classifiers, attributes and operations with their owning classifier, plus relationship ends and kind', () => {
    const names = createDiagramNames(model);

    expect(names.classifiers.get(PRODUCT_ID)).toBe('Producto');
    expect(names.attributes.get(ATTRIBUTE_ID)).toEqual({
      name: 'nombre',
      ownerId: PRODUCT_ID,
      ownerName: 'Producto',
      type: 'String',
    });
    expect(names.operations.get(OPERATION_ID)).toEqual({
      name: 'guardar',
      ownerId: PRODUCT_ID,
      ownerName: 'Producto',
    });
    expect(names.relationships.get(RELATIONSHIP_ID)).toEqual({
      sourceName: 'Producto',
      targetName: 'Proveedor',
      kind: 'ASSOCIATION',
    });
  });
});

describe('describeAssistantOperation', () => {
  const names = createDiagramNames(model);

  it('uses natural Spanish names, operation arguments and relationship kind', () => {
    expect(describeAssistantOperation(operation('classifier.create', null, { name: 'Producto' }), names))
      .toBe('Crear clase "Producto"');
    expect(describeAssistantOperation(operation('classifier.rename', PRODUCT_ID, { name: 'Artículo' }), names))
      .toBe('Renombrar clase "Producto" a "Artículo"');
    expect(describeAssistantOperation(operation('classifier.delete', PRODUCT_ID), names))
      .toBe('Eliminar clase "Producto"');
    expect(describeAssistantOperation(operation('attribute.add', PRODUCT_ID, { name: 'nombre', type: 'String' }), names))
      .toBe('Agregar atributo "nombre: String" a "Producto"');
    expect(describeAssistantOperation(operation('attribute.update', ATTRIBUTE_ID), names))
      .toBe('Actualizar atributo "nombre" de "Producto"');
    expect(describeAssistantOperation(operation('attribute.remove', ATTRIBUTE_ID), names))
      .toBe('Eliminar atributo "nombre" de "Producto"');
    expect(describeAssistantOperation(operation('operation.add', PRODUCT_ID, { name: 'guardar' }), names))
      .toBe('Agregar operación "guardar" a "Producto"');
    expect(describeAssistantOperation(operation('relationship.create', null, {
      source: { id: PRODUCT_ID },
      target: { name: 'Proveedor' },
      kind: 'ASSOCIATION',
    }), names)).toBe('Crear relación de Asociación "Producto" → "Proveedor"');
    expect(describeAssistantOperation(operation('relationship.update', RELATIONSHIP_ID), names))
      .toBe('Actualizar relación de Asociación "Producto" → "Proveedor"');
    expect(describeAssistantOperation(operation('relationship.delete', RELATIONSHIP_ID), names))
      .toBe('Eliminar relación de Asociación "Producto" → "Proveedor"');
  });

  it('resolves names that belong to classifiers created earlier in the same plan', () => {
    expect(describeAssistantOperation(operation('attribute.add', null, {
      name: 'nombre',
      type: 'String',
      targetClassifierName: 'Producto',
    }), names)).toBe('Agregar atributo "nombre: String" a "Producto"');
    expect(describeAssistantOperation(operation('operation.add', null, {
      name: 'guardar',
      targetClassifierName: 'Proveedor',
    }), names)).toBe('Agregar operación "guardar" a "Proveedor"');
    expect(describeAssistantOperation(operation('relationship.create', null, {
      source: { classifierName: 'Producto' },
      target: { classifierName: 'Proveedor' },
      kind: 'ASSOCIATION',
    }), names)).toBe('Crear relación de Asociación "Producto" → "Proveedor"');
  });

  it('uses Spanish relationship-kind words when the kind is available', () => {
    const cases = [
      ['COMPOSITION', 'Composición'],
      ['AGGREGATION', 'Agregación'],
      ['ASSOCIATION', 'Asociación'],
    ] as const;

    for (const [kind, spanishKind] of cases) {
      expect(describeAssistantOperation(operation('relationship.create', null, {
        source: PRODUCT_ID,
        target: SUPPLIER_ID,
        kind,
      }), names)).toContain(`relación de ${spanishKind}`);
    }
  });

  it('uses human fallbacks when an id or name cannot be resolved', () => {
    const emptyNames = createDiagramNames({
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      revision: 0,
      versionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      views: [],
      classifiers: [],
      relationships: [],
    });
    const unresolvedId = '88888888-8888-4888-8888-888888888888';

    expect(describeAssistantOperation(operation('classifier.create'), emptyNames)).toBe('Crear clase');
    expect(describeAssistantOperation(operation('attribute.add', unresolvedId, { name: 'nombre' }), emptyNames))
      .toBe('Agregar atributo "nombre" a la clase seleccionada');
    expect(describeAssistantOperation(operation('relationship.delete', unresolvedId), emptyNames))
      .toBe('Eliminar la relación seleccionada');
    expect(describeAssistantOperation(operation('relationship.create', null, {
      source: unresolvedId,
      target: unresolvedId,
    }), emptyNames)).toBe('Crear relación entre clases seleccionadas');
  });

  it('never emits UUIDs or generic placeholders for any assistant operation type', () => {
    const emptyNames = createDiagramNames({
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      revision: 0,
      versionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      views: [],
      classifiers: [],
      relationships: [],
    });
    const unresolvedId = '99999999-9999-4999-8999-999999999999';
    const types = [
      'classifier.create',
      'classifier.rename',
      'classifier.delete',
      'classifier.setAbstract',
      'attribute.add',
      'attribute.update',
      'attribute.remove',
      'operation.add',
      'operation.update',
      'operation.remove',
      'relationship.create',
      'relationship.update',
      'relationship.delete',
      'unknown.operation',
    ];

    const descriptions = types.map((type) => describeAssistantOperation(operation(type, unresolvedId, {
      name: unresolvedId,
      source: unresolvedId,
      target: unresolvedId,
      type: unresolvedId,
      kind: unresolvedId,
    }), emptyNames));

    expect(descriptions).toHaveLength(types.length);
    for (const description of descriptions) {
      expect(description).not.toMatch(UUID_PATTERN);
      expect(description.toLowerCase()).not.toContain('elemento nuevo');
      expect(description).not.toMatch(/destino\s+[0-9a-f-]{36}/i);
    }
  });
});
