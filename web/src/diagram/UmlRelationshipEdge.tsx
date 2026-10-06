import { useEffect, useId, useRef, useState, type MouseEvent, type PointerEvent, type ReactNode } from 'react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  useInternalNode,
  useReactFlow,
  type Edge,
  type EdgeProps,
} from '@xyflow/react';
import type { RelationshipEndResource, RelationshipResource, RouteAdjustmentResource } from '../api/types';
import {
  ORTHO_GRID,
  addJog,
  defaultJogLevelOffset,
  dragOrthoHandle,
  orthogonalRoute,
  removeJog,
  roundedOrthogonalPath,
  roundToOrthoGrid,
  type OrthoAdjustment,
  type OrthoHandle,
} from './orthogonal-routing';
import { endOrdinalForLabelSide } from './contextMenuBehavior';
import { formatRelationshipEndLabel } from './uml-format';

export type RelationshipMarker =
  | 'open-arrow'
  | 'hollow-diamond'
  | 'filled-diamond'
  | 'hollow-diamond-open-arrow'
  | 'filled-diamond-open-arrow'
  | 'hollow-triangle'
  | null;

export interface EdgePoint {
  x: number;
  y: number;
}

export interface EdgeRectangle extends EdgePoint {
  width: number;
  height: number;
}

export interface RelationshipLabelPlacement {
  x: number;
  y: number;
  estimatedBounds: EdgeRectangle;
}

export interface RelationshipLabelTexts {
  source: string;
  target: string;
  targetStereotype?: string;
  name: string;
}

export interface RelationshipLabelPlacements {
  source: RelationshipLabelPlacement | null;
  target: RelationshipLabelPlacement | null;
  name: RelationshipLabelPlacement | null;
}

const ESTIMATED_LABEL_CHARACTER_WIDTH_PX = 6;
const LABEL_HORIZONTAL_PADDING_PX = 10;
const LABEL_HEIGHT_PX = 17;
const LABEL_GROUP_GAP_PX = 5;
const LABEL_NODE_PADDING_PX = 8;
const LABEL_TO_LABEL_GAP_PX = 2;
const PERPENDICULAR_SEARCH_STEP_PX = 8;
const PERPENDICULAR_CLEARANCE_PX = 1;
export const EDGE_STROKE_WIDTH_PX = 1.7;

/**
 * Browser-independent width heuristic for the 9–10px UML edge text. It uses a
 * fixed average glyph width plus the CSS horizontal padding; it is not a font measurement.
 */
export function estimateLabelWidth(text: string): number {
  return Array.from(text).length * ESTIMATED_LABEL_CHARACTER_WIDTH_PX + LABEL_HORIZONTAL_PADDING_PX;
}

function estimateLabelGroupWidth(texts: string[]): number {
  const visibleTexts = texts.filter(Boolean);
  return visibleTexts.reduce((width, text) => width + estimateLabelWidth(text), 0)
    + Math.max(0, visibleTexts.length - 1) * LABEL_GROUP_GAP_PX;
}

function expandRectangle(rectangle: EdgeRectangle, padding: number): EdgeRectangle {
  return {
    x: rectangle.x - padding,
    y: rectangle.y - padding,
    width: rectangle.width + padding * 2,
    height: rectangle.height + padding * 2,
  };
}

function rectanglesIntersect(left: EdgeRectangle, right: EdgeRectangle): boolean {
  return left.x < right.x + right.width
    && left.x + left.width > right.x
    && left.y < right.y + right.height
    && left.y + left.height > right.y;
}

function pathSegments(points: EdgePoint[]): Array<{ start: EdgePoint; end: EdgePoint; length: number; distance: number }> {
  const segments: Array<{ start: EdgePoint; end: EdgePoint; length: number; distance: number }> = [];
  let distance = 0;
  for (let index = 1; index < points.length; index += 1) {
    const start = points[index - 1]!;
    const end = points[index]!;
    const length = Math.hypot(end.x - start.x, end.y - start.y);
    if (length > 0) {
      segments.push({ start, end, length, distance });
      distance += length;
    }
  }
  return segments;
}

function pointAtPathDistance(points: EdgePoint[], distance: number): EdgePoint {
  const segments = pathSegments(points);
  const totalLength = segments.reduce((length, segment) => length + segment.length, 0);
  const requestedDistance = Math.max(0, Math.min(distance, totalLength));
  const segment = segments.find((candidate) => requestedDistance <= candidate.distance + candidate.length)
    ?? segments.at(-1);
  if (!segment) {
    return points[0] ?? { x: 0, y: 0 };
  }
  const fraction = (requestedDistance - segment.distance) / segment.length;
  return {
    x: segment.start.x + (segment.end.x - segment.start.x) * fraction,
    y: segment.start.y + (segment.end.y - segment.start.y) * fraction,
  };
}

