/**
 * IA4-07: deterministic orthogonal routing for relationship edges.
 *
 * Pure geometry only: no node-avoidance is promised. The route exits the source
 * rectangle on the axis of the greater center-to-center delta, crosses at the
 * midpoint of the remaining axis, and enters the target rectangle straight on.
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

/** Persisted, auto-route-relative adjustment: K corridors and K-1 levels (ROR-02). */
export interface OrthoAdjustment {
  axis: OrthoAxis;
  corridors: number[];
  levels: number[];
}

export interface OrthoHandle {
  index: number;
  x: number;
  y: number;
  dragAxis: 'x' | 'y';
  coordinate: number;
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
}

export const DEFAULT_BEND_SPACING = 48;

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
  const sourceCenter = { x: source.x + source.width / 2, y: source.y + source.height / 2 };
  const targetCenter = { x: target.x + target.width / 2, y: target.y + target.height / 2 };
  const deltaX = targetCenter.x - sourceCenter.x;
  const deltaY = targetCenter.y - sourceCenter.y;
  const axis: OrthoAxis = Math.abs(deltaX) >= Math.abs(deltaY) ? 'H' : 'V';

  const uS = axis === 'H'
    ? (deltaX >= 0 ? source.x + source.width : source.x)
    : (deltaY >= 0 ? source.y + source.height : source.y);
  const uT = axis === 'H'
    ? (deltaX >= 0 ? target.x : target.x + target.width)
    : (deltaY >= 0 ? target.y : target.y + target.height);
  const vS = axis === 'H' ? sourceCenter.y : sourceCenter.x;
  const vT = axis === 'H' ? targetCenter.y : targetCenter.x;

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
    });
  }

  const levelHandles: OrthoHandle[] = levels.map((v, j) => {
    const midU = (corridors[j]! + corridors[j + 1]!) / 2;
    return {
      index: j,
      ...(axis === 'H' ? { x: midU, y: v } : { x: v, y: midU }),
      dragAxis: axis === 'H' ? 'y' : 'x',
      coordinate: v,
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
  };
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
