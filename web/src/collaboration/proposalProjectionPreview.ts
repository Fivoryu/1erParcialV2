import type {
  AttributeResource,
  ChatProposalOperation,
  ClassifierResource,
  ModelResource,
  RelationshipResource,
} from '../api/types';

export type ProposalPreviewState = 'added' | 'modified' | 'removed';

export interface ProposalPreview {
  /** A detached copy of the model with the supported prefix applied; removed elements stay as ghosts. */
  model: ModelResource;
  /** Change state per element id (classifier, attribute, operation or relationship). */
  states: Record<string, ProposalPreviewState>;
  /** Index of the first step that cannot be previewed; it and every later step are not shown. */
  stoppedAt: number | null;
}

/** Same comparison as the backend's name lookup: trimmed, case- and accent-insensitive. */
function normalizeName(value: string): string {
  return value.trim().toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '');
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null;
}

function upper(value: unknown, fallback: string): string {
  return text(value)?.toUpperCase() ?? fallback;
}

/** A RANGE bound from a proposal argument: only a whole number counts, anything else is unset. */
function bound(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) ? value : null;
}

/**
 * Projects the supported prefix of a proposal without changing the live model, mirroring how the
 * backend applies a plan: steps run in order, a member that already exists is skipped, and the
 * first step that would be rejected stops the rest. A removed element stays in the display model
 * as a ghost, but is no longer a valid target. Unsupported steps stop the projection, because
 * later steps may depend on their effects.
 */