function placementCandidates(
  points: EdgePoint[],
  preferredDistance: number,
  width: number,
  height: number,
  obstacles: EdgeRectangle[],
): number[] {
  const segments = pathSegments(points);
  const totalLength = segments.reduce((length, segment) => length + segment.length, 0);
  const candidates = new Set<number>([0, totalLength, Math.max(0, Math.min(preferredDistance, totalLength))]);

  for (const segment of segments) {
    const localDistances = new Set<number>([0, segment.length]);
    for (const obstacle of obstacles) {
      const centerBounds = {
        x: obstacle.x - width / 2,
        y: obstacle.y - height / 2,
        width: obstacle.width + width,
        height: obstacle.height + height,
      };
      const addCrossing = (axis: 'x' | 'y', boundary: number) => {
        const start = segment.start[axis];
        const delta = segment.end[axis] - start;
        if (delta === 0) {
          return;
        }
        const fraction = (boundary - start) / delta;
        if (fraction >= 0 && fraction <= 1) {
          localDistances.add(fraction * segment.length);
        }
      };
      addCrossing('x', centerBounds.x);
      addCrossing('x', centerBounds.x + centerBounds.width);
      addCrossing('y', centerBounds.y);
      addCrossing('y', centerBounds.y + centerBounds.height);
    }

    const sortedLocalDistances = [...localDistances].sort((left, right) => left - right);
    for (const localDistance of sortedLocalDistances) {
      candidates.add(segment.distance + localDistance);
    }
    for (let index = 1; index < sortedLocalDistances.length; index += 1) {
      const start = sortedLocalDistances[index - 1]!;
      const end = sortedLocalDistances[index]!;
      const preferredLocalDistance = Math.max(0, Math.min(preferredDistance - segment.distance, segment.length));
      candidates.add(segment.distance + (start + end) / 2);
      candidates.add(segment.distance + preferredLocalDistance);
    }
  }

  return [...candidates].sort((left, right) => Math.abs(left - preferredDistance) - Math.abs(right - preferredDistance));
}

function placeLabelOnPath(
  points: EdgePoint[],
  texts: string[],
  preferredDistance: number,
  obstacles: EdgeRectangle[],
): RelationshipLabelPlacement | null {
  const width = estimateLabelGroupWidth(texts);
  if (width <= 0) {
    return null;
  }
  const height = LABEL_HEIGHT_PX;
  for (const distance of placementCandidates(points, preferredDistance, width, height, obstacles)) {
    const point = pointAtPathDistance(points, distance);
    const estimatedBounds = {
      x: point.x - width / 2,
      y: point.y - height / 2,
      width,
      height,
    };
    if (obstacles.every((obstacle) => !rectanglesIntersect(estimatedBounds, obstacle))) {
      return { x: point.x, y: point.y, estimatedBounds };
    }
  }
  return null;
}

function placeLabelPerpendicularToPath(
  points: EdgePoint[],
  texts: string[],
  preferredDistance: number,
  obstacles: EdgeRectangle[],
): RelationshipLabelPlacement | null {
  const width = estimateLabelGroupWidth(texts);
  if (width <= 0) {
    return null;
  }

  const segments = pathSegments(points);
  const totalLength = segments.reduce((length, segment) => length + segment.length, 0);
  const distance = Math.max(0, Math.min(preferredDistance, totalLength));
  const segment = segments.find((candidate) => distance <= candidate.distance + candidate.length)
    ?? segments.at(-1);
  if (!segment) {
    return null;
  }

  const tangent = {
    x: (segment.end.x - segment.start.x) / segment.length,
    y: (segment.end.y - segment.start.y) / segment.length,
  };
  const normal = { x: -tangent.y, y: tangent.x };
  const anchor = pointAtPathDistance(points, distance);
  const height = LABEL_HEIGHT_PX;
  const offsetToClearObstacle = (obstacle: EdgeRectangle) => {
    const xOffset = Math.abs(normal.x) === 0
      ? Number.POSITIVE_INFINITY
      : (Math.abs(obstacle.x + obstacle.width / 2 - anchor.x)
        + (obstacle.width + width) / 2 + PERPENDICULAR_CLEARANCE_PX) / Math.abs(normal.x);
    const yOffset = Math.abs(normal.y) === 0
      ? Number.POSITIVE_INFINITY
      : (Math.abs(obstacle.y + obstacle.height / 2 - anchor.y)
        + (obstacle.height + height) / 2 + PERPENDICULAR_CLEARANCE_PX) / Math.abs(normal.y);
    return Math.min(xOffset, yOffset);
  };
  const maximumOffset = Math.max(0, ...obstacles.map(offsetToClearObstacle));
  const finalOffset = Math.max(
    PERPENDICULAR_SEARCH_STEP_PX,
    Math.ceil(maximumOffset / PERPENDICULAR_SEARCH_STEP_PX) * PERPENDICULAR_SEARCH_STEP_PX,
  );

  for (let offset = PERPENDICULAR_SEARCH_STEP_PX; offset <= finalOffset; offset += PERPENDICULAR_SEARCH_STEP_PX) {
    for (const direction of [1, -1]) {
      const point = {
        x: anchor.x + normal.x * offset * direction,
        y: anchor.y + normal.y * offset * direction,
      };
      const estimatedBounds = {
        x: point.x - width / 2,
        y: point.y - height / 2,
        width,
        height,
      };
      if (obstacles.every((obstacle) => !rectanglesIntersect(estimatedBounds, obstacle))) {
        return { x: point.x, y: point.y, estimatedBounds };
      }
    }
  }
  return null;
}

