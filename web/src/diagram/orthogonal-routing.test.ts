import { describe, expect, it } from 'vitest';
import {
  ANCHOR_CORNER_MARGIN,
  DEFAULT_BEND_SPACING,
  ORTHO_GRID,
  addJog,
  anchorPoint,
  autoAnchorSide,
  defaultJogLevelOffset,
  dragAnchorHandle,
  dragOrthoHandle,
  isAdjustmentApplicable,
  orthogonalRoute,
  orthogonalWaypoints,
  projectAnchorOnBorder,
  removeJog,
  roundedOrthogonalPath,
  roundToOrthoGrid,
  slideCorridor,
  slideLevel,
  type OrthoAdjustment,
  type OrthoRoute,
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

describe('relationship end anchors (pure geometry, ANC-02..05)', () => {
  const rect = { x: 0, y: 0, width: 200, height: 100 };

  it('exposes the frozen corner margin', () => {
    expect(ANCHOR_CORNER_MARGIN).toBe(12);
  });

  it('autoAnchorSide reproduces the historical side choice exactly', () => {
    expect(autoAnchorSide(source, alignedRight, 'source')).toBe('E');
    expect(autoAnchorSide(source, alignedRight, 'target')).toBe('W');
    expect(autoAnchorSide(source, alignedBelow, 'source')).toBe('S');
    expect(autoAnchorSide(source, alignedBelow, 'target')).toBe('N');
    expect(autoAnchorSide(source, staggeredRight, 'source')).toBe('E');
    expect(autoAnchorSide(source, staggeredRight, 'target')).toBe('W');
    expect(autoAnchorSide(source, { x: -300, y: 200, width: 100, height: 100 }, 'source')).toBe('W');
    expect(autoAnchorSide(source, source, 'source')).toBe('E');
    expect(autoAnchorSide(source, source, 'target')).toBe('W');
  });

  it('anchorPoint applies signed offsets from the side centre (ANC-02)', () => {
    expect(anchorPoint(rect, { side: 'N', offset: 10 }, 'S')).toEqual({ side: 'N', x: 110, y: 0 });
    expect(anchorPoint(rect, { side: 'N', offset: -10 }, 'S')).toEqual({ side: 'N', x: 90, y: 0 });
    expect(anchorPoint(rect, { side: 'E', offset: 20 }, 'W')).toEqual({ side: 'E', x: 200, y: 70 });
    expect(anchorPoint(rect, { side: 'W', offset: -20 }, 'E')).toEqual({ side: 'W', x: 0, y: 30 });
    expect(anchorPoint(rect, { side: 'S', offset: 0 }, 'N')).toEqual({ side: 'S', x: 100, y: 100 });
  });

  it('anchorPoint clamps to the corner margin and degrades short sides to the centre', () => {
    expect(anchorPoint(rect, { side: 'N', offset: 1000 }, 'S')).toEqual({ side: 'N', x: 188, y: 0 });
    expect(anchorPoint(rect, { side: 'W', offset: -1000 }, 'E')).toEqual({ side: 'W', x: 0, y: 12 });
    expect(anchorPoint(rect, null, 'N')).toEqual({ side: 'N', x: 100, y: 0 });
    expect(anchorPoint(rect, undefined, 'E')).toEqual({ side: 'E', x: 200, y: 50 });
    const thin = { x: 0, y: 0, width: 10, height: 100 }; // width < 2 * margin
    expect(anchorPoint(thin, { side: 'N', offset: 5 }, 'S')).toEqual({ side: 'N', x: 5, y: 0 });
    const flat = { x: 0, y: 0, width: 200, height: 8 }; // height < 2 * margin
    expect(anchorPoint(flat, { side: 'E', offset: 4 }, 'W')).toEqual({ side: 'E', x: 200, y: 4 });
  });

  it('projectAnchorOnBorder returns the nearest side and round trips through anchorPoint', () => {
    expect(projectAnchorOnBorder(rect, { x: 100, y: -30 })).toEqual({ side: 'N', offset: 0 });
    expect(projectAnchorOnBorder(rect, { x: 230, y: 50 })).toEqual({ side: 'E', offset: 0 });
    expect(projectAnchorOnBorder(rect, { x: 100, y: 130 })).toEqual({ side: 'S', offset: 0 });
    expect(projectAnchorOnBorder(rect, { x: -30, y: 50 })).toEqual({ side: 'W', offset: 0 });
    const inside = projectAnchorOnBorder(rect, { x: 60, y: 20 });
    expect(inside).toEqual({ side: 'N', offset: -40 });
    expect(anchorPoint(rect, inside, 'S')).toEqual({ side: 'N', x: 60, y: 0 });
    const far = projectAnchorOnBorder(rect, { x: 100, y: -500 });
    expect(far).toEqual({ side: 'N', offset: 0 });
    const cornerish = projectAnchorOnBorder(rect, { x: 260, y: 200 });
    expect(cornerish.side).toBe('E');
    const back = anchorPoint(rect, cornerish, 'W');
    expect(back.side).toBe('E');
    expect(back.x).toBe(200);
    expect(back.y).toBeGreaterThanOrEqual(ANCHOR_CORNER_MARGIN);
    expect(back.y).toBeLessThanOrEqual(rect.height - ANCHOR_CORNER_MARGIN);
  });

  it('slips along a side and hops to the neighbour at the corner (ANC-03)', () => {
    const sweep = [
      { x: 100, y: -30 },
      { x: 230, y: 50 },
      { x: 100, y: 130 },
      { x: -30, y: 50 },
    ].map((p) => projectAnchorOnBorder(rect, p).side);
    expect(sweep).toEqual(['N', 'E', 'S', 'W']);
    expect(projectAnchorOnBorder(rect, { x: 230, y: -20 }).side).toBe('N');
    expect(projectAnchorOnBorder(rect, { x: 230, y: 20 }).side).toBe('E');
    expect(projectAnchorOnBorder(rect, { x: -30, y: -20 }).side).toBe('N');
    expect(projectAnchorOnBorder(rect, { x: -30, y: 20 }).side).toBe('W');
  });

  it('without anchors the route stays byte-identical and reports the auto pair', () => {
    const route = orthogonalRoute(source, alignedRight);
    expect(route.pair).toBe('same-axis');
    expect(route.sourceAnchor).toEqual({ side: 'E', x: 100, y: 50 });
    expect(route.targetAnchor).toEqual({ side: 'W', x: 300, y: 50 });
    expect(route.points).toEqual([{ x: 100, y: 50 }, { x: 300, y: 50 }]);
    expect(orthogonalRoute(source, alignedRight, { axis: 'H', corridors: [0], levels: [], anchors: null })).toEqual(route);
  });

  it('same-axis anchors pin the endpoints and keep corridors relative (ANC-02)', () => {
    const anchors = { source: { side: 'E' as const, offset: 25 }, target: { side: 'W' as const, offset: -25 } };
    const route = orthogonalRoute(source, alignedRight, { axis: 'H', corridors: [0], levels: [], anchors });
    expect(route.pair).toBe('same-axis');
    expect(route.axis).toBe('H');
    expect(route.sourceAnchor).toEqual({ side: 'E', x: 100, y: 75 });
    expect(route.targetAnchor).toEqual({ side: 'W', x: 300, y: 25 });
    expect(route.points).toEqual([
      { x: 100, y: 75 },
      { x: 200, y: 75 },
      { x: 200, y: 25 },
      { x: 300, y: 25 },
    ]);
    for (let i = 1; i < route.points.length; i += 1) {
      const a = route.points[i - 1]!;
      const b = route.points[i]!;
      expect(a.x === b.x || a.y === b.y).toBe(true);
      expect(a.x !== b.x || a.y !== b.y).toBe(true);
    }
    const shifted = orthogonalRoute(source, alignedRight, { axis: 'H', corridors: [40], levels: [], anchors });
    expect(shifted.points).toEqual([
      { x: 100, y: 75 },
      { x: 240, y: 75 },
      { x: 240, y: 25 },
      { x: 300, y: 25 },
    ]);
    expect(shifted.corridors[0]! - shifted.autoCorridor).toBe(40);
  });

  it('perpendicular anchors emit a single forced corner (ANC-04)', () => {
    const target = { x: 300, y: 200, width: 100, height: 100 };
    const anchors = { source: { side: 'E' as const, offset: 0 }, target: { side: 'N' as const, offset: -20 } };
    const route = orthogonalRoute(source, target, { axis: 'H', corridors: [30], levels: [], anchors });
    expect(route.pair).toBe('perpendicular');
    expect(route.points).toEqual([
      { x: 100, y: 50 },
      { x: 330, y: 50 },
      { x: 330, y: 200 },
    ]);
    expect(route.corridorHandles).toEqual([]);
    expect(route.levelHandles).toEqual([]);
    expect(route.ignoredAdjustment).toBe(true);
    expect(route.sourceAnchor).toEqual({ side: 'E', x: 100, y: 50 });
    expect(route.targetAnchor).toEqual({ side: 'N', x: 330, y: 200 });
    const flipped = orthogonalRoute(source, target, {
      axis: 'H',
      corridors: [30],
      levels: [],
      anchors: { source: { side: 'S', offset: 10 }, target: { side: 'W', offset: 0 } },
    });
    expect(flipped.pair).toBe('perpendicular');
    expect(flipped.points).toEqual([
      { x: 60, y: 100 },
      { x: 60, y: 250 },
      { x: 300, y: 250 },
    ]);
    expect(flipped.ignoredAdjustment).toBe(true);
  });

  it('ignores corridors authored on the other axis but keeps the anchors (ANC-05)', () => {
    const target = { x: 300, y: 300, width: 100, height: 100 };
    const anchors = { source: { side: 'N' as const, offset: 0 }, target: { side: 'S' as const, offset: 0 } };
    const reference = orthogonalRoute(source, target, { axis: 'V', corridors: [0], levels: [], anchors });
    const mismatched = orthogonalRoute(source, target, { axis: 'H', corridors: [10], levels: [5], anchors });
    expect(mismatched.ignoredAdjustment).toBe(true);
    expect(mismatched.points).toEqual(reference.points);
    expect(mismatched.points[0]).toEqual({ x: 50, y: 0 });
    expect(mismatched.points[mismatched.points.length - 1]).toEqual({ x: 350, y: 400 });
  });

  it('ignores malformed or partial anchors without throwing', () => {
    const auto = orthogonalRoute(source, staggeredRight);
    expect(orthogonalRoute(source, staggeredRight, { axis: 'H', corridors: [0], levels: [], anchors: 42 as never })).toEqual(auto);
    expect(orthogonalRoute(source, staggeredRight, {
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: { source: null as never, target: null as never },
    })).toEqual(auto);
    const unknownSide = orthogonalRoute(source, staggeredRight, {
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'X' as never, offset: 0 }, target: { side: 'W', offset: 0 } },
    });
    expect(unknownSide.points[0]).toEqual({ x: 100, y: 50 });
    expect(unknownSide.points[unknownSide.points.length - 1]).toEqual({ x: 300, y: 250 });
    const nonInteger = orthogonalRoute(source, staggeredRight, {
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'E', offset: 0 }, target: { side: 'W', offset: 0.5 as never } },
    });
    expect(nonInteger.points[0]).toEqual({ x: 100, y: 50 });
    expect(nonInteger.points[nonInteger.points.length - 1]).toEqual({ x: 300, y: 250 });
    const partial = orthogonalRoute(source, staggeredRight, {
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'S', offset: 10 } } as never,
    });
    expect(partial.pair).toBe('perpendicular');
    expect(partial.points).toEqual([
      { x: 60, y: 100 },
      { x: 60, y: 250 },
      { x: 300, y: 250 },
    ]);
  });
});

