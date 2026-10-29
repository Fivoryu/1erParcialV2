/**
 * Copy, cut, and paste of a group of classifiers on the canvas.
 *
 * No React and no DOM access: the canvas hands in the selected ids and a dispatch callback,
 * so the whole rule set stays unit-testable. A paste replays ordinary model commands
 * (classifier.create, attribute.add, relationship.create, ...), so it goes through the same
 * validation, optimistic projection, and collaboration events as manual edits.
 */
import type {
  ClassifierResource,
  ModelResource,
  OperationEnvelope,
  RelationshipResource,
  ViewStyleResource,
} from '../api/types';
import {
  applyOptimisticCommand,
  buildClassifierSetNamespaceRequest,
  buildClassifierSetStoreRequest,
  buildConstraintAddRequest,
  buildGeometrySetRequest,
  buildParameterAddRequest,
  buildStyleSetRequest,
  classifierCascadeCount,
  mergeViewStyleUpdate,
  type ModelCommandRequest,
  type ParameterDirection,
  type TypeReferenceInput,
} from './commands';

/** Offset of each successive paste, a multiple of the canvas grid so copies stay snapped. */
export const PASTE_OFFSET = 48;

interface ClipboardRectangle {
  x: number;
  y: number;
  w: number;
  h: number;
  zOrder: number;
}

export interface DiagramClipboard {
  classifiers: ClassifierResource[];
  /** Only relationships that join two copied classifiers. */
  relationships: RelationshipResource[];
  geometry: Record<string, ClipboardRectangle>;
  waypoints: Record<string, Array<{ x: number; y: number }>>;
  styles: Record<string, ViewStyleResource>;
}

export type ClipboardDispatch = (request: ModelCommandRequest) => OperationEnvelope | null | void;

export interface PasteResult {
  classifierIds: string[];
  relationshipIds: string[];
}

function defaultView(model: ModelResource) {
  return model.views.find((view) => view.is_default) ?? model.views[0];
}

/**
 * Snapshot of the selected classifiers. A relationship is copied only when both of its ends
 * are among the copied classifiers: a box selection also selects the relationships that lead
 * to classes left outside it, and copying those would duplicate a link to the original class.
 * Whether the line itself is selected does not matter, because a Ctrl+click selection and a
 * freshly pasted group select classes without their relationships.
 */
export function copySelection(model: ModelResource, classifierIds: Iterable<string>): DiagramClipboard | null {
  const wanted = new Set(classifierIds);
  const classifiers = model.classifiers.filter((classifier) => wanted.has(classifier.id));
  if (classifiers.length === 0) {
    return null;
  }
  const copiedIds = new Set(classifiers.map((classifier) => classifier.id));
  const relationships = model.relationships.filter((relationship) =>
    relationship.ends.length === 2
    && relationship.ends.every((end) => copiedIds.has(end.classifierId)));
  const view = defaultView(model);
  const geometry: DiagramClipboard['geometry'] = {};
  for (const entry of view?.element_geometry ?? []) {
    if (entry.targetType === 'CLASSIFIER' && copiedIds.has(entry.targetId)) {
      geometry[entry.targetId] = { x: entry.x, y: entry.y, w: entry.w, h: entry.h, zOrder: entry.zOrder };
    }
  }
  const waypoints: DiagramClipboard['waypoints'] = {};
  const styles: DiagramClipboard['styles'] = {};
  for (const relationship of relationships) {
    waypoints[relationship.id] = (view?.connector_path ?? [])
      .filter((path) => path.relationshipId === relationship.id)
      .sort((left, right) => left.ordinal - right.ordinal)
      .map(({ x, y }) => ({ x, y }));
    const style = (model.view_style ?? []).find((row) => row.viewId === view?.id && row.targetId === relationship.id);
    if (style) {
      styles[relationship.id] = style;
    }
  }
  return { classifiers, relationships, geometry, waypoints, styles };
}