/**
 * Places each estimated label box on the polyline while keeping it at least 8px
 * from both node rectangles. End labels prefer the nearest safe point to their
 * endpoint; the relationship name prefers the path midpoint. If no on-path point
 * is safe, it offsets the unchanged label perpendicularly at that preferred
 * distance, searching both sides until it clears every node and placed label.
 * Omission is reserved for degenerate paths or the rare case where no tested
 * perpendicular offset can clear all obstacles; text is never shrunk or put over a class box.
 */
export function placeRelationshipLabels(
  points: EdgePoint[],
  sourceRectangle: EdgeRectangle,
  targetRectangle: EdgeRectangle,
  texts: RelationshipLabelTexts,
): RelationshipLabelPlacements {
  const nodeObstacles = [
    expandRectangle(sourceRectangle, LABEL_NODE_PADDING_PX),
    expandRectangle(targetRectangle, LABEL_NODE_PADDING_PX),
  ];
  const placedBoxes: EdgeRectangle[] = [];
  const pathLength = pathSegments(points).reduce((length, segment) => length + segment.length, 0);
  const place = (labelTexts: string[], preferredDistance: number) => {
    const visibleTexts = labelTexts.filter(Boolean);
    if (!visibleTexts.length) {
      return null;
    }
    const obstacles = [
      ...nodeObstacles,
      ...placedBoxes.map((box) => expandRectangle(box, LABEL_TO_LABEL_GAP_PX)),
    ];
    const placement = placeLabelOnPath(points, visibleTexts, preferredDistance, obstacles)
      ?? placeLabelPerpendicularToPath(points, visibleTexts, preferredDistance, obstacles);
    if (placement) {
      placedBoxes.push(placement.estimatedBounds);
    }
    return placement;
  };

  const source = place([texts.source], 0);
  const target = place([texts.target, texts.targetStereotype ?? ''], pathLength);
  const name = place([texts.name], pathLength / 2);
  return { source, target, name };
}

const HOLLOW_TRIANGLE_TIP = { x: 0, y: 0 } as const;
/**
 * The base sits at negative local x, which is opposite the direction of travel at the path
 * end. SVG orients the marker along that direction, so for an edge arriving at the target's
 * border from outside, the apex lands on the border and the base lands back along the line,
 * outside the node. That is the UML arrowhead.
 *
 * This was verified against the real render and it is correct: with the base at positive x
 * the triangle would land past the border, inside the box it is supposed to meet.
 */
const HOLLOW_TRIANGLE_BASE_LEFT = { x: -13, y: -7 } as const;
const HOLLOW_TRIANGLE_BASE_RIGHT = { x: -13, y: 7 } as const;

export const HOLLOW_TRIANGLE_MARKER = {
  viewBox: '-14 -8 15 16',
  refX: HOLLOW_TRIANGLE_TIP.x,
  refY: HOLLOW_TRIANGLE_TIP.y,
  markerWidth: 14,
  markerHeight: 16,
  orient: 'auto',
  markerUnits: 'strokeWidth',
  path: `M ${HOLLOW_TRIANGLE_TIP.x} ${HOLLOW_TRIANGLE_TIP.y} L ${HOLLOW_TRIANGLE_BASE_LEFT.x} ${HOLLOW_TRIANGLE_BASE_LEFT.y} L ${HOLLOW_TRIANGLE_BASE_RIGHT.x} ${HOLLOW_TRIANGLE_BASE_RIGHT.y} Z`,
} as const;

export interface HollowTriangleGeometry {
  pathEnd: EdgePoint;
  apex: EdgePoint;
  baseCenter: EdgePoint;
  orientationRadians: number;
}