describe('anchor dot drag (pure, no rendering, ANC-03)', () => {
  it('materializes the adjustment from null with the other end at the route side centre', () => {
    const route = orthogonalRoute(source, alignedRight);
    const next = dragAnchorHandle(route, null, 'source', { x: 100, y: 20 }, source);
    expect(next).toEqual({
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: {
        source: { side: 'E', offset: -30 },
        target: { side: 'W', offset: 0 },
      },
    });
  });

  it('materializes from a perpendicular route repairing corridors to [0]', () => {
    const perpendicular = orthogonalRoute(source, alignedBelow, {
      axis: 'V',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'E', offset: 0 }, target: { side: 'N', offset: 0 } },
    });
    expect(perpendicular.pair).toBe('perpendicular');
    expect(perpendicular.corridors).toEqual([]);
    const next = dragAnchorHandle(perpendicular, null, 'target', { x: 70, y: 300 }, alignedBelow);
    expect(next.axis).toBe(perpendicular.axis);
    expect(next.corridors).toEqual([0]);
    expect(next.levels).toEqual([]);
    expect(next.anchors).toEqual({
      source: { side: 'E', offset: 0 },
      target: { side: 'N', offset: 20 },
    });
  });

  it('the dragged end follows the projection through all four sides', () => {
    const rectangle = { x: 0, y: 0, width: 200, height: 100 };
    const route = orthogonalRoute(rectangle, alignedRight);
    const cases = [
      [{ x: 100, y: -60 }, 'N', 0],
      [{ x: 260, y: 50 }, 'E', 0],
      [{ x: 100, y: 160 }, 'S', 0],
      [{ x: -60, y: 50 }, 'W', 0],
      [{ x: 40, y: -60 }, 'N', -60],
    ] as const;
    for (const [position, side, offset] of cases) {
      const next = dragAnchorHandle(route, null, 'source', position, rectangle);
      expect(next.anchors?.source).toEqual({ side, offset });
    }
  });

  it('keeps the other end stored and the corridors of an applicable adjustment', () => {
    const anchors = { source: { side: 'E' as const, offset: 25 }, target: { side: 'W' as const, offset: -20 } };
    const adjustment = { axis: 'H' as const, corridors: [40], levels: [], anchors };
    const route = orthogonalRoute(source, alignedRight, adjustment);
    const next = dragAnchorHandle(route, adjustment, 'source', { x: 100, y: 80 }, source);
    expect(next.corridors).toEqual([40]);
    expect(next.levels).toEqual([]);
    expect(next.anchors).toEqual({
      source: { side: 'E', offset: 30 },
      target: { side: 'W', offset: -20 },
    });
  });

  it('materializes the other end from the route side when it had no stored anchor', () => {
    const adjustment = { axis: 'H' as const, corridors: [40], levels: [] };
    const route = orthogonalRoute(source, alignedRight, adjustment);
    const next = dragAnchorHandle(route, adjustment, 'target', { x: 300, y: 50 }, alignedRight);
    expect(next.anchors).toEqual({
      source: { side: 'E', offset: 0 },
      target: { side: 'W', offset: 0 },
    });
  });

  it('always yields a backend-valid shape (corridors >= 1, levels = corridors - 1, integer offsets)', () => {
    const cases: Array<{ route: OrthoRoute; adjustment: OrthoAdjustment | null }> = [
      { route: orthogonalRoute(source, alignedRight), adjustment: null },
      {
        route: orthogonalRoute(source, alignedBelow, {
          axis: 'V',
          corridors: [0],
          levels: [],
          anchors: { source: { side: 'E', offset: 0 }, target: { side: 'N', offset: 0 } },
        }),
        adjustment: null,
      },
      {
        route: orthogonalRoute(source, staggeredRight),
        adjustment: {
          axis: 'H',
          corridors: [24, -48],
          levels: [12, -12],
          anchors: { source: { side: 'E', offset: 10 }, target: { side: 'W', offset: -10 } },
        },
      },
    ];
    for (const item of cases) {
      for (const end of ['source', 'target'] as const) {
        const next = dragAnchorHandle(item.route, item.adjustment, end, { x: 33.7, y: 41.3 }, source);
        expect(next.corridors.length).toBeGreaterThanOrEqual(1);
        expect(next.levels.length).toBe(next.corridors.length - 1);
        for (const anchor of [next.anchors!.source, next.anchors!.target]) {
          expect(['N', 'E', 'S', 'W']).toContain(anchor.side);
          expect(Number.isInteger(anchor.offset)).toBe(true);
        }
        expect(next.axis).toBe(item.route.axis);
      }
    }
  });

  it('never mutates the inputs', () => {
    const deepFreeze = <T,>(value: T): T => {
      if (value && typeof value === 'object') {
        Object.values(value as Record<string, unknown>).forEach(deepFreeze);
        Object.freeze(value);
      }
      return value;
    };
    const anchors = { source: { side: 'E' as const, offset: 25 }, target: { side: 'W' as const, offset: -20 } };
    const adjustment = deepFreeze({ axis: 'H' as const, corridors: [40], levels: [], anchors });
    const route = deepFreeze(orthogonalRoute(source, alignedRight, adjustment));
    const snapshot = JSON.stringify({ route, adjustment });
    let next: OrthoAdjustment | null = null;
    expect(() => {
      next = dragAnchorHandle(route, adjustment, 'source', { x: 100, y: 80 }, source);
    }).not.toThrow();
    expect(next).toEqual({
      axis: 'H',
      corridors: [40],
      levels: [],
      anchors: { source: { side: 'E', offset: 30 }, target: { side: 'W', offset: -20 } },
    });
    expect(JSON.stringify({ route, adjustment })).toBe(snapshot);
  });
});

