/**
 * IA4-07: deterministic orthogonal routing for relationship edges.
 *
 * Pure geometry only: no node-avoidance is promised. The route exits the source
 * rectangle on the axis of the greater center-to-center delta, crosses at the
 * midpoint of the remaining axis, and enters the target rectangle straight on.
 *
 * Relationship ends may carry explicit anchors (ANC-02..05): a side plus a signed
 * offset from that side's centre. Same-axis anchor pairs keep the staircase with
 * the endpoints pinned to the anchor points; perpendicular pairs collapse to a
 * single forced corner (no degrees of freedom).
 */
export interface OrthoPoint {
  x: number;
  y: number;
}

export interface OrthoRectangle {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Full polyline (endpoints included) between the two rectangle borders. */
export function orthogonalWaypoints(source: OrthoRectangle, target: OrthoRectangle): OrthoPoint[] {
  return orthogonalRoute(source, target).points;
}

export type OrthoAxis = 'H' | 'V';

/** Side of a rectangle a relationship end attaches to (N=top, E=right, S=bottom, W=left). */
export type AnchorSide = 'N' | 'E' | 'S' | 'W';

/**
 * A stored anchor: side plus a signed integer offset in px measured from the
 * CENTRE of that side (positive toward +x for N/S and toward +y for E/W; ANC-02).
 */
export interface RelationshipAnchor {
  side: AnchorSide;
  offset: number;
}

/** Kept clear of the corners so the side of a border point stays unambiguous (ANC-03). */
export const ANCHOR_CORNER_MARGIN = 12;

const ANCHOR_SIDES: readonly AnchorSide[] = ['N', 'E', 'S', 'W'];

function isAnchorSide(value: unknown): value is AnchorSide {
  return typeof value === 'string' && (ANCHOR_SIDES as readonly string[]).includes(value);
}

function isValidAnchor(value: unknown): value is RelationshipAnchor {
  return (
    !!value &&
    typeof value === 'object' &&
    isAnchorSide((value as RelationshipAnchor).side) &&
    Number.isInteger((value as RelationshipAnchor).offset)
  );
}

/** Per-end extraction: a missing or malformed end simply means the auto side. */
function resolveAnchorEnd(anchors: unknown, end: 'source' | 'target'): RelationshipAnchor | null {
  if (!anchors || typeof anchors !== 'object') {
    return null;
  }
  const value = (anchors as Record<string, unknown>)[end];
  return isValidAnchor(value) ? value : null;
}

/**
 * The side each end uses when no anchor is stored: the historical choice, i.e. the
 * dominant axis between the two centres with the direction of travel applied.
 */
export function autoAnchorSide(source: OrthoRectangle, target: OrthoRectangle, end: 'source' | 'target'): AnchorSide {
  const sourceCenter = { x: source.x + source.width / 2, y: source.y + source.height / 2 };
  const targetCenter = { x: target.x + target.width / 2, y: target.y + target.height / 2 };
  const deltaX = targetCenter.x - sourceCenter.x;
  const deltaY = targetCenter.y - sourceCenter.y;
  const horizontal = Math.abs(deltaX) >= Math.abs(deltaY);
  if (end === 'source') {
    return horizontal ? (deltaX >= 0 ? 'E' : 'W') : deltaY >= 0 ? 'S' : 'N';
  }
  return horizontal ? (deltaX >= 0 ? 'W' : 'E') : deltaY >= 0 ? 'N' : 'S';
}

/** Clamps a raw travel-axis coordinate into the margin window of a side; a side too
 * short to hold the margin degrades to its centre (never NaN, never a negative bound). */
function clampToSide(raw: number, start: number, length: number): number {
  const centre = start + length / 2;
  const min = start + ANCHOR_CORNER_MARGIN;
  const max = start + length - ANCHOR_CORNER_MARGIN;
  if (max < min) {
    return centre;
  }
  return Math.min(max, Math.max(min, raw));
}

/**
 * Absolute border point for one end: the stored anchor when present and valid,
 * otherwise the auto side's centre. Returns the effective side (which may differ
 * from a malformed anchor's side) and the clamped point.
 */
export function anchorPoint(
  rectangle: OrthoRectangle,
  anchor: RelationshipAnchor | null | undefined,
  autoSide: AnchorSide,
): { side: AnchorSide; x: number; y: number } {
  const valid = isValidAnchor(anchor);
  const side = valid ? anchor!.side : autoSide;
  const offset = valid ? anchor!.offset : 0;
  switch (side) {
    case 'N':
      return { side, x: clampToSide(rectangle.x + rectangle.width / 2 + offset, rectangle.x, rectangle.width), y: rectangle.y };
    case 'S':
      return { side, x: clampToSide(rectangle.x + rectangle.width / 2 + offset, rectangle.x, rectangle.width), y: rectangle.y + rectangle.height };
    case 'E':
      return { side, x: rectangle.x + rectangle.width, y: clampToSide(rectangle.y + rectangle.height / 2 + offset, rectangle.y, rectangle.height) };
    case 'W':
      return { side, x: rectangle.x, y: clampToSide(rectangle.y + rectangle.height / 2 + offset, rectangle.y, rectangle.height) };
  }
}

/**
 * Nearest point of the rectangle border to a pointer position, expressed as a
 * stored anchor with the same clamp and margin as anchorPoint (ANC-03). Total:
 * any position yields a valid anchor; near a corner the closer side wins, which
 * is the "slip along the side and hop to the neighbour" behaviour.
 */
export function projectAnchorOnBorder(rectangle: OrthoRectangle, position: OrthoPoint): RelationshipAnchor {
  const right = rectangle.x + rectangle.width;
  const bottom = rectangle.y + rectangle.height;
  const within = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));
  const candidates: { side: AnchorSide; x: number; y: number }[] = [
    { side: 'N', x: within(position.x, rectangle.x, right), y: rectangle.y },
    { side: 'E', x: right, y: within(position.y, rectangle.y, bottom) },
    { side: 'S', x: within(position.x, rectangle.x, right), y: bottom },
    { side: 'W', x: rectangle.x, y: within(position.y, rectangle.y, bottom) },
  ];
  let best = candidates[0]!;
  let bestDistance = Math.hypot(position.x - best.x, position.y - best.y);
  for (const candidate of candidates.slice(1)) {
    const distance = Math.hypot(position.x - candidate.x, position.y - candidate.y);
    if (distance < bestDistance) {
      best = candidate;
      bestDistance = distance;
    }
  }
  const raw = best.side === 'N' || best.side === 'S'
    ? best.x - (rectangle.x + rectangle.width / 2)
    : best.y - (rectangle.y + rectangle.height / 2);
  const halfLength = (best.side === 'N' || best.side === 'S' ? rectangle.width : rectangle.height) / 2;
  const bound = halfLength - ANCHOR_CORNER_MARGIN;
  const offset = bound < 0 ? 0 : Math.min(bound, Math.max(-bound, raw));
  return { side: best.side, offset };
}