/** Geometry of the SVG end marker after auto-orienting it to the final path segment. */
export function hollowTriangleGeometry(points: EdgePoint[]): HollowTriangleGeometry | null {
  const pathEnd = points.at(-1);
  if (!pathEnd) {
    return null;
  }
  let precedingPoint: EdgePoint | undefined;
  for (let index = points.length - 2; index >= 0; index -= 1) {
    const candidate = points[index]!;
    if (candidate.x !== pathEnd.x || candidate.y !== pathEnd.y) {
      precedingPoint = candidate;
      break;
    }
  }
  if (!precedingPoint) {
    return null;
  }

  const orientationRadians = Math.atan2(pathEnd.y - precedingPoint.y, pathEnd.x - precedingPoint.x);
  const baseCenter = {
    x: (HOLLOW_TRIANGLE_BASE_LEFT.x + HOLLOW_TRIANGLE_BASE_RIGHT.x) / 2,
    y: (HOLLOW_TRIANGLE_BASE_LEFT.y + HOLLOW_TRIANGLE_BASE_RIGHT.y) / 2,
  };
  const cosine = Math.cos(orientationRadians);
  const sine = Math.sin(orientationRadians);
  const transform = (point: EdgePoint): EdgePoint => ({
    x: pathEnd.x + (point.x * cosine - point.y * sine) * EDGE_STROKE_WIDTH_PX,
    y: pathEnd.y + (point.x * sine + point.y * cosine) * EDGE_STROKE_WIDTH_PX,
  });

  return {
    pathEnd: { ...pathEnd },
    apex: transform(HOLLOW_TRIANGLE_TIP),
    baseCenter: transform(baseCenter),
    orientationRadians,
  };
}

export function buildRelationshipEdgePath(points: EdgePoint[]): string {
  return points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ');
}

export interface RelationshipMarkerPresentation {
  lineStyle: 'solid' | 'dashed';
  sourceMarker: RelationshipMarker;
  targetMarker: RelationshipMarker;
  realizationLabelAtTarget: boolean;
}

export function relationshipMarkerPresentation(
  kind: string,
  ends: RelationshipEndResource[],
): RelationshipMarkerPresentation {
  const orderedEnds = [...ends].sort((left, right) => left.ordinal - right.ordinal);
  const sourceEnd = orderedEnds[0];
  const targetEnd = orderedEnds[1];
  const sourceNavigable = sourceEnd?.navigability === 'NAVIGABLE';
  const targetNavigable = targetEnd?.navigability === 'NAVIGABLE';

  // UML generalization and interface-realization triangles point at the parent; navigability does not add an arrowhead.
  switch (kind) {
    case 'AGGREGATION':
      return {
        lineStyle: 'solid',
        sourceMarker: sourceNavigable ? 'hollow-diamond-open-arrow' : 'hollow-diamond',
        targetMarker: targetNavigable ? 'open-arrow' : null,
        realizationLabelAtTarget: false,
      };
    case 'COMPOSITION':
      return {
        lineStyle: 'solid',
        sourceMarker: sourceNavigable ? 'filled-diamond-open-arrow' : 'filled-diamond',
        targetMarker: targetNavigable ? 'open-arrow' : null,
        realizationLabelAtTarget: false,
      };
    case 'GENERALIZATION':
      return { lineStyle: 'solid', sourceMarker: null, targetMarker: 'hollow-triangle', realizationLabelAtTarget: false };
    case 'INTERFACE_REALIZATION':
      return { lineStyle: 'dashed', sourceMarker: null, targetMarker: 'hollow-triangle', realizationLabelAtTarget: true };
    case 'DEPENDENCY':
      return {
        lineStyle: 'dashed',
        sourceMarker: sourceNavigable ? 'open-arrow' : null,
        targetMarker: targetNavigable ? 'open-arrow' : null,
        realizationLabelAtTarget: false,
      };
    case 'ASSOCIATION':
    default:
      return {
        lineStyle: 'solid',
        sourceMarker: sourceNavigable ? 'open-arrow' : null,
        targetMarker: targetNavigable ? 'open-arrow' : null,
        realizationLabelAtTarget: false,
      };
  }
}

export interface RelationshipEdgeStyle {
  routing: 'STRAIGHT' | 'FREE' | 'ORTHOGONAL';
  cornerRadius: number;
  strokeWidth: number;
  dashed: boolean;
  /** ROR-02: persisted auto-route-relative adjustment; null means the auto route. */
  routeAdjustment: RouteAdjustmentResource | null;
}

export const DEFAULT_EDGE_STYLE: RelationshipEdgeStyle = {
  routing: 'STRAIGHT',
  cornerRadius: 0,
  strokeWidth: 2,
  dashed: false,
  routeAdjustment: null,
};

export interface RelationshipEdgeData extends Record<string, unknown> {
  relationship: RelationshipResource;
  sourceEnd: RelationshipEndResource;
  targetEnd: RelationshipEndResource;
  waypoints: Array<{ x: number; y: number }>;
  /** IA4-07: per-view appearance; absent in older payloads. */
  style?: RelationshipEdgeStyle;
  onWaypointsChange?: (
    relationshipId: string,
    waypoints: Array<{ x: number; y: number }>,
    settled: boolean,
  ) => void;
  /** Opens the relationship context menu with this end preselected (label right-click). */
  onRelationshipContextMenu?: (
    event: MouseEvent<HTMLDivElement>,
    relationshipId: string,
    endOrdinal: number,
  ) => void;
  /** Reports this edge's endpoint anchors so a line right-click can preselect the nearest end. */
  onEdgeEndpointsChange?: (relationshipId: string, source: EdgePoint, target: EdgePoint) => void;
  /** ROR-06: applies one orthogonal handle drag/jog; settled when the pointer is released. */
  onRouteAdjustmentChange?: (
    relationshipId: string,
    adjustment: RouteAdjustmentResource | null,
    settled: boolean,
  ) => void;
}

