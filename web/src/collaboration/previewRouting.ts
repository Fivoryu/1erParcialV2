export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Point {
  x: number;
  y: number;
}

/** Free space kept between a route and any card it passes. */
const CLEARANCE = 6;
/** Grid spacing: 10px, coarser on big diagrams so a search never visits more than about 6000 cells. */
const MIN_STEP = 10;
const MAX_CELLS = 6000;
const MARGIN = 40;
const BEND_COST = 4;

function center(box: Box): Point {
  return { x: box.x + box.w / 2, y: box.y + box.h / 2 };
}

function inflate(box: Box, by: number): Box {
  return { x: box.x - by, y: box.y - by, w: box.w + by * 2, h: box.h + by * 2 };
}

function contains(box: Box, point: Point): boolean {
  return point.x > box.x && point.x < box.x + box.w && point.y > box.y && point.y < box.y + box.h;
}

/** Liang–Barsky clip: does the segment pass through the box interior? */
function segmentHitsBox(a: Point, b: Point, box: Box): boolean {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  let low = 0;
  let high = 1;
  const edges: Array<[number, number]> = [
    [-dx, a.x - box.x], [dx, box.x + box.w - a.x], [-dy, a.y - box.y], [dy, box.y + box.h - a.y],
  ];
  for (const [p, q] of edges) {
    if (p === 0) {
      if (q <= 0) return false;
      continue;
    }
    const t = q / p;
    if (p < 0) low = Math.max(low, t);
    else high = Math.min(high, t);
    if (low >= high) return false;
  }
  return true;
}

/** Where the segment from inside the box towards `outside` crosses its border. */
function exitPoint(box: Box, inside: Point, outside: Point): Point {
  const dx = outside.x - inside.x;
  const dy = outside.y - inside.y;
  const candidates = [
    dx > 0 ? (box.x + box.w - inside.x) / dx : dx < 0 ? (box.x - inside.x) / dx : Infinity,
    dy > 0 ? (box.y + box.h - inside.y) / dy : dy < 0 ? (box.y - inside.y) / dy : Infinity,
  ];
  const t = Math.min(1, ...candidates.filter((value) => value >= 0));
  return { x: inside.x + dx * t, y: inside.y + dy * t };
}

function simplify(points: Point[]): Point[] {
  return points.filter((point, index) => {
    const before = points[index - 1];
    const after = points[index + 1];
    if (!before || !after) return true;
    return !((before.x === point.x && point.x === after.x) || (before.y === point.y && point.y === after.y));
  });
}

/** A self-association: a small loop over the card's top-right corner. */
function selfLoop(box: Box): Point[] {
  const right = box.x + box.w;
  const join = box.x + box.w * 0.75;
  return [
    { x: right, y: box.y + box.h * 0.25 }, { x: right + 24, y: box.y + box.h * 0.25 },
    { x: right + 24, y: box.y - 24 }, { x: join, y: box.y - 24 }, { x: join, y: box.y },
  ];
}

/**
 * Orthogonal grid search with a bend penalty that avoids every card except the two endpoints.
 * Returns centre-to-centre grid points, or null when the cards wall every path off.
 */
