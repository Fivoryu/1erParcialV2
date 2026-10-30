import { describe, expect, it } from 'vitest';
import { labelPoint, routeEdge, type Box, type Point } from './previewRouting';

const top: Box = { x: 0, y: 0, w: 200, h: 100 };
const middle: Box = { x: 0, y: 200, w: 200, h: 100 };
const bottom: Box = { x: 0, y: 400, w: 200, h: 100 };
const side: Box = { x: 400, y: 0, w: 200, h: 100 };

/** True when any part of the polyline enters the box interior (sampled every pixel). */
function crosses(points: Point[], box: Box): boolean {
  for (let index = 1; index < points.length; index++) {
    const a = points[index - 1]!;
    const b = points[index]!;
    const steps = Math.max(1, Math.ceil(Math.hypot(b.x - a.x, b.y - a.y)));
    for (let step = 0; step <= steps; step++) {
      const x = a.x + ((b.x - a.x) * step) / steps;
      const y = a.y + ((b.y - a.y) * step) / steps;
      if (x > box.x && x < box.x + box.w && y > box.y && y < box.y + box.h) return true;
    }
  }
  return false;
}

const onBorder = (point: Point, box: Box) => (
  (point.x === box.x || point.x === box.x + box.w) && point.y >= box.y && point.y <= box.y + box.h
) || ((point.y === box.y || point.y === box.y + box.h) && point.x >= box.x && point.x <= box.x + box.w);

describe('preview relationship routing', () => {
  it('draws a straight border-to-border line when no card is in the way', () => {
    const route = routeEdge(top, side, [middle, bottom]);
    expect(route).toEqual([{ x: 200, y: 50 }, { x: 400, y: 50 }]);
  });

  it('goes around a card that sits between both ends, with right-angle segments', () => {
    const route = routeEdge(top, bottom, [middle, side]);
    expect(route.length).toBeGreaterThan(2);
    expect(crosses(route, middle)).toBe(false);
    expect(crosses(route, side)).toBe(false);
    expect(onBorder(route[0]!, top)).toBe(true);
    expect(onBorder(route[route.length - 1]!, bottom)).toBe(true);
    for (let index = 1; index < route.length; index++) {
      const a = route[index - 1]!;
      const b = route[index]!;
      expect(a.x === b.x || a.y === b.y).toBe(true);
    }
  });

  it('finds the lanes between cards on a large diagram without crossing any of them', () => {
    const grid = Array.from({ length: 30 }, (_, index) => ({
      x: (index % 6) * 300, y: Math.floor(index / 6) * 220, w: 220, h: 140,
    }));
    const others = grid.slice(1, 29);
    const route = routeEdge(grid[0]!, grid[29]!, others);
    expect(route.length).toBeGreaterThan(2);
    for (const box of others) {
      expect(crosses(route, box)).toBe(false);
    }
  });

  it('loops a self-association over its own card corner', () => {
    const route = routeEdge(top, top, [middle]);
    expect(route[0]).toEqual({ x: 200, y: 25 });
    expect(route[route.length - 1]).toEqual({ x: 150, y: 0 });
    expect(crosses(route, top)).toBe(false);
  });

  it('labels the middle of the longest segment', () => {
    expect(labelPoint([{ x: 0, y: 0 }, { x: 0, y: 10 }, { x: 100, y: 10 }])).toEqual({ x: 50, y: 10 });
  });
});