export type RelationshipFlowEdge = Edge<RelationshipEdgeData, 'umlRelationship'>;

export function RelationshipEndLabels({
  sourceEnd,
  targetEnd,
  realizationLabelAtTarget = false,
}: {
  sourceEnd: RelationshipEndResource;
  targetEnd: RelationshipEndResource;
  realizationLabelAtTarget?: boolean;
}) {
  const sourceLabel = formatRelationshipEndLabel(sourceEnd);
  const targetLabel = formatRelationshipEndLabel(targetEnd);
  return (
    <div className="uml-edge-labels">
      {sourceLabel && <span className="uml-edge-end-label uml-edge-end-label--source">{sourceLabel}</span>}
      {targetLabel && <span className="uml-edge-end-label uml-edge-end-label--target">{targetLabel}</span>}
      {realizationLabelAtTarget && <span className="uml-edge-stereotype uml-edge-stereotype--target">«realize»</span>}
    </div>
  );
}

function RelationshipEndLabel({ end, side }: { end: RelationshipEndResource; side: 'source' | 'target' }) {
  const label = formatRelationshipEndLabel(end);
  return label ? <span className={`uml-edge-end-label uml-edge-end-label--${side}`}>{label}</span> : null;
}

/**
 * Interactive hit area for one relationship end's rendered label. The overlay
 * div it lives in is inert (`pointer-events: none`); this anchor opts back in
 * so a right-click on the label opens the relationship context menu with this
 * exact end preselected (source label = end A, target label = end B).
 */
export function RelationshipEndLabelAnchor({
  side,
  end,
  position,
  labelGroup = false,
  onContextMenu,
  children,
}: {
  side: 'source' | 'target';
  end: RelationshipEndResource;
  position: RelationshipLabelPlacement;
  /** Flex layout for label groups sharing one position (target end + stereotype). */
  labelGroup?: boolean;
  onContextMenu?: ((event: MouseEvent<HTMLDivElement>, endOrdinal: number) => void) | undefined;
  children?: ReactNode;
}) {
  const endOrdinal = endOrdinalForLabelSide(side);
  return (
    <div
      className={`uml-edge-label-position uml-edge-label-position--end${labelGroup ? ' uml-edge-labels' : ''}`}
      data-end-ordinal={endOrdinal}
      onContextMenu={(event) => {
        // The label is the anchor: this exact end is edited, and neither the
        // browser menu nor the pane menu may take over the right click.
        event.preventDefault();
        event.stopPropagation();
        onContextMenu?.(event, endOrdinal);
      }}
      style={{ transform: `translate(-50%, -50%) translate(${position.x}px, ${position.y}px)` }}
    >
      <RelationshipEndLabel end={end} side={side} />
      {children}
    </div>
  );
}

const MARKER_SUFFIXES: Record<Exclude<RelationshipMarker, null>, string> = {
  'open-arrow': 'open-arrow',
  'hollow-diamond': 'hollow-diamond',
  'filled-diamond': 'filled-diamond',
  'hollow-diamond-open-arrow': 'hollow-diamond-open-arrow',
  'filled-diamond-open-arrow': 'filled-diamond-open-arrow',
  'hollow-triangle': 'hollow-triangle',
};

function markerUrl(marker: RelationshipMarker, markerIds: Record<Exclude<RelationshipMarker, null>, string>): string | undefined {
  return marker ? `url(#${markerIds[marker]})` : undefined;
}

function pointAlongSegment(start: EdgePoint, end: EdgePoint, fraction: number): EdgePoint {
  return {
    x: start.x + (end.x - start.x) * fraction,
    y: start.y + (end.y - start.y) * fraction,
  };
}

function rectangleForInternalNode(node: {
  measured?: { width?: number; height?: number };
  width?: number;
  height?: number;
  internals?: { positionAbsolute: EdgePoint };
} | undefined): EdgeRectangle | null {
  const width = node?.measured?.width ?? node?.width;
  const height = node?.measured?.height ?? node?.height;
  const position = node?.internals?.positionAbsolute;
  if (!position || width === undefined || height === undefined || width <= 0 || height <= 0) {
    return null;
  }
  return { x: position.x, y: position.y, width, height };
}