/** Persisted, auto-route-relative adjustment: K corridors and K-1 levels (ROR-02),
 * plus the optional relationship-end anchors (ANC-01). */
export interface OrthoAdjustment {
  axis: OrthoAxis;
  corridors: number[];
  levels: number[];
  /** Optional: a missing or partial value means the auto side (the side centre). */
  anchors?: { source: RelationshipAnchor; target: RelationshipAnchor } | null;
}

export interface OrthoHandle {
  index: number;
  x: number;
  y: number;
  dragAxis: 'x' | 'y';
  coordinate: number;
  /** Which route element this handle moves; picks slideCorridor vs slideLevel on drag. */
  kind: 'corridor' | 'level';
}

export interface OrthoRoute {
  axis: OrthoAxis;
  points: OrthoPoint[];
  autoCorridor: number;
  levelMid: number;
  /** Absolute corridor coordinates c_i on the travel axis. */
  corridors: number[];
  /** Absolute level coordinates v_j on the perpendicular axis. */
  levels: number[];
  corridorHandles: OrthoHandle[];
  levelHandles: OrthoHandle[];
  addBendAnchor: OrthoPoint;
  ignoredAdjustment: boolean;
  /** Whether both resolved sides share an axis (staircase) or force a single corner. */
  pair: 'same-axis' | 'perpendicular';
  /** Resolved absolute border point where the route leaves the source rectangle. */
  sourceAnchor: { side: AnchorSide; x: number; y: number };
  /** Resolved absolute border point where the route enters the target rectangle. */
  targetAnchor: { side: AnchorSide; x: number; y: number };
}

export const DEFAULT_BEND_SPACING = 48;

/** Shared snap grid: FREE waypoints and orthogonal handles land on the same lattice. */
export const ORTHO_GRID = 24;

export function roundToOrthoGrid(value: number): number {
  return Math.round(value / ORTHO_GRID) * ORTHO_GRID;
}

/**
 * An adjustment applies only when structurally valid (ROR-05 shape rules) and
 * its stored axis equals the current dominant axis (ROR-04). Anything else
 * falls back to the auto route.
 */