export function projectProposalPreview(model: ModelResource, operations: readonly ChatProposalOperation[]): ProposalPreview {
  const projected: ModelResource = structuredClone(model);
  const states: Record<string, ProposalPreviewState> = {};
  const removed = new Set<string>();
  let created = 0;
  let stoppedAt: number | null = null;

  const live = <T extends { id: string }>(item: T | undefined) => (item && !removed.has(item.id) ? item : undefined);
  const classifierById = (id: unknown) => live(projected.classifiers.find((item) => item.id === id));
  const classifierByName = (name: string | null) => {
    if (!name) {
      return undefined;
    }
    const matches = projected.classifiers.filter((item) => !removed.has(item.id)
      && normalizeName(item.name) === normalizeName(name));
    return matches.length === 1 ? matches[0] : undefined;
  };
  const attributeOwner = (id: unknown) => projected.classifiers.find((item) => !removed.has(item.id)
    && item.attributes.some((attribute) => attribute.id === id && !removed.has(attribute.id)));
  const relationshipById = (id: unknown) => live(projected.relationships.find((item) => item.id === id));
  const classifierForEnd = (value: unknown) => {
    if (typeof value !== 'object' || value === null) {
      return undefined;
    }
    const end = value as Record<string, unknown>;
    return end['classifierId'] != null ? classifierById(end['classifierId']) : classifierByName(text(end['classifierName']));
  };
  const typeName = (value: unknown): string | null => {
    if (typeof value === 'string') {
      return text(value);
    }
    if (typeof value === 'object' && value !== null) {
      const type = value as Record<string, unknown>;
      return text(type['primitiveName']) ?? classifierById(type['classifierId'])?.name ?? null;
    }
    return null;
  };
  const markModified = (id: string) => {
    if (states[id] !== 'added') {
      states[id] = 'modified';
    }
  };
  const newId = () => `preview-new-${created++}`;

  function apply(operation: ChatProposalOperation): boolean {
    const args = operation.args;
    switch (operation.type) {
      case 'classifier.create': {
        const name = text(args['name']);
        if (!name) {
          return false;
        }
        const classifier: ClassifierResource = {
          id: newId(), name, kind: upper(args['kind'], 'CLASS'), isAbstract: false, namespaceId: null,
          attributes: [], operations: [], constraints: [],
        };
        projected.classifiers.push(classifier);
        states[classifier.id] = 'added';
        return true;
      }
      case 'classifier.rename': {
        const classifier = classifierById(operation.targetId);
        // Prompt v3 declares args {newName}; args.name stays a tolerated fallback.
        const name = text(args['newName']) ?? text(args['name']);
        if (!classifier || !name) {
          return false;
        }
        const collision = projected.classifiers.some((item) => item.id !== classifier.id
          && !removed.has(item.id) && item.namespaceId === classifier.namespaceId && item.name === name);
        if (collision) {
          return false;
        }
        classifier.name = name;
        markModified(classifier.id);
        return true;
      }
      case 'classifier.setAbstract': {
        const classifier = classifierById(operation.targetId);
        if (!classifier) {
          return false;
        }
        const raw = args['isAbstract'] ?? args['value'];
        classifier.isAbstract = typeof raw === 'boolean' ? raw : true;
        markModified(classifier.id);
        return true;
      }
      case 'classifier.move': {
        const classifier = classifierById(operation.targetId);
        const view = projected.views.find((item) => item.is_default) ?? projected.views[0];
        const numbers = ['x', 'y', 'dx', 'dy'].map((key) => (typeof args[key] === 'number' ? args[key] as number : null));
        if (!classifier || !view || numbers.every((value) => value === null)) {
          return false;
        }
        const [x, y, dx, dy] = numbers;
        const geometry = view.element_geometry.find((item) => item.targetType === 'CLASSIFIER' && item.targetId === classifier.id);
        if (geometry) {
          geometry.x = x ?? geometry.x + (dx ?? 0);
          geometry.y = y ?? geometry.y + (dy ?? 0);
        }
        markModified(classifier.id);
        return true;
      }
      case 'classifier.delete': {
        const classifier = classifierById(operation.targetId);
        if (!classifier) {
          return false;
        }
        removed.add(classifier.id);
        states[classifier.id] = 'removed';
        for (const relationship of projected.relationships) {
          if (!removed.has(relationship.id) && relationship.ends.some((end) => end.classifierId === classifier.id)) {
            removed.add(relationship.id);
            states[relationship.id] = 'removed';
          }
        }
        return true;
      }
      case 'attribute.add':
      case 'operation.add': {
        const name = text(args['name']);
        const owner = operation.targetId ? classifierById(operation.targetId) : classifierByName(text(args['targetClassifierName']));
        if (!name || !owner) {
          return false;
        }
        const members = operation.type === 'attribute.add' ? owner.attributes : owner.operations;
        // The backend skips a member whose exact name already exists in the class, and goes on.
        if (members.some((member) => member.name === name)) {
          return true;
        }
        const id = newId();
        const visibility = upper(args['visibility'], 'PUBLIC');
        if (operation.type === 'attribute.add') {
          owner.attributes.push({
            id, name, kind: 'ATTRIBUTE', visibility, multiplicity: upper(args['multiplicity'], 'ONE'),
            lowerBound: bound(args['lowerBound']), upperBound: bound(args['upperBound']),
            defaultValue: text(args['defaultValue']), typeReferenceId: null,
            type: typeName(args['type']) ?? 'String', ordinal: owner.attributes.length,
          });
        } else {
          owner.operations.push({
            id, name, visibility, isAbstract: false, isStatic: false, returnTypeReferenceId: null,
            returnType: typeName(args['returnType']), ordinal: owner.operations.length, parameters: [],
          });
        }
        states[id] = 'added';
        return true;
      }
      case 'attribute.update': {
        const attribute = attributeOwner(operation.targetId)?.attributes.find((item) => item.id === operation.targetId);
        const changes: Partial<AttributeResource> = {};
        if (text(args['name'])) changes.name = text(args['name'])!;
        if (typeName(args['type'])) changes.type = typeName(args['type']);
        if (text(args['visibility'])) changes.visibility = upper(args['visibility'], '');
        if (text(args['multiplicity'])) changes.multiplicity = upper(args['multiplicity'], '');
        if (args['lowerBound'] !== undefined) changes.lowerBound = bound(args['lowerBound']);
        if (args['upperBound'] !== undefined) changes.upperBound = bound(args['upperBound']);
        if (text(args['defaultValue'])) changes.defaultValue = text(args['defaultValue']);
        if (!attribute || Object.keys(changes).length === 0) {
          return false;
        }
        Object.assign(attribute, changes);
        markModified(attribute.id);
        return true;
      }
      case 'attribute.remove': {
        const attribute = attributeOwner(operation.targetId)?.attributes.find((item) => item.id === operation.targetId);
        if (!attribute) {
          return false;
        }
        removed.add(attribute.id);
        states[attribute.id] = 'removed';
        return true;
      }
      case 'relationship.create': {
        const source = classifierForEnd(args['source']);
        const target = classifierForEnd(args['target']);
        if (!source || !target || args['associationClass'] != null) {
          return false;
        }
        const id = newId();
        const end = (classifierId: string, ordinal: number) => ({
          id: `${id}-end-${ordinal}`, classifierId, role: null, multiplicity: 'ONE',
          navigability: 'UNSPECIFIED', aggregationKind: 'NONE', ordinal,
        });
        const relationship: RelationshipResource = {
          id, kind: upper(args['kind'], 'ASSOCIATION'), name: text(args['name']), constraints: [],
          ends: [end(source.id, 0), end(target.id, 1)],
        };
        projected.relationships.push(relationship);
        states[id] = 'added';
        return true;
      }
      case 'relationship.update': {
        const relationship = relationshipById(operation.targetId);
        // An association class change needs a lookup the preview does not model, so it is not shown.
        if (!relationship || 'associationClass' in args) {
          return false;
        }
        let changed = false;
        if (text(args['name'])) {
          relationship.name = text(args['name']);
          changed = true;
        }
        if (text(args['kind'])) {
          relationship.kind = upper(args['kind'], relationship.kind);
          changed = true;
        }
        if ('stereotype' in args) {
          relationship.stereotype = text(args['stereotype'])?.toUpperCase() ?? null;
          changed = true;
        }
        for (const [key, ordinal] of [['source', 0], ['target', 1]] as const) {
          const update = args[key];
          const end = relationship.ends.find((item) => item.ordinal === ordinal);
          if (typeof update !== 'object' || update === null || !end) {
            continue;
          }
          const values = update as Record<string, unknown>;
          if (values['role'] != null) end.role = String(values['role']);
          if (values['multiplicity'] != null) end.multiplicity = String(values['multiplicity']).toUpperCase();
          if (values['lowerBound'] !== undefined) end.lowerBound = bound(values['lowerBound']);
          if (values['upperBound'] !== undefined) end.upperBound = bound(values['upperBound']);
          if (values['navigability'] != null) end.navigability = String(values['navigability']).toUpperCase();
          changed = changed
            || ['role', 'multiplicity', 'navigability', 'lowerBound', 'upperBound'].some((field) => values[field] != null);
        }
        if (!changed) {
          return false;
        }
        markModified(relationship.id);
        return true;
      }
      case 'relationship.delete': {
        const relationship = relationshipById(operation.targetId);
        if (!relationship) {
          return false;
        }
        removed.add(relationship.id);
        states[relationship.id] = 'removed';
        return true;
      }
      default:
        return false;
    }
  }

  for (const [index, operation] of operations.entries()) {
    if (!apply(operation)) {
      stoppedAt = index;
      break;
    }
  }
  return { model: projected, states, stoppedAt };
}
