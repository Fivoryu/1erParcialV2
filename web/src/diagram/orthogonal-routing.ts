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

/**
 * How far the route leaves a side before it may turn (T5). Without this stub the route
 * would use the anchor coordinates only and would cut straight through the classes
 * whenever the two chosen sides do not face each other.
 */
export const ANCHOR_STUB = 24;

/** Outward direction of a side: `N` points up, `E` right, and so on. */
export interface AnchorNormal {
  axis: 'x' | 'y';
  sign: 1 | -1;
}

export function anchorNormal(side: AnchorSide): AnchorNormal {
  switch (side) {
    case 'N':
      return { axis: 'y', sign: -1 };
    case 'S':
      return { axis: 'y', sign: 1 };
    case 'W':
      return { axis: 'x', sign: -1 };
    case 'E':
      return { axis: 'x', sign: 1 };
  }
}

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

/**
 * T5: an orthogonal route may not enter a class. A segment merely touching a border is
 * allowed; crossing the interior is not. A vertical or horizontal segment is a degenerate
 * box, so the perpendicular axis is tested against the OPEN interval: testing the box
 * overlap instead would miss a line running straight through the middle of a class.
 */
function segmentEntersRectangle(a: OrthoPoint, b: OrthoPoint, rectangle: OrthoRectangle): boolean {
  const left = rectangle.x;
  const right = rectangle.x + rectangle.width;
  const top = rectangle.y;
  const bottom = rectangle.y + rectangle.height;
  const xOverlap = Math.min(Math.max(a.x, b.x), right) - Math.max(Math.min(a.x, b.x), left);
  const yOverlap = Math.min(Math.max(a.y, b.y), bottom) - Math.max(Math.min(a.y, b.y), top);
  if (a.x === b.x) {
    return a.x > left && a.x < right && yOverlap > 1e-9;
  }
  if (a.y === b.y) {
    return a.y > top && a.y < bottom && xOverlap > 1e-9;
  }
  return xOverlap > 1e-9 && yOverlap > 1e-9;
}

