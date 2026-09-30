import type {
  GenerationDiagnosticResource,
  GenerationRunResource,
  GenerationState,
  GenerationTargetResource,
} from '../api/types';

/**
 * Product language for the generation panel.
 *
 * The panel never echoes a backend diagnostic message: those messages name type references and
 * classifiers by their internal identifiers, and the editor must not show one. Every sentence here
 * is rebuilt from the diagnostic `code` and its named `feature`, so a product label can never carry
 * an identifier through by accident.
 */

const FEATURE_LABELS: Record<string, string> = {
  GENERALIZATION: 'Generalization (inheritance)',
  REALIZATION: 'Realization (interface implementation)',
  DEPENDENCY: 'Dependency',
  ASSOCIATION: 'Association',
  AGGREGATION: 'Aggregation',
  COMPOSITION: 'Composition',
  multiplicity: 'Multiplicity',
  relationshipKind: 'Relationship kind',
  relationshipEnds: 'Relationship ends',
  classifierKind: 'Classifier kind',
  namespace: 'Package placement',
  visibility: 'Visibility',
  primitiveTypeMappings: 'Primitive type mapping',
};

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

const DIAGNOSTIC_DETAILS: Record<string, (feature: string) => string> = {
  UNSUPPORTED_UML_FEATURE:
    (feature) => `The generator does not support ${feature} yet. Remove or replace it, then generate again.`,
  UNSUPPORTED_MULTIPLICITY:
    (feature) => `The generator does not support this ${feature} on an attribute yet.`,
  UNSUPPORTED_MULTIPLICITY_COMBINATION:
    (feature) => `The generator does not support this combination of ${feature} across the two relationship ends.`,
  UNSUPPORTED_NAMESPACE:
    (feature) => `The generator does not support this ${feature} yet.`,
  INVALID_RELATIONSHIP_ENDS:
    (feature) => `A relationship must have exactly two ends; this ${feature} does not meet that rule.`,
  UNRESOLVABLE_TYPE:
    (feature) => `A ${feature} could not be resolved to a class or to a supported primitive type.`,
  PROFILE_INCOMPATIBLE:
    (feature) => `The selected profile is not compatible with this ${feature}.`,
};

/** A diagnostic restated for the product, with no internal identifier anywhere in it. */
export interface ProductDiagnostic {
  readonly feature: string;
  readonly detail: string;
  readonly blocking: boolean;
}

/** Turns a one-word feature code into the product name a person can act on. */
export function featureLabel(feature: string): string {
  const mapped = FEATURE_LABELS[feature];
  if (mapped) {
    return mapped;
  }
  const spaced = feature
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (spaced.length === 0) {
    return 'Unsupported model feature';
  }
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/** Drops anything that looks like an internal identifier from a fallback sentence. */
export function withoutIdentifiers(message: string): string {
  return message.replace(UUID_PATTERN, 'this element').replace(/\s+/g, ' ').trim();
}

export function describeDiagnostic(diagnostic: GenerationDiagnosticResource): ProductDiagnostic {
  const feature = featureLabel(diagnostic.feature);
  const detail = DIAGNOSTIC_DETAILS[diagnostic.code];
  return {
    feature,
    detail: detail
      ? detail(feature)
      : withoutIdentifiers(diagnostic.message) || 'The generator cannot map this part of the model yet.',
    blocking: diagnostic.blocking,
  };
}

export function describeRunState(state: GenerationState): string {
  switch (state) {
    case 'PENDING':
      return 'Queued';
    case 'RUNNING':
      return 'Generating…';
    case 'SUCCEEDED':
      return 'Ready to download';
    case 'FAILED':
      return 'Blocked by the model';
  }
}

export function isTerminalState(state: GenerationState): boolean {
  return state === 'SUCCEEDED' || state === 'FAILED';
}

export function blockingDiagnostics(run: GenerationRunResource | null): ProductDiagnostic[] {
  if (!run) {
    return [];
  }
  return run.diagnostics.filter((diagnostic) => diagnostic.blocking).map(describeDiagnostic);
}

/**
 * The archive is offered only for a run the backend finished successfully, and never while a
 * blocking diagnostic is still on the run.
 */
export function canDownloadArchive(run: GenerationRunResource | null): boolean {
  return run !== null
    && run.state === 'SUCCEEDED'
    && run.artifactZipHash !== null
    && blockingDiagnostics(run).length === 0;
}

/** What a destination looks like in the list: a name and its database, never an identifier. */
export function targetOptionLabel(target: GenerationTargetResource): string {
  return target.databaseName ? `${target.name} · ${target.databaseName}` : target.name;
}

/** Derives a stable, non-secret destination key from the name the user typed. */
export function suggestedTargetKey(name: string): string {
  const slug = name
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return slug.length > 0 ? slug.slice(0, 128) : 'target';
}

/** Keeps keys unique inside one model, because the backend rejects a repeated one. */
export function uniqueTargetKey(base: string, existingKeys: readonly string[]): string {
  if (!existingKeys.includes(base)) {
    return base;
  }
  let suffix = 2;
  while (existingKeys.includes(`${base}_${suffix}`)) {
    suffix += 1;
  }
  return `${base}_${suffix}`;
}