/** `Name`, then `NameCopy`, `NameCopy2`, ...: the first one no classifier of the model uses. */
function copyName(name: string, taken: Set<string>): string {
  let candidate = taken.has(name) ? `${name}Copy` : name;
  for (let index = 2; taken.has(candidate); index += 1) {
    candidate = `${name}Copy${index}`;
  }
  taken.add(candidate);
  return candidate;
}

function createdId(envelope: OperationEnvelope | null | void): string | null {
  return envelope && typeof envelope.payload.id === 'string' ? envelope.payload.id : null;
}

/**
 * Pastes the clipboard into `model`, `offset` px right and down from where it was copied.
 * Types and relationship ends that pointed at a copied classifier point at its copy; a type
 * that pointed at a classifier left out keeps pointing at it when the model has it.
 */
export function pasteClipboard(
  clipboard: DiagramClipboard,
  model: ModelResource,
  dispatch: ClipboardDispatch,
  offset: number,
): PasteResult {
  const takenNames = new Set(model.classifiers.map((classifier) => classifier.name));
  const existingIds = new Set(model.classifiers.map((classifier) => classifier.id));
  const copies = new Map<string, string>();

  for (const original of clipboard.classifiers) {
    const keepsNamespace = original.namespaceId !== null
      && !clipboard.classifiers.some((candidate) => candidate.id === original.namespaceId)
      && existingIds.has(original.namespaceId);
    const id = createdId(dispatch({
      type: 'classifier.create',
      payload: {
        name: copyName(original.name, takenNames),
        kind: original.kind,
        namespaceId: keepsNamespace ? original.namespaceId : null,
      },
    }));
    if (id) {
      copies.set(original.id, id);
    }
  }

  const copyByName = new Map<string, string>();
  for (const original of clipboard.classifiers) {
    const copy = copies.get(original.id);
    if (copy) {
      copyByName.set(original.name, copy);
    }
  }
  const typeInput = (name: string | null): TypeReferenceInput | null => {
    if (!name) {
      return null;
    }
    const copied = copyByName.get(name);
    if (copied) {
      return { classifierId: copied };
    }
    const existing = model.classifiers.find((classifier) => classifier.name === name);
    return existing ? { classifierId: existing.id } : { primitiveName: name };
  };
  const viewId = defaultView(model)?.id;

  for (const original of clipboard.classifiers) {
    const id = copies.get(original.id);
    if (!id) {
      continue;
    }
    const target = [{ scope: 'classifier', id }];
    const namespaceCopy = original.namespaceId ? copies.get(original.namespaceId) : undefined;
    if (namespaceCopy) {
      dispatch(buildClassifierSetNamespaceRequest(id, namespaceCopy));
    }
    if (original.storeOverride) {
      dispatch(buildClassifierSetStoreRequest(id, original.storeOverride));
    }
    for (const attribute of original.attributes) {
      // Enumeration literals are untyped identifiers; the backend rejects a type on them.
      const literal = original.kind === 'ENUMERATION' || attribute.kind === 'LITERAL';
      dispatch({
        type: 'attribute.add',
        targets: target,
        payload: literal
          ? { name: attribute.name, ordinal: attribute.ordinal }
          : {
            name: attribute.name,
            type: typeInput(attribute.type) ?? { primitiveName: 'String' },
            visibility: attribute.visibility,
            multiplicity: attribute.multiplicity,
            defaultValue: attribute.defaultValue,
            ordinal: attribute.ordinal,
          },
      });
    }
    for (const operation of original.operations) {
      const operationId = createdId(dispatch({
        type: 'operation.add',
        targets: target,
        payload: {
          name: operation.name,
          returnType: typeInput(operation.returnType),
          visibility: operation.visibility,
          isAbstract: operation.isAbstract,
          isStatic: operation.isStatic,
          ordinal: operation.ordinal,
        },
      }));
      if (!operationId) {
        continue;
      }
      for (const parameter of operation.parameters) {
        dispatch(buildParameterAddRequest(operationId, {
          name: parameter.name,
          type: typeInput(parameter.type) ?? { primitiveName: 'String' },
          direction: parameter.direction as ParameterDirection,
          ordinal: parameter.ordinal,
        }));
      }
    }
    for (const constraint of original.constraints) {
      dispatch(buildConstraintAddRequest('CLASSIFIER', id, constraint.expression, constraint.severity));
    }
    const rectangle = clipboard.geometry[original.id];
    if (rectangle && viewId) {
      dispatch(buildGeometrySetRequest({
        targetType: 'CLASSIFIER',
        viewId,
        targetId: id,
        x: rectangle.x + offset,
        y: rectangle.y + offset,
        w: rectangle.w,
        h: rectangle.h,
        zOrder: rectangle.zOrder,
      }));
    }
  }

  const relationshipIds: string[] = [];
  for (const original of clipboard.relationships) {
    const [first, second] = [...original.ends].sort((left, right) => left.ordinal - right.ordinal);
    const sourceId = first ? copies.get(first.classifierId) : undefined;
    const targetId = second ? copies.get(second.classifierId) : undefined;
    if (!first || !second || !sourceId || !targetId) {
      continue;
    }
    const end = (classifierId: string, from: typeof first) => ({
      classifierId,
      role: from.role,
      multiplicity: from.multiplicity,
      navigability: from.navigability,
    });
    const associationClassCopy = original.associationClassId ? copies.get(original.associationClassId) : undefined;
    const id = createdId(dispatch({
      type: 'relationship.create',
      targets: [
        { scope: 'classifier', id: sourceId },
        { scope: 'classifier', id: targetId },
      ],
      payload: {
        kind: original.kind,
        name: original.name,
        ...(original.stereotype ? { stereotype: original.stereotype } : {}),
        // An association class belongs to one association, so only a copied one follows the copy.
        ...(associationClassCopy ? { associationClassId: associationClassCopy } : {}),
        source: end(sourceId, first),
        target: end(targetId, second),
        waypoints: (clipboard.waypoints[original.id] ?? []).map(({ x, y }) => ({ x: x + offset, y: y + offset })),
      },
    }));
    if (!id) {
      continue;
    }
    relationshipIds.push(id);
    for (const constraint of original.constraints) {
      dispatch(buildConstraintAddRequest('RELATIONSHIP', id, constraint.expression, constraint.severity));
    }
    const style = clipboard.styles[original.id];
    if (style && viewId) {
      dispatch(buildStyleSetRequest(mergeViewStyleUpdate(viewId, id, {
        routing: style.routing,
        cornerRadius: style.cornerRadius,
        strokeWidth: style.strokeWidth,
        dashed: style.dashed,
        routeAdjustment: style.routeAdjustment ?? null,
      }, {})));
    }
  }

  return { classifierIds: [...copies.values()], relationshipIds };
}

