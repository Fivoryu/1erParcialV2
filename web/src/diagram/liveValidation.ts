/**
 * Turns the model validation report into the short findings of the live validation strip:
 * one line per rule (a cycle through three classes is one finding, not three), named with the
 * model's own element names, plus the redundant-relationship check, which only the editor makes.
 */
import type { ModelResource, ValidationDiagnosticResource } from '../api/types';

export interface ValidationFinding {
  key: string;
  text: string;
}

export interface ValidationSummary {
  errors: ValidationFinding[];
  warnings: ValidationFinding[];
}

const RULE_TITLES: Record<string, string> = {
  GENERALIZATION_CYCLE: 'Herencia circular',
  SELF_GENERALIZATION: 'Una clase hereda de sí misma',
  DUPLICATE_ROLE_NAME: 'Rol repetido',
  INVALID_GENERALIZATION_MULTIPLICITY: 'Multiplicidad en una generalización',
  INCOMPLETE_MULTIPLICITY_SPECIFICATION: 'Multiplicidad incompleta',
  DUPLICATE_MEMBER_NAME: 'Miembro duplicado',
  UNRESOLVED_TYPE: 'Tipo desconocido',
  UNSUPPORTED_MULTIPLICITY_COMBINATION: 'Multiplicidad que el generador no admite',
  MANDATORY_REFERENCE_CYCLE: 'Ciclo de referencias obligatorias',
  COMPOSITION_CYCLE: 'Composición circular',
};

const KIND_PLURALS: Record<string, string> = {
  ASSOCIATION: 'asociaciones',
  AGGREGATION: 'agregaciones',
  COMPOSITION: 'composiciones',
  GENERALIZATION: 'generalizaciones',
  INTERFACE_REALIZATION: 'realizaciones de interfaz',
  DEPENDENCY: 'dependencias',
};

function elementNames(model: ModelResource): Map<string, string> {
  const names = new Map<string, string>();
  for (const classifier of model.classifiers) {
    names.set(classifier.id, classifier.name);
    for (const member of [...classifier.attributes, ...classifier.operations]) {
      names.set(member.id, `${classifier.name}.${member.name}`);
    }
  }
  for (const relationship of model.relationships) {
    const ends = relationship.ends.map((end) => names.get(end.classifierId) ?? '?');
    names.set(relationship.id, relationship.name ?? ends.join(' – '));
  }
  return names;
}

function redundantRelationships(model: ModelResource, names: Map<string, string>): ValidationFinding[] {
  const groups = new Map<string, { kind: string; ends: string[]; count: number }>();
  for (const relationship of model.relationships) {
    const ends = [...relationship.ends].sort((left, right) => left.ordinal - right.ordinal).map((end) => end.classifierId);
    // An association has no direction; every other kind does.
    const key = JSON.stringify([relationship.kind, ...(relationship.kind === 'ASSOCIATION' ? [...ends].sort() : ends)]);
    const group = groups.get(key);
    if (group) {
      group.count += 1;
    } else {
      groups.set(key, { kind: relationship.kind, ends, count: 1 });
    }
  }
  return [...groups.entries()]
    .filter(([, group]) => group.count > 1)
    .map(([key, group]) => ({
      key: `REDUNDANT:${key}`,
      text: `Posible relación redundante: ${group.count} ${KIND_PLURALS[group.kind] ?? 'relaciones'} entre `
        + group.ends.map((id) => names.get(id) ?? '?').join(' y '),
    }));
}

function grouped(diagnostics: ValidationDiagnosticResource[], names: Map<string, string>): ValidationFinding[] {
  const byRule = new Map<string, ValidationDiagnosticResource[]>();
  const findings: ValidationFinding[] = [];
  for (const diagnostic of diagnostics) {
    if (!RULE_TITLES[diagnostic.code]) {
      findings.push({ key: `${diagnostic.code}:${diagnostic.elementId ?? findings.length}`, text: diagnostic.message });
      continue;
    }
    byRule.set(diagnostic.code, [...(byRule.get(diagnostic.code) ?? []), diagnostic]);
  }
  const known = [...byRule.entries()].map(([code, entries]) => {
    const involved = [...new Set(entries.map((entry) => (entry.elementId ? names.get(entry.elementId) : undefined))
      .filter((name): name is string => Boolean(name)))];
    return { key: code, text: involved.length ? `${RULE_TITLES[code]}: ${involved.join(', ')}` : RULE_TITLES[code]! };
  });
  return [...known, ...findings];
}

export function summarizeValidation(model: ModelResource, diagnostics: ValidationDiagnosticResource[]): ValidationSummary {
  const names = elementNames(model);
  return {
    errors: grouped(diagnostics.filter((entry) => entry.severity === 'ERROR'), names),
    warnings: [
      ...grouped(diagnostics.filter((entry) => entry.severity !== 'ERROR'), names),
      ...redundantRelationships(model, names),
    ],
  };
}
