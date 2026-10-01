import type {
  DeploymentState,
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

/**
 * Product language for a deployment.
 *
 * The deployment is what the user actually cares about, so its state and its failures are stated in terms of
 * what happened to their backend, never in terms of the operator's internals.
 */

const DEPLOYMENT_STATE_LABELS: Record<DeploymentState, string> = {
  PENDING: 'Waiting for the operator',
  RUNNING: 'Deploying…',
  UP: 'Running',
  FAILED: 'Could not start',
  STOPPING: 'Stopping…',
  STOPPED: 'Stopped',
};

const DEPLOYMENT_FAILURE_REASONS: Record<string, string> = {
  COMPOSE_UP_FAILED:
    'Docker Compose could not start the stack. Check that the ports are free and that Docker is running.',
  GENERATED_BUILD_FAILED:
    'The generated project did not compile. The local Maven cache may be missing something it needs.',
  MISSING_LOCAL_IMAGE:
    'An image this deployment needs is not on this machine, and the operator never downloads one. Pull it once and deploy again.',
  HEALTH_NEVER_UP: 'The stack started but the backend never answered its health check.',
  OPERATOR_INTERRUPTED: 'The deployment was interrupted, so its stack was taken down.',
  UNKNOWN_DEPLOYMENT_DIRECTORY:
    'The operator that started this deployment no longer has its working directory, so it cannot take it down.',
  STOP_INCOMPLETE: 'The stack could not be removed completely; something may still be running.',
  DOCKER_UNAVAILABLE: 'The operator could not use Docker on this machine.',
  MAVEN_UNAVAILABLE: 'The operator could not use Maven on this machine.',
  ARCHIVE_INCOMPLETE: 'The generated archive is missing a file the deployment needs.',
  ARCHIVE_UNAVAILABLE: 'The generated archive could not be downloaded, so there was nothing to deploy.',
  API_UNREACHABLE: 'The operator could not reach the diagrammer.',
  TARGET_NOT_FOUND: 'The destination of this run no longer exists.',
};

/** A host path or a scheme-like location, which must not reach the screen either. */
const LOCATION_PATTERN = /(?:[A-Za-z]:\\|\/(?:home|Users|tmp|var)\/|\bfile:\/\/)/;

export function describeDeploymentState(state: DeploymentState): string {
  return DEPLOYMENT_STATE_LABELS[state];
}

/** True while the deployment holds its destination and its host port pair. */
export function isDeploymentActive(state: DeploymentState): boolean {
  return state === 'PENDING' || state === 'RUNNING' || state === 'UP' || state === 'STOPPING';
}

export function isDeploymentTerminal(state: DeploymentState): boolean {
  return state === 'FAILED' || state === 'STOPPED';
}

export function canStopDeployment(state: DeploymentState): boolean {
  return state === 'PENDING' || state === 'RUNNING' || state === 'UP' || state === 'FAILED';
}

/** The reason a deployment failed, in product language, with the operator's code as the key. */
export function describeDeploymentFailure(code: string | null): string {
  if (code && DEPLOYMENT_FAILURE_REASONS[code]) {
    return DEPLOYMENT_FAILURE_REASONS[code];
  }
  return 'The deployment did not finish. The operator log on this machine has the details.';
}

/**
 * The operator's own message, shown only when it is free of anything technical.
 *
 * <p>Its messages can name a working directory or a container, and none of that belongs on this screen. When it
 * is not clean the product sentence above stands alone, which is still true.
 */
export function safeDeploymentDetail(message: string | null): string | null {
  if (!message) {
    return null;
  }
  if (UUID_PATTERN.test(message) || LOCATION_PATTERN.test(message)) {
    return null;
  }
  return message;
}

/**
 * The address another device on the same network uses.
 *
 * <p>It is built from the host the browser already used, because that host is a property of whoever is looking
 * and not of the server: a server that guessed its own address would be wrong for every client that reaches it
 * by another name.
 */
export function phoneBaseUrl(hostname: string, httpHostPort: number): string {
  const host = hostname.length > 0 ? hostname : 'localhost';
  return `http://${host}:${httpHostPort}`;
}

/** True when the editor was opened through loopback, so the shown address cannot work from another device. */
export function editorIsLocal(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1' || hostname === '[::1]';
}

export const NO_AUTHENTICATION_WARNING =
  'The generated backend has no authentication, so anyone on this network who knows the address can read and '
  + 'write its data. Keep it on a network you trust.';