export function isAdjustmentApplicable(adjustment: OrthoAdjustment | null | undefined, axis: OrthoAxis): boolean {
  if (!adjustment || typeof adjustment !== 'object' || adjustment.axis !== axis) {
    return false;
  }
  const { corridors, levels } = adjustment;
  if (!Array.isArray(corridors) || !Array.isArray(levels)) {
    return false;
  }
  if (corridors.length < 1 || levels.length !== corridors.length - 1) {
    return false;
  }
  return [...corridors, ...levels].every((value) => Number.isInteger(value));
}

/** Drops consecutive duplicates and interior points collinear with both neighbours. */
function collapseOrthoPoints(points: OrthoPoint[]): OrthoPoint[] {
  const deduped: OrthoPoint[] = [];
  for (const point of points) {
    const last = deduped[deduped.length - 1];
    if (!last || last.x !== point.x || last.y !== point.y) {
      deduped.push(point);
    }
  }
  const result: OrthoPoint[] = [];
  for (let index = 0; index < deduped.length; index += 1) {
    if (index > 0 && index < deduped.length - 1) {
      const previous = deduped[index - 1]!;
      const current = deduped[index]!;
      const next = deduped[index + 1]!;
      const cross = (current.x - previous.x) * (next.y - current.y) - (current.y - previous.y) * (next.x - current.x);
      if (cross === 0) {
        continue;
      }
    }
    result.push(deduped[index]!);
  }
  return result;
}

function pointAtHalfArcLength(points: OrthoPoint[]): OrthoPoint {
  const first = points[0] ?? { x: 0, y: 0 };
  if (points.length < 2) {
    return first;
  }
  let total = 0;
  for (let index = 1; index < points.length; index += 1) {
    total += Math.hypot(points[index]!.x - points[index - 1]!.x, points[index]!.y - points[index - 1]!.y);
  }
  if (total === 0) {
    return first;
  }
  let remaining = total / 2;
  for (let index = 1; index < points.length; index += 1) {
    const previous = points[index - 1]!;
    const next = points[index]!;
    const segment = Math.hypot(next.x - previous.x, next.y - previous.y);
    if (remaining <= segment) {
      const t = segment === 0 ? 0 : remaining / segment;
      return { x: previous.x + (next.x - previous.x) * t, y: previous.y + (next.y - previous.y) * t };
    }
    remaining -= segment;
  }
  return points[points.length - 1]!;
}

/**
 * Frame-relative orthogonal route (ROR-03): a staircase that exits the source
 * on the dominant axis, runs through K corridors and K-1 levels, and enters
 * the target straight on. The horizontal frame maps u to x; the vertical frame
 * mirrors it. With no adjustment (K = 1, no levels) this reproduces the
 * original two-bend generator point by point.
 */
