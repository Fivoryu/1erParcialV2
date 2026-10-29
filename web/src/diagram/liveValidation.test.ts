import { describe, expect, it } from 'vitest';
import type { ModelResource, RelationshipResource, ValidationDiagnosticResource } from '../api/types';
import { summarizeValidation } from './liveValidation';

function relationship(id: string, kind: string, sourceId: string, targetId: string): RelationshipResource {
  return {
    id,
    kind,
    name: null,
    ends: [
      { id: `${id}-1`, classifierId: sourceId, role: null, multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 1 },
      { id: `${id}-2`, classifierId: targetId, role: null, multiplicity: 'EXACTLY_ONE', navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal: 2 },
    ],
    constraints: [],
  };
}

const model: ModelResource = {
  id: 'm',
  projectId: 'p',
  revision: 3,
  versionId: 'v',
  views: [],
  classifiers: ['Book', 'Copy', 'Loan'].map((name) => ({
    id: name.toLowerCase(),
    name,
    kind: 'CLASS',
    isAbstract: false,
    namespaceId: null,
    attributes: name === 'Book' ? [{ id: 'isbn', name: 'isbn', kind: 'ATTRIBUTE', visibility: 'PRIVATE', multiplicity: 'EXACTLY_ONE', defaultValue: null, typeReferenceId: null, type: 'Isbn', ordinal: 0 }] : [],
    operations: [],
    constraints: [],
  })),
  relationships: [
    relationship('r1', 'ASSOCIATION', 'book', 'copy'),
    relationship('r2', 'ASSOCIATION', 'copy', 'book'),
    relationship('r3', 'GENERALIZATION', 'book', 'loan'),
    relationship('r4', 'GENERALIZATION', 'loan', 'book'),
  ],
};

function diagnostic(code: string, severity: 'ERROR' | 'WARNING', elementId: string | null, message = 'server message'): ValidationDiagnosticResource {
  return { code, severity, elementId, feature: null, message };
}

describe('summarizeValidation', () => {
  it('groups one finding per rule, naming every element involved, in Spanish', () => {
    const summary = summarizeValidation(model, [
      diagnostic('GENERALIZATION_CYCLE', 'ERROR', 'book'),
      diagnostic('GENERALIZATION_CYCLE', 'ERROR', 'loan'),
      diagnostic('UNRESOLVED_TYPE', 'ERROR', 'isbn'),
    ]);

    expect(summary.errors.map((finding) => finding.text)).toEqual([
      'Herencia circular: Book, Loan',
      'Tipo desconocido: Book.isbn',
    ]);
  });

  it('warns about relationships of the same kind repeated between the same classes', () => {
    const summary = summarizeValidation(model, []);

    // An association is the same in both directions; a generalization is not.
    expect(summary.warnings.map((finding) => finding.text)).toEqual([
      'Posible relación redundante: 2 asociaciones entre Book y Copy',
    ]);
  });

  it('falls back to the server message for a rule it does not know', () => {
    const summary = summarizeValidation(model, [diagnostic('NEW_RULE', 'WARNING', null, 'Something new.')]);

    expect(summary.warnings.map((finding) => finding.text)).toContain('Something new.');
  });

  it('keeps finding keys unique when one element repeats an unknown code', () => {
    const summary = summarizeValidation(model, [
      diagnostic('NEW_RULE', 'ERROR', 'book', 'First.'),
      diagnostic('NEW_RULE', 'ERROR', 'book', 'Second.'),
    ]);

    const keys = summary.errors.map((finding) => finding.key);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('counts nothing on a clean model', () => {
    const clean = { ...model, relationships: [] };

    expect(summarizeValidation(clean, [])).toEqual({ errors: [], warnings: [] });
  });
});
