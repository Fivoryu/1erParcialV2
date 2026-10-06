import { describe, expect, it } from 'vitest';
import {
  DEFAULT_BEND_SPACING,
  addJog,
  defaultJogLevelOffset,
  isAdjustmentApplicable,
  orthogonalRoute,
  orthogonalWaypoints,
  removeJog,
  roundedOrthogonalPath,
  slideCorridor,
  slideLevel,
} from './orthogonal-routing';

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

describe('adjustable orthogonal route model (pure, no rendering)', () => {
  const staggeredAdj = { axis: 'H' as const, corridors: [30, -40], levels: [10] };

  it('is deterministic for same-axis other-direction and identical rectangles', () => {
    const toTheLeft = { x: -300, y: 200, width: 100, height: 100 };
    expect(orthogonalRoute(source, toTheLeft)).toEqual(orthogonalRoute(source, toTheLeft));
    expect(orthogonalRoute(source, source)).toEqual(orthogonalRoute(source, source));
    expect(orthogonalRoute(staggeredRight, source)).toEqual(orthogonalRoute(staggeredRight, source));
    expect(orthogonalRoute(staggeredRight, source).axis).toBe('H');
  });

  it('moves every point by the same delta when both rectangles translate', () => {
    const base = orthogonalRoute(source, staggeredRight);
    const dx = 40;
    const dy = -15;
    const moved = orthogonalRoute(
      { x: source.x + dx, y: source.y + dy, width: 100, height: 100 },
      { x: staggeredRight.x + dx, y: staggeredRight.y + dy, width: 100, height: 100 },
    );
    expect(moved.points).toEqual(base.points.map((p) => ({ x: p.x + dx, y: p.y + dy })));
  });

  it('keeps corridor and level offsets relative when one rectangle moves', () => {
    const base = orthogonalRoute(source, staggeredRight, staggeredAdj);
    const moved = orthogonalRoute(
      { x: source.x + 50, y: source.y + 25, width: 100, height: 100 },
      staggeredRight,
      staggeredAdj,
    );
    expect(base.ignoredAdjustment).toBe(false);
    expect(moved.corridors[0]! - moved.autoCorridor).toBe(staggeredAdj.corridors[0]);
    expect(moved.corridors[1]! - moved.autoCorridor).toBe(staggeredAdj.corridors[1]);
    expect(moved.levels[0]! - moved.levelMid).toBe(staggeredAdj.levels[0]);
  });

  it('keeps every consecutive pair sharing exactly one coordinate over a grid', () => {
    const horizontalTargets = [
      { x: 300, y: 0, width: 100, height: 100 },
      { x: 300, y: 150, width: 100, height: 100 },
      { x: 250, y: -100, width: 100, height: 100 },
    ];
    const verticalTargets = [
      { x: 0, y: 300, width: 100, height: 100 },
      { x: 150, y: 300, width: 100, height: 100 },
      { x: -100, y: 250, width: 100, height: 100 },
    ];
    const offsetSets = [
      { corridors: [-30], levels: [] },
      { corridors: [0, 45], levels: [20] },
      { corridors: [-60, 10, 90], levels: [-15, 30] },
    ];
    for (const [axis, targets] of [['H', horizontalTargets], ['V', verticalTargets]] as const) {
      for (const target of targets) {
        for (const offsets of offsetSets) {
          const route = orthogonalRoute(source, target, { axis, corridors: offsets.corridors, levels: offsets.levels });
          expect(route.ignoredAdjustment).toBe(false);
          expect(route.points.length).toBeGreaterThan(1);
          for (let i = 1; i < route.points.length; i += 1) {
            const a = route.points[i - 1]!;
            const b = route.points[i]!;
            expect(a.x === b.x || a.y === b.y).toBe(true);
            expect(a.x !== b.x || a.y !== b.y).toBe(true);
          }
        }
      }
    }
  });

  it('offers no handles on the aligned case but a real midpoint anchor', () => {
    const route = orthogonalRoute(source, alignedRight);
    expect(route.points).toEqual([{ x: 100, y: 50 }, { x: 300, y: 50 }]);
    expect(route.corridorHandles).toEqual([]);
    expect(route.levelHandles).toEqual([]);
    expect(route.addBendAnchor).toEqual({ x: 200, y: 50 });
  });

  it('changes the route immediately when a jog is added', () => {
    const isStraight = (points: { x: number; y: number }[]): boolean => {
      for (let i = 2; i < points.length; i += 1) {
        const a = points[i - 2]!;
        const b = points[i - 1]!;
        const c = points[i]!;
        if ((b.x - a.x) * (c.y - b.y) !== (b.y - a.y) * (c.x - b.x)) {
          return false;
        }
      }
      return true;
    };
    const verticalStaggered = { x: 150, y: 300, width: 100, height: 100 };
    const bases = [
      { target: staggeredRight, basePoints: 4 },
      { target: alignedRight, basePoints: 2 },
      { target: alignedBelow, basePoints: 2 },
      { target: verticalStaggered, basePoints: 4 },
 ] as const;
    for (const { target, basePoints } of bases) {
      const base = orthogonalRoute(source, target);
      expect(base.points.length).toBe(basePoints);
      const jogged = orthogonalRoute(source, target, addJog(null, base.axis, defaultJogLevelOffset(base)));
      expect(jogged.ignoredAdjustment).toBe(false);
      expect(jogged.points.length).toBeGreaterThan(base.points.length);
      expect(isStraight(jogged.points)).toBe(false);
      expect(jogged.corridors.length).toBe(2);
      expect(jogged.levels.length).toBe(1);
      expect(jogged.levelHandles.length).toBe(1);
      expect(jogged.corridorHandles.length).toBe(2);
    }
  });

  it('defaults the jog level offset to bend spacing only for degenerate bases', () => {
    expect(defaultJogLevelOffset(orthogonalRoute(source, staggeredRight))).toBe(0);
    expect(defaultJogLevelOffset(orthogonalRoute(source, alignedRight))).toBe(DEFAULT_BEND_SPACING);
    expect(defaultJogLevelOffset(orthogonalRoute(source, alignedBelow))).toBe(DEFAULT_BEND_SPACING);
    expect(defaultJogLevelOffset(orthogonalRoute(source, { x: 150, y: 300, width: 100, height: 100 }))).toBe(0);
    const zero = { x: 10, y: 10, width: 0, height: 0 };
    expect(defaultJogLevelOffset(orthogonalRoute(zero, zero))).toBe(0);
  });

  it('removes the last jog, inverting addJog', () => {
    const once = addJog(null, 'H');
    const twice = addJog(once, 'H');
    expect(twice.corridors).toEqual([0, DEFAULT_BEND_SPACING, DEFAULT_BEND_SPACING * 2]);
    expect(twice.levels).toEqual([0, 0]);
    expect(removeJog(twice)).toEqual(once);
    const single = { axis: 'H' as const, corridors: [7], levels: [] };
    const kept = removeJog(single);
    expect(kept.corridors).toEqual([7]);
    expect(kept.levels).toEqual([]);
    expect(kept).not.toBe(single);
  });

  it('slides exactly the intended corridor or level coordinate', () => {
    const base = orthogonalRoute(source, staggeredRight);
    const adj = { axis: 'H' as const, corridors: [0], levels: [] };
    const slid = slideCorridor(adj, 0, base.autoCorridor + 25, base.autoCorridor);
    expect(slid.corridors).toEqual([25]);
    expect(adj.corridors).toEqual([0]);
    const routed = orthogonalRoute(source, staggeredRight, slid);
    expect(routed.corridors[0]).toBe(base.autoCorridor + 25);
    expect(routed.points[1]).toEqual({ x: base.autoCorridor + 25, y: 50 });

    const multi = { axis: 'H' as const, corridors: [0, DEFAULT_BEND_SPACING], levels: [0] };
    const levelSlid = slideLevel(multi, 0, base.levelMid - 12, base.levelMid);
    expect(levelSlid.levels).toEqual([-12]);
    expect(levelSlid.corridors).toEqual([0, DEFAULT_BEND_SPACING]);
    const levelRouted = orthogonalRoute(source, staggeredRight, levelSlid);
    expect(levelRouted.levels[0]).toBe(base.levelMid - 12);
    expect(levelRouted.points[2]).toEqual({ x: base.autoCorridor, y: base.levelMid - 12 });
  });

  it('ignores adjustments of the other axis without corrupting them', () => {
    const wrongAxis = { axis: 'V' as const, corridors: [10], levels: [] };
    const auto = orthogonalRoute(source, staggeredRight);
    const routed = orthogonalRoute(source, staggeredRight, wrongAxis);
    expect(routed.ignoredAdjustment).toBe(true);
    expect(routed.points).toEqual(auto.points);
    expect(isAdjustmentApplicable(wrongAxis, 'H')).toBe(false);
    const jogged = addJog(wrongAxis, 'H');
    expect(jogged.axis).toBe('H');
    expect(jogged.corridors).toEqual([0, DEFAULT_BEND_SPACING]);
    expect(wrongAxis).toEqual({ axis: 'V', corridors: [10], levels: [] });
    expect(removeJog(wrongAxis).corridors).toEqual([10]);
    expect(wrongAxis.corridors).toEqual([10]);
  });

  it('ignores malformed adjustments instead of throwing', () => {
    const mismatched = { axis: 'H' as const, corridors: [0, 10], levels: [] };
    const nonInteger = { axis: 'H' as const, corridors: [0.5], levels: [] };
    const auto = orthogonalRoute(source, staggeredRight);
    for (const bad of [mismatched, nonInteger]) {
      expect(isAdjustmentApplicable(bad, 'H')).toBe(false);
      const routed = orthogonalRoute(source, staggeredRight, bad);
      expect(routed.ignoredAdjustment).toBe(true);
      expect(routed.points).toEqual(auto.points);
    }
  });

  it('never throws for zero-sized or identical rectangles and stays deterministic', () => {
    const zero = { x: 10, y: 10, width: 0, height: 0 };
    const cases = [
      [zero, zero],
      [source, source],
      [zero, source],
      [source, zero],
    ] as const;
    for (const [a, b] of cases) {
      const first = orthogonalRoute(a, b);
      expect(orthogonalRoute(a, b)).toEqual(first);
      const adjusted = orthogonalRoute(a, b, { axis: 'H', corridors: [12], levels: [] });
      expect(orthogonalRoute(a, b, { axis: 'H', corridors: [12], levels: [] })).toEqual(adjusted);
    }
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