export function orthogonalRoute(
  source: OrthoRectangle,
  target: OrthoRectangle,
  adjustment?: OrthoAdjustment | null,
): OrthoRoute {
  const resolvedSource = anchorPoint(source, resolveAnchorEnd(adjustment?.anchors, 'source'), autoAnchorSide(source, target, 'source'));
  const resolvedTarget = anchorPoint(target, resolveAnchorEnd(adjustment?.anchors, 'target'), autoAnchorSide(source, target, 'target'));
  const sourceVertical = resolvedSource.side === 'N' || resolvedSource.side === 'S';
  const targetVertical = resolvedTarget.side === 'N' || resolvedTarget.side === 'S';

  // Perpendicular pair (ANC-04): no orthogonal degrees of freedom, one forced
  // corner where the two anchor lines cross. Corridors/levels never apply here.
  if (sourceVertical !== targetVertical) {
    const axis: OrthoAxis = sourceVertical ? 'V' : 'H';
    const corner = {
      x: sourceVertical ? resolvedSource.x : resolvedTarget.x,
      y: sourceVertical ? resolvedTarget.y : resolvedSource.y,
    };
    const points = collapseOrthoPoints([
      { x: resolvedSource.x, y: resolvedSource.y },
      corner,
      { x: resolvedTarget.x, y: resolvedTarget.y },
    ]);
    return {
      axis,
      points,
      autoCorridor: axis === 'H' ? (resolvedSource.x + resolvedTarget.x) / 2 : (resolvedSource.y + resolvedTarget.y) / 2,
      levelMid: axis === 'H' ? (resolvedSource.y + resolvedTarget.y) / 2 : (resolvedSource.x + resolvedTarget.x) / 2,
      corridors: [],
      levels: [],
      corridorHandles: [],
      levelHandles: [],
      addBendAnchor: pointAtHalfArcLength(points),
      ignoredAdjustment: adjustment != null,
      pair: 'perpendicular',
      sourceAnchor: resolvedSource,
      targetAnchor: resolvedTarget,
    };
  }

  // Same-axis pair: the staircase keeps its shape, but the endpoints come from
  // the anchors (travel-axis coordinate for u, perpendicular coordinate for v).
  const axis: OrthoAxis = sourceVertical ? 'V' : 'H';
  const uS = axis === 'H' ? resolvedSource.x : resolvedSource.y;
  const vS = axis === 'H' ? resolvedSource.y : resolvedSource.x;
  const uT = axis === 'H' ? resolvedTarget.x : resolvedTarget.y;
  const vT = axis === 'H' ? resolvedTarget.y : resolvedTarget.x;

  const autoCorridor = (uS + uT) / 2;
  const levelMid = (vS + vT) / 2;
  const applied = isAdjustmentApplicable(adjustment ?? null, axis);
  const corridorOffsets = applied && adjustment ? adjustment.corridors : [0];
  const levelOffsets = applied && adjustment ? adjustment.levels : [];
  const corridors = corridorOffsets.map((offset) => autoCorridor + offset);
  const levels = levelOffsets.map((offset) => levelMid + offset);

  const toPoint = (u: number, v: number): OrthoPoint => (axis === 'H' ? { x: u, y: v } : { x: v, y: u });

  const frame: OrthoPoint[] = [toPoint(uS, vS)];
  let currentV = vS;
  for (let i = 0; i < corridors.length; i += 1) {
    frame.push(toPoint(corridors[i]!, currentV));
    currentV = i < levels.length ? levels[i]! : vT;
    frame.push(toPoint(corridors[i]!, currentV));
  }
  frame.push(toPoint(uT, vT));
  const points = collapseOrthoPoints(frame);

  const corridorHandles: OrthoHandle[] = [];
  for (let i = 0; i < corridors.length; i += 1) {
    const vStart = i === 0 ? vS : levels[i - 1]!;
    const vEnd = i === corridors.length - 1 ? vT : levels[i]!;
    if (vStart === vEnd) {
      continue; // degenerate segment: nothing visible to drag
    }
    const u = corridors[i]!;
    const midV = (vStart + vEnd) / 2;
    corridorHandles.push({
      index: i,
      ...(axis === 'H' ? { x: u, y: midV } : { x: midV, y: u }),
      dragAxis: axis === 'H' ? 'x' : 'y',
      coordinate: u,
      kind: 'corridor',
    });
  }

  const levelHandles: OrthoHandle[] = levels.map((v, j) => {
    const midU = (corridors[j]! + corridors[j + 1]!) / 2;
    return {
      index: j,
      ...(axis === 'H' ? { x: midU, y: v } : { x: v, y: midU }),
      dragAxis: axis === 'H' ? 'y' : 'x',
      coordinate: v,
      kind: 'level' as const,
    };
  });

  return {
    axis,
    points,
    autoCorridor,
    levelMid,
    corridors,
    levels,
    corridorHandles,
    levelHandles,
    addBendAnchor: pointAtHalfArcLength(points),
    ignoredAdjustment: adjustment != null && !applied,
    pair: 'same-axis',
    sourceAnchor: resolvedSource,
    targetAnchor: resolvedTarget,
  };
}

/**
 * Applies one handle drag as an auto-route-relative adjustment (ROR-06). A drag on
 * the untouched auto route materializes the first hand adjustment; a null, other-axis,
 * or malformed input does the same (the auto route IS the one-corridor shape). The
 * pointer coordinate along the handle's drag axis is snapped to ORTHO_GRID FIRST and
 * the reference (autoCorridor/levelMid) is subtracted afterwards, so the rendered
 * absolute corridor/level lands exactly on the grid even though the references are
 * midpoints that need not be. Out-of-range indices are a no-op (slideCorridor/
 * slideLevel skip them); the inputs are never mutated.
 */
export function dragOrthoHandle(
  route: OrthoRoute,
  adjustment: OrthoAdjustment | null,
  handle: OrthoHandle,
  position: OrthoPoint,
): OrthoAdjustment {
  const base: OrthoAdjustment = adjustment && isAdjustmentApplicable(adjustment, route.axis)
    ? { axis: adjustment.axis, corridors: [...adjustment.corridors], levels: [...adjustment.levels] }
    : { axis: route.axis, corridors: [0], levels: [] };
  const absolute = roundToOrthoGrid(handle.dragAxis === 'x' ? position.x : position.y);
  return handle.kind === 'corridor'
    ? slideCorridor(base, handle.index, absolute, route.autoCorridor)
    : slideLevel(base, handle.index, absolute, route.levelMid);
}

