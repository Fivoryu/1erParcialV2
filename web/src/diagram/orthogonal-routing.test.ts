import { describe, expect, it } from 'vitest';
import { orthogonalWaypoints, roundedOrthogonalPath } from './orthogonal-routing';

const source = { x: 0, y: 0, width: 100, height: 100 };
const alignedRight = { x: 300, y: 0, width: 100, height: 100 };
const staggeredRight = { x: 300, y: 200, width: 100, height: 100 };
const alignedBelow = { x: 0, y: 300, width: 100, height: 100 };

describe('orthogonal route generator (pure, no rendering)', () => {
  it('is deterministic for the same rectangle pair', () => {
    expect(orthogonalWaypoints(source, staggeredRight))
      .toEqual(orthogonalWaypoints(source, staggeredRight));
  });

  it('routes horizontally between side-aligned rectangles with no bends', () => {
    expect(orthogonalWaypoints(source, alignedRight)).toEqual([
      { x: 100, y: 50 },
      { x: 300, y: 50 },
    ]);
  });

  it('routes vertically between vertically aligned rectangles with no bends', () => {
    expect(orthogonalWaypoints(source, alignedBelow)).toEqual([
      { x: 50, y: 100 },
      { x: 50, y: 300 },
    ]);
  });

  it('bends at the midpoint axis for staggered rectangles', () => {
    expect(orthogonalWaypoints(source, staggeredRight)).toEqual([
      { x: 100, y: 50 },
      { x: 200, y: 50 },
      { x: 200, y: 250 },
      { x: 300, y: 250 },
    ]);
  });
});

describe('rounded orthogonal path (pure, no rendering)', () => {
  it('is a plain polyline when the corner radius is zero', () => {
    expect(roundedOrthogonalPath([
      { x: 0, y: 0 },
      { x: 100, y: 0 },
      { x: 100, y: 50 },
    ], 0)).toBe('M 0 0 L 100 0 L 100 50');
  });

  it('cuts each corner with a deterministic quadratic curve', () => {
    expect(roundedOrthogonalPath([
      { x: 0, y: 0 },
      { x: 100, y: 0 },
      { x: 100, y: 50 },
    ], 10)).toBe('M 0 0 L 90 0 Q 100 0 100 10 L 100 50');
  });

  it('clamps the radius to half of the shorter adjacent segment', () => {
    expect(roundedOrthogonalPath([
      { x: 0, y: 0 },
      { x: 100, y: 0 },
      { x: 100, y: 50 },
    ], 1000)).toBe('M 0 0 L 75 0 Q 100 0 100 25 L 100 50');
  });
});

describe('orthogonal route generator degenerate inputs (pure, no rendering)', () => {
  it('does not throw for overlapping rectangles and stays deterministic', () => {
    const first = orthogonalWaypoints(source, source);
    expect(() => orthogonalWaypoints(source, source)).not.toThrow();
    expect(orthogonalWaypoints(source, source)).toEqual(first);
  });

  it('does not throw for zero-sized rectangles', () => {
    const zero = { x: 10, y: 10, width: 0, height: 0 } as const;
    expect(() => orthogonalWaypoints(zero, source)).not.toThrow();
    expect(() => orthogonalWaypoints(source, zero)).not.toThrow();
    expect(() => orthogonalWaypoints(zero, zero)).not.toThrow();
  });

  it('never emits NaN for polylines with repeated points', () => {
    const path = roundedOrthogonalPath([{ x: 0, y: 0 }, { x: 0, y: 0 }, { x: 50, y: 0 }], 10);
    expect(path).not.toContain('NaN');
    expect(path).toBe('M 0 0 L 0 0 L 50 0');
  });
});
