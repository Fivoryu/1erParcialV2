import type {
  GenerationReadinessDiagnosticResource,
  GenerationReadinessResource,
  ModelValidationResource,
  ValidationDiagnosticResource,
  ValidationSeverity,
} from '../api/types';
import { createDiagramNames, type DiagramNames } from '../assistant/assistantNames';
import type { ModelResource } from '../api/types';

export interface ReviewFinding {
  code: string;
  severity: ValidationSeverity;
  elementName: string;
  message: string;
}

export interface ReviewFindings {
  errors: ReviewFinding[];
  warnings: ReviewFinding[];
  readinessBlockers: ReviewFinding[];
  profile: string;
  ready: boolean;
}

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
const UUID_TEST_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

function withoutUuids(value: string): string {
  return value.replace(UUID_PATTERN, 'el elemento del diagrama');
}

function humanText(value: string | null | undefined, fallback: string): string {
  if (typeof value !== 'string') {
    return fallback;
  }
  const cleaned = withoutUuids(value).trim();
  return cleaned.length > 0 ? cleaned : fallback;
}

function findingFromDiagnostic(
  diagnostic: ValidationDiagnosticResource | GenerationReadinessDiagnosticResource,
  names: DiagramNames,
): ReviewFinding {
  const code = UUID_TEST_PATTERN.test(diagnostic.code)
    ? 'REVIEW_DIAGNOSTIC'
    : humanText(diagnostic.code, 'REVIEW_DIAGNOSTIC');
  return {
    code,
    severity: diagnostic.severity,
    elementName: resolveReviewElementName(diagnostic.elementId, names),
    message: humanText(diagnostic.message, 'No additional details were provided.'),
  };
}

function displayName(value: string | null | undefined): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const name = withoutUuids(value).trim();
  return name.length > 0 && name !== value.trim() ? null : name.length > 0 ? name : null;
}

/** Resolve only through names in the current model; missing ids are never echoed. */
export function resolveReviewElementName(elementId: string | null, names: DiagramNames): string {
  if (elementId === null) {
    return 'Whole diagram';
  }

  const classifierName = displayName(names.classifiers.get(elementId));
  if (classifierName) {
    return classifierName;
  }

  const attribute = names.attributes.get(elementId);
  if (attribute) {
    const name = displayName(attribute.name);
    const ownerName = displayName(attribute.ownerName);
    if (name && ownerName) {
      return `${name} (attribute of ${ownerName})`;
    }
    if (name) {
      return `${name} (attribute)`;
    }
  }

  const operation = names.operations.get(elementId);
  if (operation) {
    const name = displayName(operation.name);
    const ownerName = displayName(operation.ownerName);
    if (name && ownerName) {
      return `${name}() (operation of ${ownerName})`;
    }
    if (name) {
      return `${name}() (operation)`;
    }
  }

  const relationship = names.relationships.get(elementId);
  if (relationship) {
    const sourceName = displayName(relationship.sourceName);
    const targetName = displayName(relationship.targetName);
    const kind = humanText(relationship.kind.replaceAll('_', ' ').toLowerCase(), 'relationship');
    if (sourceName && targetName) {
      return `${kind} relationship between ${sourceName} and ${targetName}`;
    }
    return `${kind} relationship in diagram`;
  }

  return 'Unidentified diagram element';
}

function groupValidationFindings(
  diagnostics: ValidationDiagnosticResource[],
  names: DiagramNames,
): Pick<ReviewFindings, 'errors' | 'warnings'> {
  const errors: ReviewFinding[] = [];
  const warnings: ReviewFinding[] = [];

  for (const diagnostic of diagnostics) {
    const finding = findingFromDiagnostic(diagnostic, names);
    if (diagnostic.severity === 'ERROR') {
      errors.push(finding);
    } else if (diagnostic.severity === 'WARNING') {
      warnings.push(finding);
    }
  }

  return { errors, warnings };
}

function readinessFindings(
  diagnostics: GenerationReadinessDiagnosticResource[],
  names: DiagramNames,
): ReviewFinding[] {
  return diagnostics
    .filter((diagnostic) => diagnostic.blocking)
    .map((diagnostic) => findingFromDiagnostic(diagnostic, names));
}

/** Convert raw API diagnostics to human-named, UUID-free display and prompt data. */
export function buildReviewFindings(
  validation: ModelValidationResource,
  readiness: GenerationReadinessResource,
  model: ModelResource,
): ReviewFindings {
  const names = createDiagramNames(model);
  return {
    ...groupValidationFindings(validation.diagnostics, names),
    readinessBlockers: readinessFindings(readiness.diagnostics, names),
    profile: humanText(readiness.profile, 'Generation profile'),
    ready: readiness.ready,
  };
}

export type ReviewPromptIntent = 'explain' | 'propose-fix';

/** Build an explicit Spanish instruction from the safe, user-facing review findings. */
export function buildReviewPrompt(findings: ReviewFindings, intent: ReviewPromptIntent): string {
  const instruction = intent === 'explain'
    ? 'Explicá en español qué significan estos hallazgos y cómo afectan el modelo y la generación. No propongas ni apliques cambios todavía.'
    : 'Analizá estos hallazgos y proponé un plan mínimo para corregirlos. No apliques cambios: presentá primero el plan para que yo lo revise y lo confirmaré explícitamente.';
  const validationLines = [
    ...findings.errors.map((finding) => formatFinding(finding, 'ERROR')),
    ...findings.warnings.map((finding) => formatFinding(finding, 'WARNING')),
  ];
  const blockerLines = findings.readinessBlockers.map((finding) => formatFinding(finding, 'BLOCKER'));
  const noValidationFindings = validationLines.length === 0
    ? '- No se detectaron errores ni advertencias de validación UML.'
    : validationLines.join('\n');
  const noBlockers = blockerLines.length === 0
    ? '- No se proporcionaron diagnósticos bloqueantes.'
    : blockerLines.join('\n');

  return withoutUuids([
    instruction,
    '',
    'Validación UML:',
    noValidationFindings,
    '',
    `Preparación del backend — perfil ${humanText(findings.profile, 'Generation profile')}: ${findings.ready ? 'listo' : 'no listo'}.`,
    noBlockers,
  ].join('\n'));
}

function formatFinding(finding: ReviewFinding, label: string): string {
  return `- ${label} — ${humanText(finding.code, 'REVIEW_DIAGNOSTIC')} — Elemento: ${humanText(finding.elementName, 'Unidentified diagram element')} — ${humanText(finding.message, 'No additional details were provided.')}`;
}
