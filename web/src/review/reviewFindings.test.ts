import { describe, expect, it } from 'vitest';
import type { GenerationReadinessResource, ModelResource, ModelValidationResource } from '../api/types';
import { buildReviewFindings, buildReviewPrompt, resolveReviewElementName } from './reviewFindings';

const CLASS_ID = '11111111-1111-4111-8111-111111111111';
const ATTRIBUTE_ID = '22222222-2222-4222-8222-222222222222';
const OPERATION_ID = '33333333-3333-4333-8333-333333333333';
const RELATIONSHIP_ID = '44444444-4444-4444-8444-444444444444';
const UNKNOWN_ID = '55555555-5555-4555-8555-555555555555';

const model: ModelResource = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  revision: 3,
  versionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  views: [],
  classifiers: [{
    id: CLASS_ID,
    name: 'Pedido',
    kind: 'CLASS',
    isAbstract: false,
    namespaceId: null,
    attributes: [{
      id: ATTRIBUTE_ID,
      name: 'total',
      kind: 'ATTRIBUTE',
      visibility: 'PUBLIC',
      multiplicity: '1',
      defaultValue: null,
      typeReferenceId: null,
      type: 'Decimal',
      ordinal: 0,
    }],
    operations: [{
      id: OPERATION_ID,
      name: 'calcularTotal',
      visibility: 'PUBLIC',
      isAbstract: false,
      isStatic: false,
      returnTypeReferenceId: null,
      returnType: 'Decimal',
      ordinal: 0,
      parameters: [],
    }],
    constraints: [],
  }, {
    id: '66666666-6666-4666-8666-666666666666',
    name: 'Cliente',
    kind: 'CLASS',
    isAbstract: false,
    namespaceId: null,
    attributes: [],
    operations: [],
    constraints: [],
  }],
  relationships: [{
    id: RELATIONSHIP_ID,
    kind: 'ASSOCIATION',
    name: null,
    ends: [
      { id: '77777777-7777-4777-8777-777777777777', classifierId: CLASS_ID, role: null, multiplicity: '1', navigability: 'NONE', aggregationKind: 'NONE', ordinal: 0 },
      { id: '88888888-8888-4888-8888-888888888888', classifierId: '66666666-6666-4666-8666-666666666666', role: null, multiplicity: '0..*', navigability: 'NONE', aggregationKind: 'NONE', ordinal: 1 },
    ],
    constraints: [],
  }],
};

const validation: ModelValidationResource = {
  diagnostics: [
    { code: 'GENERALIZATION_CYCLE', severity: 'ERROR', elementId: CLASS_ID, feature: null, message: 'Class participates in a generalization cycle.' },
    { code: 'MEMBER_DUPLICATE', severity: 'WARNING', elementId: ATTRIBUTE_ID, feature: 'name', message: 'Duplicate attribute.' },
    { code: 'OPERATION_INVALID', severity: 'ERROR', elementId: OPERATION_ID, feature: null, message: 'Invalid operation declaration.' },
    { code: 'ROLE_DUPLICATE', severity: 'WARNING', elementId: RELATIONSHIP_ID, feature: 'role', message: 'Duplicate relationship role.' },
    { code: 'UNKNOWN_ELEMENT', severity: 'ERROR', elementId: UNKNOWN_ID, feature: null, message: `Unresolved reference ${UNKNOWN_ID}.` },
  ],
};

const readiness: GenerationReadinessResource = {
  versionId: model.versionId,
  profile: 'Spring Boot PostgreSQL V1',
  ready: false,
  diagnostics: [
    { code: 'UNSUPPORTED_TYPE', severity: 'ERROR', elementId: ATTRIBUTE_ID, feature: 'type', message: 'The type is unsupported.', blocking: true },
    { code: 'NON_BLOCKING_NOTE', severity: 'WARNING', elementId: null, feature: null, message: 'Informational preflight note.', blocking: false },
  ],
};

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

describe('review findings', () => {
  it('groups UML errors and warnings and resolves each supported element kind by model name', () => {
    const findings = buildReviewFindings(validation, readiness, model);

    expect(findings.errors.map(({ code, elementName }) => [code, elementName])).toEqual([
      ['GENERALIZATION_CYCLE', 'Pedido'],
      ['OPERATION_INVALID', 'calcularTotal() (operación de Pedido)'],
      ['UNKNOWN_ELEMENT', 'Elemento del diagrama sin identificar'],
    ]);
    expect(findings.warnings.map(({ code, elementName }) => [code, elementName])).toEqual([
      ['MEMBER_DUPLICATE', 'total (atributo de Pedido)'],
      ['ROLE_DUPLICATE', 'Relación de tipo asociación entre Pedido y Cliente'],
    ]);
    expect(findings.readinessBlockers.map(({ code, elementName }) => [code, elementName])).toEqual([
      ['UNSUPPORTED_TYPE', 'total (atributo de Pedido)'],
    ]);
    expect(findings.profile).toBe('Spring Boot PostgreSQL V1');
    expect(findings.ready).toBe(false);
  });

  it('uses human fallbacks for null and unresolved ids instead of echoing identifiers', () => {
    const findings = buildReviewFindings({
      diagnostics: [
        { code: 'DIAGRAM_CYCLE', severity: 'ERROR', elementId: null, feature: null, message: 'Cycle found.' },
        { code: 'UNKNOWN_ELEMENT', severity: 'WARNING', elementId: UNKNOWN_ID, feature: null, message: 'Missing element.' },
      ],
    }, readiness, model);

    expect(findings.errors[0]?.elementName).toBe('Diagrama completo');
    expect(findings.warnings[0]?.elementName).toBe('Elemento del diagrama sin identificar');
    expect(resolveReviewElementName(UNKNOWN_ID, { classifiers: new Map(), attributes: new Map(), operations: new Map(), relationships: new Map() }))
      .toBe('Elemento del diagrama sin identificar');
    expect(JSON.stringify(findings)).not.toMatch(UUID_PATTERN);
  });

  it('builds explicit Spanish prompts from safe names and excludes every UUID', () => {
    const findings = buildReviewFindings(validation, readiness, model);
    const explanation = buildReviewPrompt(findings, 'explain');
    const proposedFix = buildReviewPrompt(findings, 'propose-fix');

    expect(explanation).toContain('Explicá en español');
    expect(explanation).toContain('GENERALIZATION_CYCLE — Elemento: Pedido');
    expect(explanation).toContain('total (atributo de Pedido)');
    expect(explanation).toContain('Spring Boot PostgreSQL V1');
    expect(proposedFix).toContain('proponé un plan mínimo');
    expect(proposedFix).toContain('No apliques cambios');
    expect(explanation).not.toMatch(UUID_PATTERN);
    expect(proposedFix).not.toMatch(UUID_PATTERN);
    expect(proposedFix).toContain('el elemento del diagrama');
  });
});