/** Relationships the cut deletes although they were not copied: they lead to a class outside the cut. */
export function relationshipsLostByCut(model: ModelResource, clipboard: DiagramClipboard): number {
  const cutIds = new Set(clipboard.classifiers.map((classifier) => classifier.id));
  const copied = new Set(clipboard.relationships.map((relationship) => relationship.id));
  return model.relationships.filter((relationship) =>
    !copied.has(relationship.id)
    && relationship.ends.some((end) => cutIds.has(end.classifierId))).length;
}

/**
 * The delete half of a cut. Each classifier.delete carries the cascade count of the model as
 * it will be when it runs, after the deletions queued before it removed shared relationships.
 */
export function deleteClipboardClassifiers(
  clipboard: DiagramClipboard,
  model: ModelResource,
  dispatch: ClipboardDispatch,
): void {
  let working = model;
  for (const classifier of clipboard.classifiers) {
    const cascadeCount = classifierCascadeCount(working, classifier.id);
    if (cascadeCount === 0) {
      continue;
    }
    const envelope = dispatch({
      type: 'classifier.delete',
      targets: [{ scope: 'classifier', id: classifier.id }],
      preconditions: [{ kind: 'cascade_count', expect: cascadeCount, scope: null }],
      payload: {},
    });
    if (!envelope) {
      return;
    }
    working = applyOptimisticCommand(working, envelope);
  }
}
