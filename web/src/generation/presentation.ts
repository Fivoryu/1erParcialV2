import type {
  DeploymentState,
  GenerationDiagnosticResource,
  GenerationRunResource,
  GenerationState,
  GenerationTargetResource,
  VerificationState,
} from '../api/types';

/**
 * Product language for the generation panel.
 *
 * The panel never echoes a backend diagnostic message: those messages name type references and
 * classifiers by their internal identifiers, and the editor must not show one. Every sentence here
 * is rebuilt from the diagnostic `code` and its named `feature`, so a product label can never carry
 * an identifier through by accident.
 */

const VERIFICATION_LABELS: Record<string, string> = {
  artifact_set_structure: 'Estructura de archivos generados',
  flyway_migration_file: 'Contenido de la migración',
  artifact_hashes: 'Integridad de archivos',
  frozen_input_determinism: 'Reproducción de archivos',
  target_safe_configuration: 'Configuración segura del destino',
  jpa_entities_and_relationships: 'Entidades y relaciones JPA',
  repositories: 'Repositorios',
  services_and_supported_operations: 'Servicios y operaciones',
  rest_dto_openapi_behavior: 'Comportamiento REST y OpenAPI',
  validation_and_error_handling: 'Validación y manejo de errores',
  security: 'Seguridad', authorization: 'Autorización', audit: 'Auditoría',
  postgres_migration_execution: 'Ejecución de migración en PostgreSQL',
  generated_backend_compilation: 'Compilación del backend',
  generated_backend_startup: 'Inicio del backend',
  generated_backend_health: 'Estado del backend',
  generated_backend_contract_smoke: 'Prueba del contrato HTTP',
  generated_backend_tests: 'Pruebas del backend generado',
  docker_compose_execution: 'Ejecución de Docker Compose',
  generated_backend_runtime_reproducibility: 'Reproducción del entorno de ejecución',
};

export function verificationCheckLabel(name: string): string {
  return VERIFICATION_LABELS[name] ?? 'Comprobación adicional';
}

export function verificationStateLabel(state: VerificationState): string {
  switch (state) {
    case 'PASSED': return 'Aprobada';
    case 'FAILED': return 'Fallida';
    case 'NOT_RUN': return 'No ejecutada';
    default: return 'Estado desconocido';
  }
}

const FEATURE_LABELS: Record<string, string> = {
  GENERALIZATION: 'Generalización (herencia)',
  REALIZATION: 'Realización (implementación de interfaz)',
  DEPENDENCY: 'Dependencia',
  ASSOCIATION: 'Asociación',
  AGGREGATION: 'Agregación',
  COMPOSITION: 'Composición',
  multiplicity: 'Multiplicidad',
  relationshipKind: 'Tipo de relación',
  relationshipEnds: 'Extremos de la relación',
  classifierKind: 'Tipo de clasificador',
  namespace: 'Ubicación del paquete',
  visibility: 'Visibilidad',
  primitiveTypeMappings: 'Asignación de tipos primitivos',
};

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

const DIAGNOSTIC_DETAILS: Record<string, (feature: string) => string> = {
  UNSUPPORTED_UML_FEATURE:
    (feature) => `El generador aún no admite ${feature}. Elimínalo o reemplázalo y vuelve a generar.`,
  UNSUPPORTED_MULTIPLICITY:
    (feature) => `El generador aún no admite esta ${feature} en un atributo.`,
  UNSUPPORTED_MULTIPLICITY_COMBINATION:
    (feature) => `El generador no admite esta combinación de ${feature} entre los dos extremos de la relación.`,
  UNSUPPORTED_NAMESPACE:
    (feature) => `El generador aún no admite esta ${feature}.`,
  INVALID_RELATIONSHIP_ENDS:
    (feature) => `Una relación debe tener exactamente dos extremos; esta ${feature} no cumple esa regla.`,
  UNRESOLVABLE_TYPE:
    (feature) => `No se pudo resolver ${feature} como una clase o un tipo primitivo compatible.`,
  PROFILE_INCOMPATIBLE:
    (feature) => `El perfil seleccionado no es compatible con esta ${feature}.`,
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
    return 'Característica del modelo no compatible';
  }
  return spaced[0]!.toUpperCase() + spaced.slice(1);
}

/** Drops anything that looks like an internal identifier from a fallback sentence. */
export function withoutIdentifiers(message: string): string {
  return message.replace(UUID_PATTERN, 'este elemento').replace(/\s+/g, ' ').trim();
}

export function describeDiagnostic(diagnostic: GenerationDiagnosticResource): ProductDiagnostic {
  const feature = featureLabel(diagnostic.feature);
  const detail = DIAGNOSTIC_DETAILS[diagnostic.code];
  return {
    feature,
    detail: detail
      ? detail(feature)
      : withoutIdentifiers(diagnostic.message) || 'El generador aún no puede asignar esta parte del modelo.',
    blocking: diagnostic.blocking,
  };
}

export function describeRunState(state: GenerationState): string {
  switch (state) {
    case 'PENDING':
      return 'En cola';
    case 'RUNNING':
      return 'Generando…';
    case 'SUCCEEDED':
      return 'Listo para descargar';
    case 'FAILED':
      return 'Bloqueado por el modelo';
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
  PENDING: 'En espera del operador',
  RUNNING: 'Implementando…',
  UP: 'En ejecución',
  FAILED: 'No se pudo iniciar',
  STOPPING: 'Deteniendo…',
  STOPPED: 'Detenido',
};

const DEPLOYMENT_FAILURE_REASONS: Record<string, string> = {
  COMPOSE_UP_FAILED:
    'Docker Compose no pudo iniciar los servicios. Comprueba que los puertos estén libres y que Docker esté en ejecución.',
  GENERATED_BUILD_FAILED:
    'El proyecto generado no se pudo compilar. Es posible que falte algo en la caché local de Maven.',
  MISSING_LOCAL_IMAGE:
    'La imagen necesaria para esta implementación no está en este equipo y el operador no la descarga. Descárgala una vez y vuelve a implementar.',
  HEALTH_NEVER_UP: 'Los servicios se iniciaron, pero el backend nunca respondió a la comprobación de estado.',
  OPERATOR_INTERRUPTED: 'La implementación se interrumpió y sus servicios se detuvieron.',
  UNKNOWN_DEPLOYMENT_DIRECTORY:
    'El operador que inició esta implementación ya no tiene su directorio de trabajo, por lo que no puede detenerla.',
  STOP_INCOMPLETE: 'No se pudieron quitar todos los servicios; es posible que alguno siga en ejecución.',
  DOCKER_UNAVAILABLE: 'El operador no pudo usar Docker en este equipo.',
  MAVEN_UNAVAILABLE: 'El operador no pudo usar Maven en este equipo.',
  ARCHIVE_INCOMPLETE: 'Falta un archivo necesario para la implementación en el paquete generado.',
  ARCHIVE_UNAVAILABLE: 'No se pudo descargar el paquete generado, así que no hubo nada para implementar.',
  API_UNREACHABLE: 'El operador no pudo conectarse con Class Diagrammer.',
  TARGET_NOT_FOUND: 'El destino de esta ejecución ya no existe.',
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
  return 'La implementación no terminó. Consulta los detalles en el registro del operador de este equipo.';
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
  'El backend generado no tiene autenticación, por lo que cualquiera en esta red que conozca la dirección puede leer y '
  + 'modificar sus datos. Úsalo solo en una red de confianza.';