/** Appends a jog (corridor + level). Null or other-axis input starts a fresh same-axis adjustment. */
export function addJog(adjustment: OrthoAdjustment | null, axis: OrthoAxis, levelOffset = 0): OrthoAdjustment {
  if (!adjustment || adjustment.axis !== axis) {
    return { axis, corridors: [0, DEFAULT_BEND_SPACING], levels: [levelOffset] };
  }
  const corridors = [...adjustment.corridors];
  const last = corridors[corridors.length - 1] ?? 0;
  corridors.push(last + DEFAULT_BEND_SPACING);
  return { axis, corridors, levels: [...adjustment.levels, levelOffset] };
}

/**
 * Perpendicular level offset a new jog needs so the '+' bends the route
 * immediately: when the base corridor segment is degenerate (vS === vT, the
 * aligned case) a zero level would collapse back into a straight line, so the
 * level starts one bend spacing away from the endpoints' line. The endpoints of
 * the collapsed polyline stay pinned to vS/vT regardless of any adjustment, so
 * deriving them from the first/last point is exact.
 */
export function defaultJogLevelOffset(route: OrthoRoute): number {
  const first = route.points[0];
  const last = route.points[route.points.length - 1];
  if (!first || !last || route.points.length < 2) {
    return 0;
  }
  const vS = route.axis === 'H' ? first.y : first.x;
  const vT = route.axis === 'H' ? last.y : last.x;
  return vS === vT ? DEFAULT_BEND_SPACING : 0;
}

/** Drops the last corridor and level; a single-corridor adjustment stays as is. */
export function removeJog(adjustment: OrthoAdjustment): OrthoAdjustment {
  if (adjustment.corridors.length <= 1) {
    return { axis: adjustment.axis, corridors: [...adjustment.corridors], levels: [...adjustment.levels] };
  }
  return {
    axis: adjustment.axis,
    corridors: adjustment.corridors.slice(0, -1),
    levels: adjustment.levels.slice(0, -1),
  };
}

export function slideCorridor(
  adjustment: OrthoAdjustment,
  index: number,
  absoluteCoordinate: number,
  autoCorridor: number,
): OrthoAdjustment {
  const corridors = [...adjustment.corridors];
  if (index >= 0 && index < corridors.length) {
    corridors[index] = Math.round(absoluteCoordinate - autoCorridor);
  }
  return { axis: adjustment.axis, corridors, levels: [...adjustment.levels] };
}

export function slideLevel(
  adjustment: OrthoAdjustment,
  index: number,
  absoluteCoordinate: number,
  levelMid: number,
): OrthoAdjustment {
  const levels = [...adjustment.levels];
  if (index >= 0 && index < levels.length) {
    levels[index] = Math.round(absoluteCoordinate - levelMid);
  }
  return { axis: adjustment.axis, corridors: [...adjustment.corridors], levels };
}

/**
 * SVG path for an orthogonal polyline with quadratic-rounded corners. A radius of
 * zero (or degenerate polylines) yields the plain polyline. The radius is clamped
 * to half of each corner's shorter adjacent segment so corners never overlap.
 */
export function roundedOrthogonalPath(points: OrthoPoint[], cornerRadius: number): string {
  if (points.length === 0) {
    return '';
  }
  const radius = Math.max(0, cornerRadius);
  let path = `M ${points[0]!.x} ${points[0]!.y}`;
  for (let index = 1; index < points.length - 1; index += 1) {
    const previous = points[index - 1]!;
    const vertex = points[index]!;
    const next = points[index + 1]!;
    const incoming = Math.hypot(vertex.x - previous.x, vertex.y - previous.y);
    const outgoing = Math.hypot(next.x - vertex.x, next.y - vertex.y);
    const clamped = Math.min(radius, incoming / 2, outgoing / 2);
    const before = {
      x: vertex.x - ((vertex.x - previous.x) / incoming) * clamped,
      y: vertex.y - ((vertex.y - previous.y) / incoming) * clamped,
    };
    const after = {
      x: vertex.x + ((next.x - vertex.x) / outgoing) * clamped,
      y: vertex.y + ((next.y - vertex.y) / outgoing) * clamped,
    };
    if (clamped > 0) {
      path += ` L ${before.x} ${before.y} Q ${vertex.x} ${vertex.y} ${after.x} ${after.y}`;
    } else {
      path += ` L ${vertex.x} ${vertex.y}`;
    }
  }
  const last = points[points.length - 1]!;
  if (points.length > 1) {
    path += ` L ${last.x} ${last.y}`;
  }
  return path;
}