/** True when any segment of the polyline cuts through one of the rectangles. */
function routeEntersRectangles(points: OrthoPoint[], rectangles: OrthoRectangle[]): boolean {
  for (let index = 1; index < points.length; index += 1) {
    for (const rectangle of rectangles) {
      if (segmentEntersRectangle(points[index - 1]!, points[index]!, rectangle)) {
        return true;
      }
    }
  }
  return false;
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
 * An adjustment the user really shaped by hand: more than the single corridor at offset zero the auto route
 * has. One that only stores the connected sides is not a manual route, so losing it costs the user nothing.
 */
function hasManualShape(adjustment: OrthoAdjustment | null | undefined): boolean {
  return Boolean(adjustment) && !(adjustment!.corridors.length === 1 && adjustment!.corridors[0] === 0
    && adjustment!.levels.length === 0);
}

/**
 * True when the user bent this route by hand and the router is not drawing those bends any more, because a
 * class was moved so the dominant axis flipped, or another class now stands in the way. The bends are still
 * stored and come back if the layout does, which is why the editor says so instead of dropping them silently.
 */
export function manualRouteDropped(route: OrthoRoute, adjustment: OrthoAdjustment | null | undefined): boolean {
  return route.ignoredAdjustment && hasManualShape(adjustment);
}

/** The adjustment without the hand-made bends: back to the auto shape, keeping the connected sides if any. */
export function withoutManualShape(adjustment: OrthoAdjustment | null | undefined): OrthoAdjustment | null {
  if (!adjustment?.anchors) {
    return null;
  }
  return { axis: adjustment.axis, corridors: [0], levels: [], anchors: adjustment.anchors };
}

/** Above this many grid cells the search is skipped; the router then falls back to its plain route. */
const MAX_SEARCH_GRID_CELLS = 40_000;

/** A binary min-heap, so the search takes the cheapest frontier entry without re-sorting every step. */
class MinHeap<T> {
  private readonly items: T[] = [];

  constructor(private readonly priority: (item: T) => number) {}

  get size(): number {
    return this.items.length;
  }

  push(item: T): void {
    this.items.push(item);
    let index = this.items.length - 1;
    while (index > 0) {
      const parent = (index - 1) >> 1;
      if (this.priority(this.items[parent]!) <= this.priority(this.items[index]!)) {
        break;
      }
      [this.items[parent], this.items[index]] = [this.items[index]!, this.items[parent]!];
      index = parent;
    }
  }

  pop(): T | undefined {
    const top = this.items[0];
    const last = this.items.pop();
    if (last !== undefined && this.items.length > 0) {
      this.items[0] = last;
      let index = 0;
      for (;;) {
        const left = index * 2 + 1;
        const right = left + 1;
        let smallest = index;
        if (left < this.items.length && this.priority(this.items[left]!) < this.priority(this.items[smallest]!)) {
          smallest = left;
        }
        if (right < this.items.length && this.priority(this.items[right]!) < this.priority(this.items[smallest]!)) {
          smallest = right;
        }
        if (smallest === index) {
          break;
        }
        [this.items[smallest], this.items[index]] = [this.items[index]!, this.items[smallest]!];
        index = smallest;
      }
    }
    return top;
  }
}

/**
 * Last resort when no simple route clears the classes in the way: the shortest orthogonal path between
 * two points over the grid that the classes' borders (plus some room) and the two points themselves
 * define, with the fewest bends first and the shortest length second. It never enters a class's
 * interior, and it returns null when none exists (the target is walled in).
 */
function searchOrthogonalPath(from: OrthoPoint, to: OrthoPoint, blockers: readonly OrthoRectangle[]): OrthoPoint[] | null {
  const room = ANCHOR_STUB / 2;
  const unique = (values: number[]) => [...new Set(values)].sort((left, right) => left - right);
  const xs = unique([from.x, to.x, ...blockers.flatMap((rectangle) => [rectangle.x - room, rectangle.x + rectangle.width + room])]);
  const ys = unique([from.y, to.y, ...blockers.flatMap((rectangle) => [rectangle.y - room, rectangle.y + rectangle.height + room])]);
  const start = { i: xs.indexOf(from.x), j: ys.indexOf(from.y) };
  const goal = { i: xs.indexOf(to.x), j: ys.indexOf(to.y) };
  const directions = [{ di: 1, dj: 0 }, { di: -1, dj: 0 }, { di: 0, dj: 1 }, { di: 0, dj: -1 }];
  const BEND = 1_000_000;
  const key = (i: number, j: number, direction: number) => `${i},${j},${direction}`;
  const best = new Map<string, number>();
  const previous = new Map<string, string>();
  // A grid this large means a diagram this crowded: keep the plain fallback instead of a slow search.
  if (xs.length * ys.length > MAX_SEARCH_GRID_CELLS) {
    return null;
  }
  const queue = new MinHeap<{ i: number; j: number; direction: number; cost: number }>((entry) => entry.cost);
  queue.push({ ...start, direction: -1, cost: 0 });
  best.set(key(start.i, start.j, -1), 0);
  const free = (a: OrthoPoint, b: OrthoPoint) => blockers.every((rectangle) => !segmentEntersRectangle(a, b, rectangle));
  let finalKey: string | null = null;
  while (queue.size > 0) {
    const current = queue.pop()!;
    const currentKey = key(current.i, current.j, current.direction);
    if (current.cost > (best.get(currentKey) ?? Number.POSITIVE_INFINITY)) {
      continue;
    }
    if (current.i === goal.i && current.j === goal.j) {
      finalKey = currentKey;
      break;
    }
    directions.forEach(({ di, dj }, direction) => {
      const i = current.i + di;
      const j = current.j + dj;
      if (i < 0 || j < 0 || i >= xs.length || j >= ys.length) {
        return;
      }
      const a = { x: xs[current.i]!, y: ys[current.j]! };
      const b = { x: xs[i]!, y: ys[j]! };
      if (!free(a, b)) {
        return;
      }
      const cost = current.cost + Math.abs(b.x - a.x) + Math.abs(b.y - a.y)
        + (current.direction !== -1 && current.direction !== direction ? BEND : 0);
      const nextKey = key(i, j, direction);
      if (cost < (best.get(nextKey) ?? Number.POSITIVE_INFINITY)) {
        best.set(nextKey, cost);
        previous.set(nextKey, currentKey);
        queue.push({ i, j, direction, cost });
      }
    });
  }
  if (finalKey === null) {
    return null;
  }
  const path: OrthoPoint[] = [];
  for (let at: string | undefined = finalKey; at !== undefined; at = previous.get(at)) {
    const [i, j] = at.split(',').map(Number);
    path.unshift({ x: xs[i!]!, y: ys[j!]! });
  }
  return collapseOrthoPoints(path);
}

/** Distance between two relationships that share the same pair of classes. */
export const PARALLEL_LANE_SPACING = 16;

/** The offset of each end of one relationship along the side it leaves or enters through. */
export interface OrthoLane {
  source: number;
  target: number;
}

/**
 * One lane per end of every relationship, so relationships that attach to the same side of a
 * class fan out instead of lying on top of each other. They are ordered by where their other end
 * sits along that side's axis, which keeps them from crossing, and by the order given when that
 * ties, which keeps the relationships between one pair of classes parallel at both ends. A
 * relationship that shares its sides with nothing stays on their centre. A relationship of a class
 * with itself loops out from the corner, so its lane starts at zero and grows with each further one.
 */
export function sideLanes(
  relationships: readonly { id: string; sourceId: string; targetId: string }[],
  rectangles: ReadonlyMap<string, OrthoRectangle>,
): Map<string, OrthoLane> {
  const lanes = new Map<string, OrthoLane>();
  const selfCount = new Map<string, number>();
  const sides = new Map<string, { id: string; end: 'source' | 'target'; key: number; order: number }[]>();
  relationships.forEach(({ id, sourceId, targetId }, order) => {
    lanes.set(id, { source: 0, target: 0 });
    if (sourceId === targetId) {
      const index = selfCount.get(sourceId) ?? 0;
      selfCount.set(sourceId, index + 1);
      lanes.set(id, { source: index * PARALLEL_LANE_SPACING, target: index * PARALLEL_LANE_SPACING });
      return;
    }
    const source = rectangles.get(sourceId);
    const target = rectangles.get(targetId);
    if (!source || !target) {
      return;
    }
    for (const [end, own, other, ownId] of [['source', source, target, sourceId], ['target', target, source, targetId]] as const) {
      const side = autoAnchorSide(source, target, end);
      const key = side === 'E' || side === 'W' ? other.y + other.height / 2 : other.x + other.width / 2;
      const group = `${ownId}|${side}`;
      sides.set(group, [...(sides.get(group) ?? []), { id, end, key, order }]);
      void own;
    }
  });
  for (const group of sides.values()) {
    [...group]
      .sort((left, right) => left.key - right.key || left.order - right.order)
      .forEach(({ id, end }, index) => {
        lanes.get(id)![end] = (index - (group.length - 1) / 2) * PARALLEL_LANE_SPACING;
      });
  }
  return lanes;
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
  lane: number | OrthoLane = 0,
  obstacles: readonly OrthoRectangle[] = [],
): OrthoRoute {
  // The classes the route must not enter: its own two ends and every other class in the diagram.
  const laneOf = (end: 'source' | 'target') => (typeof lane === 'number' ? lane : lane[end]);
  const blockers = [source, target, ...obstacles];

  // A class related to itself: the UML loop out of the east side, round the top-right corner and
  // back into the north side. Each further self relationship (lane) swings wider than the last.
  if (source.width > 0 && source.height > 0
    && source.x === target.x && source.y === target.y && source.width === target.width && source.height === target.height) {
    const east = anchorPoint(source, { side: 'E', offset: -Math.round(source.height / 4) }, 'E');
    const north = anchorPoint(target, { side: 'N', offset: Math.round(target.width / 4) }, 'N');
    const reach = ANCHOR_STUB + Math.max(0, laneOf('source'));
    const points = [
      { x: east.x, y: east.y },
      { x: east.x + reach, y: east.y },
      { x: east.x + reach, y: north.y - reach },
      { x: north.x, y: north.y - reach },
      { x: north.x, y: north.y },
    ];
    return {
      axis: 'H',
      points,
      autoCorridor: east.x + reach,
      levelMid: north.y - reach,
      corridors: [],
      levels: [],
      corridorHandles: [],
      levelHandles: [],
      addBendAnchor: { x: east.x + reach, y: north.y - reach },
      ignoredAdjustment: adjustment != null,
      pair: 'perpendicular',
      sourceAnchor: east,
      targetAnchor: north,
    };
  }
  // An end without a stored anchor sits on its auto side, shifted by the lane so relationships
  // between the same two classes run side by side instead of on top of each other.
  const endAnchor = (end: 'source' | 'target'): RelationshipAnchor | null =>
    resolveAnchorEnd(adjustment?.anchors, end)
    ?? (laneOf(end) === 0 ? null : { side: autoAnchorSide(source, target, end), offset: laneOf(end) });
  const resolvedSource = anchorPoint(source, endAnchor('source'), autoAnchorSide(source, target, 'source'));
  const resolvedTarget = anchorPoint(target, endAnchor('target'), autoAnchorSide(source, target, 'target'));
  const normalSource = anchorNormal(resolvedSource.side);
  const normalTarget = anchorNormal(resolvedTarget.side);
  const sourceVertical = normalSource.axis === 'y';
  const targetVertical = normalTarget.axis === 'y';

  // T5: the route leaves the source along its own outward normal and enters the target
  // against its own normal. These stub points are what makes that possible; using the
  // anchor coordinates alone cut through the classes whenever the sides did not face.
  const stubOf = (point: { x: number; y: number }, normal: AnchorNormal) => (normal.axis === 'x'
    ? { x: point.x + normal.sign * ANCHOR_STUB, y: point.y }
    : { x: point.x, y: point.y + normal.sign * ANCHOR_STUB });
  const stubSource = stubOf(resolvedSource, normalSource);
  const stubTarget = stubOf(resolvedTarget, normalTarget);

  // Both ends attached to the same point: there is no route to draw. This is the
  // degenerate case (zero-sized or coincident rectangles) and must stay a single point
  // instead of the closed loop the detour below would otherwise produce.
  if (resolvedSource.x === resolvedTarget.x && resolvedSource.y === resolvedTarget.y) {
    const collapsedAxis: OrthoAxis = sourceVertical ? 'V' : 'H';
    return {
      axis: collapsedAxis,
      points: [{ x: resolvedSource.x, y: resolvedSource.y }],
      autoCorridor: collapsedAxis === 'H' ? resolvedSource.x : resolvedSource.y,
      levelMid: collapsedAxis === 'H' ? resolvedSource.y : resolvedSource.x,
      corridors: [],
      levels: [],
      corridorHandles: [],
      levelHandles: [],
      addBendAnchor: { x: resolvedSource.x, y: resolvedSource.y },
      ignoredAdjustment: adjustment != null,
      pair: 'same-axis',
      sourceAnchor: resolvedSource,
      targetAnchor: resolvedTarget,
    };
  }

  // Perpendicular pair: two corners are topologically valid (the source may turn right
  // after its stub, or after reaching the target's line). The first one that does not
  // cut a class wins; corridors/levels never apply here.
  if (sourceVertical !== targetVertical) {
    const pairAxis: OrthoAxis = sourceVertical ? 'V' : 'H';
    const cornerOptions = sourceVertical
      ? [{ x: stubSource.x, y: stubTarget.y }, { x: stubTarget.x, y: stubSource.y }]
      : [{ x: stubTarget.x, y: stubSource.y }, { x: stubSource.x, y: stubTarget.y }];
    const sourceAnchorPoint = { x: resolvedSource.x, y: resolvedSource.y };
    const targetAnchorPoint = { x: resolvedTarget.x, y: resolvedTarget.y };
    const options = cornerOptions.map((corner) => collapseOrthoPoints([
      sourceAnchorPoint,
      stubSource,
      corner,
      stubTarget,
      targetAnchorPoint,
    ]));
    const clear = options.find((candidate) => !routeEntersRectangles(candidate, blockers));
    // With other classes around and no simple corner clear of them, look for a way between the stubs.
    const detour = clear === undefined && obstacles.length > 0
      ? searchOrthogonalPath(stubSource, stubTarget, blockers)
      : null;
    const points = clear ?? (detour === null
      ? options[0]!
      : collapseOrthoPoints([sourceAnchorPoint, stubSource, ...detour, stubTarget, targetAnchorPoint]));
    return {
      axis: pairAxis,
      points,
      autoCorridor: pairAxis === 'H' ? (resolvedSource.x + resolvedTarget.x) / 2 : (resolvedSource.y + resolvedTarget.y) / 2,
      levelMid: pairAxis === 'H' ? (resolvedSource.y + resolvedTarget.y) / 2 : (resolvedSource.x + resolvedTarget.x) / 2,
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

  // Same-axis pair: the frame travels along the normals' axis. `u` is that axis and `v`
  // the perpendicular one; the staircase runs between the two stubs.
  const axis: OrthoAxis = sourceVertical ? 'V' : 'H';
  const uS = axis === 'H' ? resolvedSource.x : resolvedSource.y;
  const vS = axis === 'H' ? resolvedSource.y : resolvedSource.x;
  const uT = axis === 'H' ? resolvedTarget.x : resolvedTarget.y;
  const vT = axis === 'H' ? resolvedTarget.y : resolvedTarget.x;
  const uA = axis === 'H' ? stubSource.x : stubSource.y;
  const uB = axis === 'H' ? stubTarget.x : stubTarget.y;

  // The crossing line must lie beyond each stub in that side's outward direction, so the
  // route can leave and arrive without entering a class. Each positive normal sets a lower
  // bound, each negative normal an upper bound.
  const lowerBounds: number[] = [];
  const upperBounds: number[] = [];
  for (const [coordinate, sign] of [[uA, normalSource.sign], [uB, normalTarget.sign]] as const) {
    if (sign > 0) {
      lowerBounds.push(coordinate);
    } else {
      upperBounds.push(coordinate);
    }
  }
  const lower = lowerBounds.length > 0 ? Math.max(...lowerBounds) : null;
  const upper = upperBounds.length > 0 ? Math.min(...upperBounds) : null;

  const applied = isAdjustmentApplicable(adjustment ?? null, axis);
  const corridorOffsets = applied && adjustment ? adjustment.corridors : [0];
  const levelOffsets = applied && adjustment ? adjustment.levels : [];
  const levelMid = (vS + vT) / 2;
  const toPoint = (u: number, v: number): OrthoPoint => (axis === 'H' ? { x: u, y: v } : { x: v, y: u });

  const staircaseAt = (crossingValue: number): OrthoPoint[] => {
    const stairs = corridorOffsets.map((offset) => crossingValue + offset);
    const steps = levelOffsets.map((offset) => levelMid + offset);
    const frame: OrthoPoint[] = [toPoint(uS, vS), toPoint(uA, vS)];
    let currentV = vS;
    for (let i = 0; i < stairs.length; i += 1) {
      frame.push(toPoint(stairs[i]!, currentV));
      currentV = i < steps.length ? steps[i]! : vT;
      frame.push(toPoint(stairs[i]!, currentV));
    }
    frame.push(toPoint(uB, vT));
    frame.push(toPoint(uT, vT));
    return collapseOrthoPoints(frame);
  };

  const lateralAt = (lateral: number): OrthoPoint[] => collapseOrthoPoints([
    toPoint(uS, vS),
    toPoint(uA, vS),
    toPoint(uA, lateral),
    toPoint(uB, lateral),
    toPoint(uB, vT),
    toPoint(uT, vT),
  ]);

  // A single crossing line exists only when the bounds do not contradict each other. Equal
  // normals leave the crossing free beyond both stubs, so the other bound is a real second
  // option (both sides facing right, for instance, may cross past the nearer or the farther one).
  const crossingOptions: number[] = [];
  if (lower !== null && upper !== null) {
    if (lower <= upper) {
      crossingOptions.push((lower + upper) / 2);
    }
  } else if (lower !== null) {
    crossingOptions.push(lower);
  } else if (upper !== null) {
    crossingOptions.push(upper);
  }
  if (normalSource.sign === normalTarget.sign) {
    const alternative = normalSource.sign > 0 ? Math.min(uA, uB) : Math.max(uA, uB);
    if (!crossingOptions.includes(alternative)) {
      crossingOptions.push(alternative);
    }
  }

  const perpendicularAxisBounds = axis === 'H'
    ? { low: Math.min(source.y, target.y), high: Math.max(source.y + source.height, target.y + target.height) }
    : { low: Math.min(source.x, target.x), high: Math.max(source.x + source.width, target.x + target.width) };
  const lateralLow = perpendicularAxisBounds.low - ANCHOR_STUB;
  const lateralHigh = perpendicularAxisBounds.high + ANCHOR_STUB;
  // When the classes do not overlap on the perpendicular axis, the empty band between them is
  // the shortest place to cross: both stubs reach it without passing the other class. It is
  // what saves a pair whose sides face away from each other across a gap narrower than the stubs.
  const [sourceLow, sourceHigh] = axis === 'H' ? [source.y, source.y + source.height] : [source.x, source.x + source.width];
  const [targetLow, targetHigh] = axis === 'H' ? [target.y, target.y + target.height] : [target.x, target.x + target.width];
  const gapLateral = sourceHigh <= targetLow
    ? (sourceHigh + targetLow) / 2
    : targetHigh <= sourceLow ? (targetHigh + sourceLow) / 2 : null;
  const outerOrder = Math.abs((vS + vT) / 2 - lateralLow) <= Math.abs(lateralHigh - (vS + vT) / 2)
    ? [lateralLow, lateralHigh]
    : [lateralHigh, lateralLow];
  // With other classes around, any of their borders (plus a stub of room) is a place where a
  // detour can run, and so is a line past all of them. The nearest to the pair is tried first,
  // so the route threads between classes before it gives up and circles the whole diagram.
  const detourOrder = obstacles.length === 0 ? outerOrder : [...new Set(
    blockers.flatMap((rectangle) => (axis === 'H'
      ? [rectangle.y - ANCHOR_STUB, rectangle.y + rectangle.height + ANCHOR_STUB]
      : [rectangle.x - ANCHOR_STUB, rectangle.x + rectangle.width + ANCHOR_STUB])),
  )].sort((left, right) => Math.abs(left - (vS + vT) / 2) - Math.abs(right - (vS + vT) / 2));
  const lateralOrder = gapLateral === null ? detourOrder : [gapLateral, ...detourOrder];

  // An adjustment the user stored is an explicit instruction, so honour it with the primary
  // crossing: a bend they placed by hand must never be silently replaced. The exception is an
  // adjustment that changes nothing (one corridor at offset 0, no levels): that IS the auto
  // shape, so it goes through the safe search like any automatic route and can be rerouted
  // around a class.
  const trivialAdjustment = corridorOffsets.length === 1 && corridorOffsets[0] === 0
    && levelOffsets.length === 0;
  const primaryCrossing = crossingOptions[0] ?? null;
  const honoured = applied && primaryCrossing !== null && !trivialAdjustment;
  const candidates = honoured
    ? [{ points: staircaseAt(primaryCrossing), crossing: primaryCrossing as number | null }]
    : [
      ...crossingOptions.map((value) => ({ points: staircaseAt(value), crossing: value as number | null })),
      ...lateralOrder.map((lateral) => ({ points: lateralAt(lateral), crossing: null as number | null })),
    ];
  const clearCandidate = candidates.find((candidate) => !routeEntersRectangles(candidate.points, blockers));
  // A hand-placed route is kept as the user left it; an automatic one searches before giving up.
  const searched = clearCandidate === undefined && !honoured && obstacles.length > 0
    ? searchOrthogonalPath(stubSource, stubTarget, blockers)
    : null;
  const chosen = clearCandidate ?? (searched === null
    ? candidates[0]!
    : {
      points: collapseOrthoPoints([toPoint(uS, vS), ...searched, toPoint(uT, vT)]),
      crossing: null as number | null,
    });

  const autoCorridor = chosen.crossing ?? (uA + uB) / 2;
  const corridors = chosen.crossing === null ? [] : corridorOffsets.map((offset) => chosen.crossing! + offset);
  const levels = chosen.crossing === null ? [] : levelOffsets.map((offset) => levelMid + offset);
  const points = chosen.points;
  const adjustmentApplied = applied && chosen.crossing !== null;

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
    ignoredAdjustment: adjustment != null && !adjustmentApplied,
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

/**
 * Applies one anchor-dot drag (ANC-03): the dragged end's stored anchor becomes the
 * pointer position projected onto THAT end's own class border (projectAnchorOnBorder,
 * so sliding along a side and hopping to the neighbour at a corner come for free),
 * while the other end keeps its stored anchor and is otherwise materialized at the
 * route's resolved side centre. The base follows the same rules as dragOrthoHandle:
 * a null, other-axis, or malformed input materializes the one-corridor auto shape.
 * The result is always backend-valid — at least one corridor and exactly
 * corridors.length - 1 levels — so a perpendicular route (which reports corridors: [])
 * is repaired to [0]. The offset is rounded to a whole px (ANC-02) because a fractional
 * flow position would be rejected by the backend's strict integer validation. The
 * inputs are never mutated.
 */
export function dragAnchorHandle(
  route: OrthoRoute,
  adjustment: OrthoAdjustment | null,
  end: 'source' | 'target',
  position: OrthoPoint,
  rectangle: OrthoRectangle,
): OrthoAdjustment {
  const base: OrthoAdjustment = adjustment && isAdjustmentApplicable(adjustment, route.axis)
    ? {
      axis: adjustment.axis,
      corridors: [...adjustment.corridors],
      levels: [...adjustment.levels],
      ...(adjustment.anchors
        ? { anchors: { source: { ...adjustment.anchors.source }, target: { ...adjustment.anchors.target } } }
        : {}),
    }
    : { axis: route.axis, corridors: [0], levels: [] };
  const storedOther = adjustment?.anchors?.[end === 'source' ? 'target' : 'source'] ?? null;
  const otherAnchor: RelationshipAnchor = isValidAnchor(storedOther)
    ? { ...storedOther }
    : { side: end === 'source' ? route.targetAnchor.side : route.sourceAnchor.side, offset: 0 };
  const projected = projectAnchorOnBorder(rectangle, position);
  const dragged: RelationshipAnchor = { side: projected.side, offset: Math.round(projected.offset) };
  return {
    axis: base.axis,
    corridors: base.corridors.length >= 1 ? base.corridors : [0],
    levels: base.corridors.length >= 1 ? base.levels : [],
    anchors: end === 'source'
      ? { source: dragged, target: otherAnchor }
      : { source: otherAnchor, target: dragged },
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