export default function UmlRelationshipEdge(props: EdgeProps<RelationshipFlowEdge>) {
  const { screenToFlowPosition } = useReactFlow();
  const sourceNode = useInternalNode(props.source);
  const targetNode = useInternalNode(props.target);
  const markerPrefix = useId().replaceAll(':', '');
  const markerIds = Object.fromEntries(Object.entries(MARKER_SUFFIXES)
    .map(([marker, suffix]) => [marker, `${markerPrefix}-${suffix}`])) as Record<Exclude<RelationshipMarker, null>, string>;
  const data = props.data!;
  const relationship = data.relationship;
  const [waypoints, setWaypoints] = useState(data.waypoints);
  const dragIndex = useRef<number | null>(null);
  const waypointsRef = useRef(waypoints);
  const orderedEnds = [data.sourceEnd, data.targetEnd];
  const markers = relationshipMarkerPresentation(relationship.kind, orderedEnds);
  // ROR-06: local adjustment state so a drag re-renders from the pointer while a model
  // refresh re-syncs through the effect instead of fighting it (same pattern as waypoints).
  const [adjustment, setAdjustment] = useState<OrthoAdjustment | null>(data.style?.routeAdjustment ?? null);
  const adjustmentRef = useRef(adjustment);
  // A drag gesture is identified by a stable primitive key (`kind:index`), not by handle
  // object identity: orthogonalRoute(...) rebuilds the handle objects on every render, so
  // after the first move commits a re-render the next pointermove runs with a NEW handle
  // object and an identity check would reject it, freezing the route after a single step.
  const routeDragKey = (handle: OrthoHandle): string => `${handle.kind}:${handle.index}`;
  const routeDragHandle = useRef<string | null>(null);

  useEffect(() => {
    setWaypoints(data.waypoints);
    waypointsRef.current = data.waypoints;
  }, [data.waypoints]);

  useEffect(() => {
    // While a drag gesture is in flight the live pointer owns the adjustment: a model
    // refresh landing mid-drag with the pre-drag value must not rewind the base the next
    // pointer step is computed from (the route would snap back). Every other case re-syncs.
    if (routeDragHandle.current !== null) {
      return;
    }
    setAdjustment(data.style?.routeAdjustment ?? null);
    adjustmentRef.current = data.style?.routeAdjustment ?? null;
  }, [data.style?.routeAdjustment]);

  const points: EdgePoint[] = [
    { x: props.sourceX, y: props.sourceY },
    ...waypoints,
    { x: props.targetX, y: props.targetY },
  ];

  // Expose the endpoint anchors so the canvas can preselect the nearest end
  // when the line itself is right-clicked: only this component knows the
  // resolved sourceX/Y and targetX/Y of the rendered path.
  useEffect(() => {
    data.onEdgeEndpointsChange?.(
      relationship.id,
      { x: props.sourceX, y: props.sourceY },
      { x: props.targetX, y: props.targetY },
    );
  }, [data, props.sourceX, props.sourceY, props.targetX, props.targetY, relationship.id]);
  const style = data.style ?? DEFAULT_EDGE_STYLE;
  const sourceRectangle = rectangleForInternalNode(sourceNode);
  const targetRectangle = rectangleForInternalNode(targetNode);
  // Orthogonal routing regenerates the polyline client-side from the node rectangles and
  // the current hand adjustment; STRAIGHT and FREE render the persisted waypoint polyline
  // (existing behavior).
  const route = style.routing === 'ORTHOGONAL' && sourceRectangle && targetRectangle
    ? orthogonalRoute(sourceRectangle, targetRectangle, adjustment)
    : null;
  const orthogonalPoints = route?.points ?? null;
  const path = orthogonalPoints
    ? roundedOrthogonalPath(orthogonalPoints, style.cornerRadius)
    : buildRelationshipEdgePath(points);
  const labelPoints: EdgePoint[] = orthogonalPoints ?? points;
  const sourceLabel = formatRelationshipEndLabel(orderedEnds[0]!);
  const targetLabel = formatRelationshipEndLabel(orderedEnds[1]!);
  const labelPlacements = sourceRectangle && targetRectangle
    ? placeRelationshipLabels(labelPoints, sourceRectangle, targetRectangle, {
      source: sourceLabel,
      target: targetLabel,
      ...(markers.realizationLabelAtTarget ? { targetStereotype: '«realize»' } : {}),
      name: relationship.name ?? '',
    })
    : { source: null, target: null, name: null };
  const middle = pointAlongSegment(points[0]!, points[points.length - 1]!, 0.5);

  function updateWaypoint(event: PointerEvent<HTMLButtonElement>, index: number): void {
    if (dragIndex.current !== index) {
      return;
    }
    const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
    const next = waypointsRef.current.map((point, pointIndex) => pointIndex === index
      ? { x: roundToOrthoGrid(position.x), y: roundToOrthoGrid(position.y) }
      : point);
    waypointsRef.current = next;
    setWaypoints(next);
    data.onWaypointsChange?.(relationship.id, next, false);
  }

  function settleWaypoints(): void {
    const next = waypointsRef.current;
    dragIndex.current = null;
    data.onWaypointsChange?.(relationship.id, next, true);
  }

  function addWaypoint(): void {
    const next = [...waypointsRef.current, { x: roundToOrthoGrid(middle.x), y: roundToOrthoGrid(middle.y) }];
    waypointsRef.current = next;
    setWaypoints(next);
    data.onWaypointsChange?.(relationship.id, next, true);
  }

  function updateRouteHandle(event: PointerEvent<HTMLButtonElement>, handle: OrthoHandle): void {
    // Compare by stable key, not object identity: the handle object is rebuilt on every
    // render, so identity would stop the gesture after the first committed move.
    if (!route || routeDragHandle.current !== routeDragKey(handle)) {
      return;
    }
    const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
    const next = dragOrthoHandle(route, adjustmentRef.current, handle, position);
    adjustmentRef.current = next;
    setAdjustment(next);
    data.onRouteAdjustmentChange?.(relationship.id, next, false);
  }

  function settleRouteAdjustment(): void {
    routeDragHandle.current = null;
    data.onRouteAdjustmentChange?.(relationship.id, adjustmentRef.current, true);
  }

  function addRouteBend(): void {
    if (!route) {
      return;
    }
    const next = addJog(adjustmentRef.current, route.axis, defaultJogLevelOffset(route));
    adjustmentRef.current = next;
    setAdjustment(next);
    data.onRouteAdjustmentChange?.(relationship.id, next, true);
  }

  function removeRouteBend(): void {
    const current = adjustmentRef.current;
    if (!current || current.corridors.length <= 1) {
      return;
    }
    const next = removeJog(current);
    adjustmentRef.current = next;
    setAdjustment(next);
    data.onRouteAdjustmentChange?.(relationship.id, next, true);
  }

  return (
    <>
      <defs>
        <marker id={markerIds['open-arrow']} viewBox="0 -6 10 12" refX="0" refY="0" markerWidth="10" markerHeight="12" orient="auto-start-reverse" markerUnits="strokeWidth">
          <path d="M 0 -5 L 9 0 L 0 5" fill="none" stroke="#526b62" strokeWidth="1.4" />
        </marker>
        <marker id={markerIds['hollow-diamond']} viewBox="0 -7 14 14" refX="0" refY="0" markerWidth="14" markerHeight="14" orient="auto-start-reverse" markerUnits="strokeWidth">
          <path d="M 0 0 L 7 -6 L 14 0 L 7 6 Z" fill="white" stroke="#526b62" strokeWidth="1.4" />
        </marker>
        <marker id={markerIds['filled-diamond']} viewBox="0 -7 14 14" refX="0" refY="0" markerWidth="14" markerHeight="14" orient="auto-start-reverse" markerUnits="strokeWidth">
          <path d="M 0 0 L 7 -6 L 14 0 L 7 6 Z" fill="#526b62" stroke="#526b62" strokeWidth="1.4" />
        </marker>
        <marker id={markerIds['hollow-diamond-open-arrow']} viewBox="-26 -8 28 16" refX="0" refY="0" markerWidth="28" markerHeight="16" orient="auto-start-reverse" markerUnits="strokeWidth">
          <path d="M 9 -5 L 0 0 L 9 5" fill="none" stroke="#526b62" strokeWidth="1.4" />
          <path d="M -24 0 L -18 -6 L -12 0 L -18 6 Z" fill="white" stroke="#526b62" strokeWidth="1.4" />
        </marker>
        <marker id={markerIds['filled-diamond-open-arrow']} viewBox="-26 -8 28 16" refX="0" refY="0" markerWidth="28" markerHeight="16" orient="auto-start-reverse" markerUnits="strokeWidth">
          <path d="M 9 -5 L 0 0 L 9 5" fill="none" stroke="#526b62" strokeWidth="1.4" />
          <path d="M -24 0 L -18 -6 L -12 0 L -18 6 Z" fill="#526b62" stroke="#526b62" strokeWidth="1.4" />
        </marker>
        <marker
          id={markerIds['hollow-triangle']}
          viewBox={HOLLOW_TRIANGLE_MARKER.viewBox}
          refX={HOLLOW_TRIANGLE_MARKER.refX}
          refY={HOLLOW_TRIANGLE_MARKER.refY}
          markerWidth={HOLLOW_TRIANGLE_MARKER.markerWidth}
          markerHeight={HOLLOW_TRIANGLE_MARKER.markerHeight}
          orient={HOLLOW_TRIANGLE_MARKER.orient}
          markerUnits={HOLLOW_TRIANGLE_MARKER.markerUnits}
        >
          <path d={HOLLOW_TRIANGLE_MARKER.path} fill="white" stroke="#526b62" strokeWidth="1.4" />
        </marker>
      </defs>
      <BaseEdge
        id={props.id}
        path={path}
        {...(markers.sourceMarker ? { markerStart: markerUrl(markers.sourceMarker, markerIds)! } : {})}
        {...(markers.targetMarker ? { markerEnd: markerUrl(markers.targetMarker, markerIds)! } : {})}
        interactionWidth={20}
        style={{
          stroke: '#526b62',
          strokeWidth: style.strokeWidth > 0 ? style.strokeWidth : EDGE_STROKE_WIDTH_PX,
          ...((markers.lineStyle === 'dashed' || style.dashed) ? { strokeDasharray: '6 4' } : {}),
        }}
      />
      <EdgeLabelRenderer>
        {sourceLabel && labelPlacements.source && (
          <RelationshipEndLabelAnchor
            end={orderedEnds[0]!}
            onContextMenu={(event, endOrdinal) => data.onRelationshipContextMenu?.(event, relationship.id, endOrdinal)}
            position={labelPlacements.source}
            side="source"
          />
        )}
        {(targetLabel || markers.realizationLabelAtTarget) && labelPlacements.target && (
          <RelationshipEndLabelAnchor
            end={orderedEnds[1]!}
            labelGroup
            onContextMenu={(event, endOrdinal) => data.onRelationshipContextMenu?.(event, relationship.id, endOrdinal)}
            position={labelPlacements.target}
            side="target"
          >
            {markers.realizationLabelAtTarget && <span className="uml-edge-stereotype">«realize»</span>}
          </RelationshipEndLabelAnchor>
        )}
        {relationship.name && labelPlacements.name && (
          <div
            className="uml-edge-name uml-edge-label-position"
            style={{ transform: `translate(-50%, -50%) translate(${labelPlacements.name.x}px, ${labelPlacements.name.y}px)` }}
          >{relationship.name}</div>
        )}
        {props.selected && style.routing !== 'ORTHOGONAL' && (
          <>
            <button
              aria-label="Add connector bend"
              className="uml-waypoint-add nodrag nopan"
              onClick={addWaypoint}
              style={{ transform: `translate(-50%, -50%) translate(${middle.x}px, ${middle.y}px)` }}
              type="button"
            >+</button>
            {waypoints.map((waypoint, index) => (
              <button
                aria-label="Move connector bend"
                className="uml-waypoint-handle nodrag nopan"
                key={index}
                onPointerCancel={settleWaypoints}
                onPointerDown={(event) => {
                  event.preventDefault();
                  event.stopPropagation();
                  dragIndex.current = index;
                  event.currentTarget.setPointerCapture(event.pointerId);
                }}
                onPointerMove={(event) => updateWaypoint(event, index)}
                onPointerUp={settleWaypoints}
                style={{ transform: `translate(-50%, -50%) translate(${waypoint.x}px, ${waypoint.y}px)` }}
                type="button"
              />
            ))}
          </>
        )}
        {props.selected && style.routing === 'ORTHOGONAL' && sourceRectangle && targetRectangle && route && (
          <>
            <button
              aria-label="Add connector bend"
              className="uml-waypoint-add nodrag nopan"
              onClick={addRouteBend}
              style={{ transform: `translate(-50%, -50%) translate(${route.addBendAnchor.x}px, ${route.addBendAnchor.y}px)` }}
              type="button"
            >+</button>
            {route.corridors.length > 1 && (
              <button
                aria-label="Remove connector bend"
                className="uml-waypoint-add nodrag nopan"
                onClick={removeRouteBend}
                style={{
                  transform: `translate(-50%, -50%) translate(${
                    route.axis === 'H'
                      ? route.addBendAnchor.x
                      : route.addBendAnchor.x - ORTHO_GRID
                  }px, ${
                    route.axis === 'H'
                      ? route.addBendAnchor.y - ORTHO_GRID
                      : route.addBendAnchor.y
                  }px)`,
                }}
                type="button"
              >−</button>
            )}
            {[...route.corridorHandles, ...route.levelHandles].map((handle) => (
              <button
                aria-label={handle.kind === 'corridor' ? 'Move orthogonal corridor' : 'Move orthogonal level'}
                className="uml-waypoint-handle nodrag nopan"
                key={`${handle.kind}-${handle.index}`}
                onPointerCancel={settleRouteAdjustment}
                onPointerDown={(event) => {
                  event.preventDefault();
                  event.stopPropagation();
                  routeDragHandle.current = routeDragKey(handle);
                  event.currentTarget.setPointerCapture(event.pointerId);
                }}
                onPointerMove={(event) => updateRouteHandle(event, handle)}
                onPointerUp={settleRouteAdjustment}
                style={{ transform: `translate(-50%, -50%) translate(${handle.x}px, ${handle.y}px)` }}
                type="button"
              />
            ))}
          </>
        )}
      </EdgeLabelRenderer>
    </>
  );
}