describe('orthogonal handle drag (pure, no rendering)', () => {
  const auto = orthogonalRoute(source, staggeredRight); // H axis, autoCorridor 200
  const corridorHandle = auto.corridorHandles[0]!;

  it('exposes the shared grid constant and helper', () => {
    expect(ORTHO_GRID).toBe(24);
    expect(roundToOrthoGrid(212.5)).toBe(216);
  });

  it('materializes the first hand adjustment from a drag on the untouched auto route', () => {
    const pointer = { x: 240, y: corridorHandle.y };
    const next = dragOrthoHandle(auto, null, corridorHandle, pointer);
    expect(next).toEqual({ axis: 'H', corridors: [40], levels: [] });
    const routed = orthogonalRoute(source, staggeredRight, next);
    expect(routed.corridors[0]).toBe(240);
    expect(routed.points[1]).toEqual({ x: 240, y: 50 });
  });

  it('moves only the dragged level when a level handle is dragged', () => {
    const adjustment = { axis: 'H' as const, corridors: [0, 48], levels: [0] };
    const jogged = orthogonalRoute(source, staggeredRight, adjustment);
    const levelHandle = jogged.levelHandles[0]!;
    expect(levelHandle.kind).toBe('level');
    expect(levelHandle.dragAxis).toBe('y');
    const pointer = { x: 999, y: 174 }; // x must be ignored for a level in an H frame
    const next = dragOrthoHandle(jogged, adjustment, levelHandle, pointer);
    expect(next.corridors).toEqual([0, 48]);
    expect(next.levels).toEqual([Math.round(roundToOrthoGrid(pointer.y) - jogged.levelMid)]);
    const routed = orthogonalRoute(source, staggeredRight, next);
    expect(routed.levels).toEqual([roundToOrthoGrid(pointer.y)]);
    expect(routed.levels[0]! % ORTHO_GRID).toBe(0);
    expect(routed.corridors).toEqual([200, 248]);
  });

  it('lands the dragged coordinate exactly on the grid', () => {
    const next = dragOrthoHandle(auto, null, corridorHandle, { x: 230, y: 50 });
    const routed = orthogonalRoute(source, staggeredRight, next);
    expect(routed.corridors[0]! % ORTHO_GRID).toBe(0);
    expect(routed.corridors[0]).toBe(240);
  });

  it('is a no-op for an out-of-range handle index instead of throwing', () => {
    const adjustment = { axis: 'H' as const, corridors: [24], levels: [] };
    const handle = { index: 5, x: 0, y: 0, dragAxis: 'x' as const, coordinate: 0, kind: 'corridor' as const };
    expect(dragOrthoHandle(auto, adjustment, handle, { x: 240, y: 50 })).toEqual(adjustment);
  });

  it('never mutates the route or the adjustment it is given', () => {
    const adjustment = { axis: 'H' as const, corridors: [0, 48], levels: [10] };
    const adjustmentSnapshot = JSON.parse(JSON.stringify(adjustment));
    const routeSnapshot = JSON.parse(JSON.stringify(auto));
    dragOrthoHandle(auto, adjustment, corridorHandle, { x: 240, y: 50 });
    expect(adjustment).toEqual(adjustmentSnapshot);
    expect(auto).toEqual(routeSnapshot);
  });

  it('starts a fresh same-axis adjustment from a null, wrong-axis, or malformed one', () => {
    const pointer = { x: 240, y: 50 };
    const expected = { axis: 'H', corridors: [40], levels: [] };
    const wrongAxis = { axis: 'V' as const, corridors: [10], levels: [] };
    const malformed = { axis: 'H' as const, corridors: [0.5], levels: [] };
    for (const bad of [null, wrongAxis, malformed]) {
      expect(dragOrthoHandle(auto, bad, corridorHandle, pointer)).toEqual(expected);
    }
  });

  it('maps the vertical frame so corridor drags follow y and level drags follow x', () => {
    const staggeredBelow = { x: 150, y: 300, width: 100, height: 100 }; // V axis, autoCorridor 200
    const vertical = orthogonalRoute(source, staggeredBelow);
    const handle = vertical.corridorHandles[0]!;
    expect(handle.kind).toBe('corridor');
    expect(handle.dragAxis).toBe('y');
    const next = dragOrthoHandle(vertical, null, handle, { x: 999, y: 216 });
    expect(next.axis).toBe('V');
    expect(next.corridors).toEqual([16]);
    expect(next.levels).toEqual([]);
    const routed = orthogonalRoute(source, staggeredBelow, next);
    expect(routed.points[1]).toEqual({ x: 50, y: 216 });
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