function searchGrid(from: Box, to: Box, obstacles: Box[], area: Box, step: number): Point[] | null {
  const columns = Math.ceil(area.w / step) + 1;
  const rows = Math.ceil(area.h / step) + 1;
  const blocked = new Uint8Array(columns * rows);
  const toCell = (point: Point) => [
    Math.min(columns - 1, Math.max(0, Math.round((point.x - area.x) / step))),
    Math.min(rows - 1, Math.max(0, Math.round((point.y - area.y) / step))),
  ] as const;
  // Rasterize each obstacle over just the grid points strictly inside it.
  for (const box of obstacles) {
    const firstColumn = Math.max(0, Math.floor((box.x - area.x) / step) + 1);
    const lastColumn = Math.min(columns - 1, Math.ceil((box.x + box.w - area.x) / step) - 1);
    const firstRow = Math.max(0, Math.floor((box.y - area.y) / step) + 1);
    const lastRow = Math.min(rows - 1, Math.ceil((box.y + box.h - area.y) / step) - 1);
    for (let row = firstRow; row <= lastRow; row++) {
      blocked.fill(1, row * columns + firstColumn, row * columns + lastColumn + 1);
    }
  }
  const [startColumn, startRow] = toCell(center(from));
  const [goalColumn, goalRow] = toCell(center(to));
  const directions = [[1, 0], [-1, 0], [0, 1], [0, -1]] as const;
  // State = cell * 5 + incoming direction (4 = none yet), so a bend can be priced.
  const cost = new Float64Array(columns * rows * 5).fill(Infinity);
  const previous = new Int32Array(columns * rows * 5).fill(-1);
  const closed = new Uint8Array(columns * rows * 5);
  // Binary min-heap over two typed arrays (priority, state); it grows by doubling.
  let priorities = new Float64Array(1024);
  let states = new Int32Array(1024);
  let size = 0;
  const swap = (left: number, right: number) => {
    const priority = priorities[left]!;
    priorities[left] = priorities[right]!;
    priorities[right] = priority;
    const state = states[left]!;
    states[left] = states[right]!;
    states[right] = state;
  };
  const push = (priority: number, state: number) => {
    if (size === priorities.length) {
      const grownPriorities = new Float64Array(size * 2);
      grownPriorities.set(priorities);
      priorities = grownPriorities;
      const grownStates = new Int32Array(size * 2);
      grownStates.set(states);
      states = grownStates;
    }
    priorities[size] = priority;
    states[size] = state;
    for (let index = size++; index > 0;) {
      const parent = (index - 1) >> 1;
      if (priorities[parent]! <= priorities[index]!) break;
      swap(parent, index);
      index = parent;
    }
  };
  const pop = () => {
    const top = states[0]!;
    size--;
    priorities[0] = priorities[size]!;
    states[0] = states[size]!;
    for (let index = 0; ;) {
      const left = index * 2 + 1;
      const right = left + 1;
      let smallest = index;
      if (left < size && priorities[left]! < priorities[smallest]!) smallest = left;
      if (right < size && priorities[right]! < priorities[smallest]!) smallest = right;
      if (smallest === index) break;
      swap(smallest, index);
      index = smallest;
    }
    return top;
  };
  const heuristic = (column: number, row: number) => Math.abs(column - goalColumn) + Math.abs(row - goalRow);
  const start = (startRow * columns + startColumn) * 5 + 4;
  cost[start] = 0;
  push(heuristic(startColumn, startRow), start);
  while (size > 0) {
    const state = pop();
    // A state can be queued several times; with a consistent heuristic the first pop is final.
    if (closed[state]) continue;
    closed[state] = 1;
    const cell = Math.floor(state / 5);
    const direction = state % 5;
    const column = cell % columns;
    const row = Math.floor(cell / columns);
    if (column === goalColumn && row === goalRow) {
      const cells: Point[] = [];
      for (let current = state; current !== -1; current = previous[current]!) {
        const at = Math.floor(current / 5);
        cells.push({ x: area.x + (at % columns) * step, y: area.y + Math.floor(at / columns) * step });
      }
      return cells.reverse();
    }
    for (let next = 0; next < 4; next++) {
      const [dx, dy] = directions[next]!;
      const nextColumn = column + dx;
      const nextRow = row + dy;
      if (nextColumn < 0 || nextRow < 0 || nextColumn >= columns || nextRow >= rows) continue;
      const nextCell = nextRow * columns + nextColumn;
      if (blocked[nextCell]) continue;
      const nextState = nextCell * 5 + next;
      const nextCost = cost[state]! + 1 + (direction !== 4 && direction !== next ? BEND_COST : 0);
      if (nextCost < cost[nextState]!) {
        cost[nextState] = nextCost;
        previous[nextState] = state;
        push(nextCost + heuristic(nextColumn, nextRow), nextState);
      }
    }
  }
  return null;
}

/**
 * Routes a relationship between two cards so it never crosses another card: a straight line when
 * the way is clear, otherwise an orthogonal detour. The route starts and ends on the card borders.
 */
export function routeEdge(from: Box, to: Box, others: Box[]): Point[] {
  if (from === to || (from.x === to.x && from.y === to.y && from.w === to.w && from.h === to.h)) {
    return selfLoop(from);
  }
  const start = center(from);
  const end = center(to);
  const obstacles = others.map((box) => inflate(box, CLEARANCE));
  if (!obstacles.some((box) => segmentHitsBox(start, end, box))) {
    return [exitPoint(from, start, end), exitPoint(to, end, start)];
  }
  const all = [from, to, ...others];
  const left = Math.min(...all.map((box) => box.x)) - MARGIN;
  const top = Math.min(...all.map((box) => box.y)) - MARGIN;
  const area = {
    x: left, y: top,
    w: Math.max(...all.map((box) => box.x + box.w)) + MARGIN - left,
    h: Math.max(...all.map((box) => box.y + box.h)) + MARGIN - top,
  };
  const step = Math.max(MIN_STEP, Math.ceil(Math.sqrt((area.w * area.h) / MAX_CELLS)));
  // A coarse grid can miss a narrow lane between cards; only then pay for the fine one.
  const cells = searchGrid(from, to, obstacles, area, step)
    ?? (step > MIN_STEP ? searchGrid(from, to, obstacles, area, MIN_STEP) : null);
  if (!cells) {
    return [exitPoint(from, start, end), exitPoint(to, end, start)];
  }
  // Keep the cells outside both endpoint cards and join them to the borders orthogonally.
  const outside = cells.filter((point) => !contains(from, point) && !contains(to, point));
  const first = outside[0];
  const last = outside[outside.length - 1];
  if (!first || !last) {
    return [exitPoint(from, start, end), exitPoint(to, end, start)];
  }
  const onBorder = (box: Box, point: Point): Point => ({
    x: Math.min(box.x + box.w, Math.max(box.x, point.x)),
    y: Math.min(box.y + box.h, Math.max(box.y, point.y)),
  });
  return simplify([onBorder(from, first), ...outside, onBorder(to, last)]);
}

/** Where to put a route's label: the middle of its longest segment. */
export function labelPoint(points: Point[]): Point {
  let best = { a: points[0]!, b: points[1] ?? points[0]!, length: -1 };
  for (let index = 1; index < points.length; index++) {
    const a = points[index - 1]!;
    const b = points[index]!;
    const length = Math.abs(b.x - a.x) + Math.abs(b.y - a.y);
    if (length > best.length) best = { a, b, length };
  }
  return { x: (best.a.x + best.b.x) / 2, y: (best.a.y + best.b.y) / 2 };
}
