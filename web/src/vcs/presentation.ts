import type { VcsCommitKind, VcsCommitOrigin, VcsMergeRequestState, VcsPermissionAction } from './api';

const COMMIT_KIND_LABELS: Record<VcsCommitKind, string> = {
  INITIAL: 'Inicial',
  MANUAL: 'Manual',
  MERGE: 'Fusión',
};

const COMMIT_ORIGIN_LABELS: Record<VcsCommitOrigin, string> = {
  MANUAL: 'Manual',
  AI: 'IA',
  VOICE: 'Voz',
  IMPORT: 'Importación',
  MERGE: 'Fusión',
};

const MERGE_REQUEST_STATE_LABELS: Record<VcsMergeRequestState, string> = {
  OPEN: 'Abierta',
  MERGED: 'Fusionada',
  CLOSED: 'Cerrada',
};

const PERMISSION_ACTION_LABELS: Record<VcsPermissionAction, string> = {
  VIEW: 'Ver',
  CREATE: 'Crear',
  ADD: 'Agregar',
  DELETE: 'Eliminar',
  COMMIT: 'Guardar cambios',
  BRANCH: 'Administrar ramas',
  MERGE: 'Fusionar',
  MANAGE_PERMISSIONS: 'Administrar permisos',
};

const COMMIT_CHANGE_LABELS: Record<string, string> = {
  ADDED: 'Agregado',
  MODIFIED: 'Modificado',
  UPDATED: 'Actualizado',
  REMOVED: 'Eliminado',
  DELETED: 'Eliminado',
};

const ELEMENT_TYPE_LABELS: Record<string, string> = {
  CLASSIFIER: 'Clase',
  ATTRIBUTE: 'Atributo',
  OPERATION: 'Operación',
  PARAMETER: 'Parámetro',
  CONSTRAINT: 'Restricción',
  RELATIONSHIP: 'Relación',
  RELATIONSHIP_END: 'Extremo de relación',
  VIEW: 'Vista',
  GEOMETRY: 'Posición',
  CONNECTOR_PATH: 'Ruta del conector',
  VIEW_STYLE: 'Estilo de vista',
};

const DIFF_FIELD_LABELS: Record<string, string> = {
  name: 'Nombre',
  kind: 'Tipo de elemento',
  isAbstract: 'Abstracta',
  isStatic: 'Estática',
  isDefault: 'Predeterminada',
  storeOverride: 'Almacenamiento',
  visibility: 'Visibilidad',
  multiplicity: 'Multiplicidad',
  defaultValue: 'Valor predeterminado',
  type: 'Tipo',
  returnType: 'Tipo de retorno',
  direction: 'Dirección',
  stereotype: 'Estereotipo',
  associationClass: 'Clase de asociación',
  classifier: 'Clase',
  role: 'Rol',
  navigability: 'Navegabilidad',
  aggregationKind: 'Agregación',
  expression: 'Expresión',
  severity: 'Severidad',
  viewKind: 'Clase de vista',
};

const AUTHOR_ROLE_LABELS: Record<string, string> = {
  AUTHOR: 'Autor/a',
  MERGED_BY: 'Fusionó',
  CONTRIBUTOR: 'Aportó cambios',
};

export function commitKindLabel(kind: VcsCommitKind): string {
  return COMMIT_KIND_LABELS[kind];
}

export function commitOriginLabel(origin: VcsCommitOrigin): string {
  return COMMIT_ORIGIN_LABELS[origin];
}

export function mergeRequestStateLabel(state: VcsMergeRequestState): string {
  return MERGE_REQUEST_STATE_LABELS[state];
}

export function permissionActionLabel(action: VcsPermissionAction): string {
  return PERMISSION_ACTION_LABELS[action];
}

export function commitChangeLabel(changeKind: string): string {
  return COMMIT_CHANGE_LABELS[changeKind] ?? 'Cambio';
}

export function elementTypeLabel(targetType: string): string {
  return ELEMENT_TYPE_LABELS[targetType] ?? 'Elemento';
}

export function diffFieldLabel(field: string): string {
  return DIFF_FIELD_LABELS[field] ?? field;
}

export function authorRoleLabel(role: string): string {
  return AUTHOR_ROLE_LABELS[role] ?? 'Participó';
}

export function relativeTimeLabel(timestamp: string, now = Date.now()): string {
  const createdAt = Date.parse(timestamp);
  if (Number.isNaN(createdAt)) {
    return 'Hora no disponible';
  }

  const elapsedSeconds = (now - createdAt) / 1000;
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 60 * 60 * 24 * 365],
    ['month', 60 * 60 * 24 * 30],
    ['week', 60 * 60 * 24 * 7],
    ['day', 60 * 60 * 24],
    ['hour', 60 * 60],
    ['minute', 60],
    ['second', 1],
  ];
  const formatter = new Intl.RelativeTimeFormat('es', { numeric: 'auto' });
  const [unit, secondsPerUnit] = units.find(([, seconds]) => Math.abs(elapsedSeconds) >= seconds) ?? units.at(-1)!;
  return formatter.format(-Math.round(elapsedSeconds / secondsPerUnit), unit);
}
