import { describe, expect, it } from 'vitest';
import {
  ANCHOR_CORNER_MARGIN,
  ANCHOR_STUB,
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
  sideLanes,
  projectAnchorOnBorder,
  removeJog,
  roundedOrthogonalPath,
  roundToOrthoGrid,
  slideCorridor,
  slideLevel,
  type AnchorSide,
  type OrthoAdjustment,
  type OrthoRectangle,
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
    // T5 confirmed this shape: the partial value falls back to the target's auto side, and
    // the route leaves the source's bottom, crosses below it and enters the target's left
    // side at its middle. The safe-candidate search keeps exactly this route.
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

describe('anchored route respects the outward normal of each side (T5)', () => {
  const origin: OrthoRectangle = { x: 0, y: 0, width: 120, height: 120 };
  const layouts: OrthoRectangle[] = [
    { x: 240, y: 0, width: 120, height: 120 },
    { x: 240, y: 200, width: 120, height: 120 },
    { x: 0, y: 240, width: 120, height: 120 },
    { x: -240, y: 0, width: 120, height: 120 },
    { x: 0, y: -240, width: 120, height: 120 },
    { x: -260, y: 170, width: 120, height: 120 },
  ];
  const sides: AnchorSide[] = ['N', 'E', 'S', 'W'];

  function strictlyInside(point: { x: number; y: number }, rect: OrthoRectangle): boolean {
    return point.x > rect.x && point.x < rect.x + rect.width
      && point.y > rect.y && point.y < rect.y + rect.height;
  }

  /** Counts, over the whole grid, how many side pairs cut through a class. */
  function sweep(): { checked: number; broken: number } {
    let checked = 0;
    let broken = 0;
    for (const target of layouts) {
      for (const from of sides) {
        for (const to of sides) {
          checked += 1;
          const route = orthogonalRoute(origin, target, {
            axis: 'H',
            corridors: [0],
            levels: [],
            anchors: { source: { side: from, offset: 0 }, target: { side: to, offset: 0 } },
          });
          let cuts = false;
          for (let index = 1; index < route.points.length; index += 1) {
            const previous = route.points[index - 1]!;
            const current = route.points[index]!;
            if (previous.x !== current.x && previous.y !== current.y) {
              cuts = true;
            }
            for (let step = 1; step < 80; step += 1) {
              const t = step / 80;
              const sample = {
                x: previous.x + (current.x - previous.x) * t,
                y: previous.y + (current.y - previous.y) * t,
              };
              if (strictlyInside(sample, origin) || strictlyInside(sample, target)) {
                cuts = true;
              }
            }
          }
          if (cuts) {
            broken += 1;
          }
        }
      }
    }
    return { checked, broken };
  }

  it('never cuts through either class, for any pair of chosen sides', () => {
    expect(sweep()).toEqual({ checked: 96, broken: 0 });
  });

  it('pins the endpoints to the anchors for every pair', () => {
    for (const target of layouts) {
      for (const from of sides) {
        for (const to of sides) {
          const route = orthogonalRoute(origin, target, {
            axis: 'H',
            corridors: [0],
            levels: [],
            anchors: { source: { side: from, offset: 0 }, target: { side: to, offset: 0 } },
          });
          expect(route.points[0]).toEqual({ x: route.sourceAnchor.x, y: route.sourceAnchor.y });
          expect(route.points[route.points.length - 1]).toEqual({
            x: route.targetAnchor.x,
            y: route.targetAnchor.y,
          });
        }
      }
    }
  });

  it('keeps the automatic route when the facing sides are the chosen ones', () => {
    const target = layouts[1]!;
    const automatic = orthogonalRoute(origin, target);
    const anchored = orthogonalRoute(origin, target, {
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'E', offset: 0 }, target: { side: 'W', offset: 0 } },
    });
    expect(anchored.points).toEqual(automatic.points);
  });

  it('runs the crossing outside both classes when both sides face the same way', () => {
    const route = orthogonalRoute(origin, layouts[1]!, {
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'N', offset: 0 }, target: { side: 'N', offset: 0 } },
    });
    expect(route.points).toEqual([
      { x: 60, y: 0 },
      { x: 60, y: -ANCHOR_STUB },
      { x: 300, y: -ANCHOR_STUB },
      { x: 300, y: 200 },
    ]);
  });

  it('goes around through the nearest lateral side when the normals contradict', () => {
    const route = orthogonalRoute(origin, layouts[4]!, {
      axis: 'V',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'S', offset: 0 }, target: { side: 'N', offset: 0 } },
    });
    expect(route.points).toEqual([
      { x: 60, y: 120 },
      { x: 60, y: 120 + ANCHOR_STUB },
      { x: -ANCHOR_STUB, y: 120 + ANCHOR_STUB },
      { x: -ANCHOR_STUB, y: -240 - ANCHOR_STUB },
      { x: 60, y: -240 - ANCHOR_STUB },
      { x: 60, y: -240 },
    ]);
    expect(route.corridorHandles).toEqual([]);
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

describe('orthogonalRoute across a gap too narrow for the stubs', () => {
  function crossesInterior(points: { x: number; y: number }[], rectangle: OrthoRectangle): boolean {
    const right = rectangle.x + rectangle.width;
    const bottom = rectangle.y + rectangle.height;
    return points.slice(1).some((next, index) => {
      const previous = points[index]!;
      if (previous.y === next.y) {
        return previous.y > rectangle.y && previous.y < bottom
          && Math.max(Math.min(previous.x, next.x), rectangle.x) < Math.min(Math.max(previous.x, next.x), right);
      }
      return previous.x > rectangle.x && previous.x < right
        && Math.max(Math.min(previous.y, next.y), rectangle.y) < Math.min(Math.max(previous.y, next.y), bottom);
    });
  }

  it('crosses through the vertical gap instead of cutting a class when the sides face away', () => {
    // Observed in the editor: the source sits up and to the right of the target with a 4px
    // horizontal gap, so the source leaves west, the target leaves east, and their stubs overlap.
    const upperRight = { x: 690, y: 0, width: 170, height: 64 };
    const lowerLeft = { x: 513, y: 160, width: 173, height: 64 };

    const route = orthogonalRoute(upperRight, lowerLeft);

    expect(route.points.slice(1).every((next, index) => next.x === route.points[index]!.x
      || next.y === route.points[index]!.y)).toBe(true);
    expect(crossesInterior(route.points, upperRight)).toBe(false);
    expect(crossesInterior(route.points, lowerLeft)).toBe(false);
  });

  it('does the same when the classes are stacked the other way round', () => {
    const lowerRight = { x: 690, y: 160, width: 170, height: 64 };
    const upperLeft = { x: 513, y: 0, width: 173, height: 64 };

    const route = orthogonalRoute(lowerRight, upperLeft);

    expect(crossesInterior(route.points, lowerRight)).toBe(false);
    expect(crossesInterior(route.points, upperLeft)).toBe(false);
  });
});

describe('lanes', () => {
  const a = { x: 0, y: 0, width: 100, height: 100 };
  const above = { x: 300, y: -200, width: 100, height: 100 };
  const below = { x: 300, y: 100, width: 100, height: 100 };

  it('shifts each end of an auto route by its own lane', () => {
    const centred = orthogonalRoute(source, alignedRight);
    const shifted = orthogonalRoute(source, alignedRight, null, 16);
    const apart = orthogonalRoute(source, alignedRight, null, { source: 8, target: -8 });

    expect(centred.points).toEqual([{ x: 100, y: 50 }, { x: 300, y: 50 }]);
    expect(shifted.points).toEqual([{ x: 100, y: 66 }, { x: 300, y: 66 }]);
    expect(apart.sourceAnchor.y).toBe(58);
    expect(apart.targetAnchor.y).toBe(42);
  });

  it('lets a stored anchor win over the lane', () => {
    const route = orthogonalRoute(source, alignedRight, {
      axis: 'H',
      corridors: [0],
      levels: [],
      anchors: { source: { side: 'E', offset: -10 }, target: { side: 'W', offset: -10 } },
    }, 16);

    expect(route.points[0]).toEqual({ x: 100, y: 40 });
  });

  it('spreads relationships between the same pair of classes in the same order at both ends', () => {
    const lanes = sideLanes(
      [{ id: 'r1', sourceId: 'a', targetId: 'b' }, { id: 'r2', sourceId: 'b', targetId: 'a' }],
      new Map([['a', a], ['b', { x: 300, y: 0, width: 100, height: 100 }]]),
    );

    expect(lanes.get('r1')).toEqual({ source: -8, target: -8 });
    expect(lanes.get('r2')).toEqual({ source: 8, target: 8 });
  });

  it('spreads relationships of different pairs that leave through the same side, upper target first', () => {
    const lanes = sideLanes(
      [{ id: 'toLower', sourceId: 'a', targetId: 'c' }, { id: 'toUpper', sourceId: 'a', targetId: 'b' }],
      new Map([['a', a], ['b', above], ['c', below]]),
    );

    expect(lanes.get('toUpper')!.source).toBe(-8);
    expect(lanes.get('toLower')!.source).toBe(8);
    expect(lanes.get('toUpper')!.target).toBe(0);
    expect(lanes.get('toLower')!.target).toBe(0);
  });

  it('leaves a relationship that shares nothing on the centre of its sides', () => {
    const lanes = sideLanes([{ id: 'r', sourceId: 'a', targetId: 'b' }], new Map([['a', a], ['b', above]]));

    expect(lanes.get('r')).toEqual({ source: 0, target: 0 });
  });

  it('starts self relationships at the corner and grows them outward, never centred', () => {
    const lanes = sideLanes(
      [{ id: 's1', sourceId: 'a', targetId: 'a' }, { id: 's2', sourceId: 'a', targetId: 'a' }],
      new Map([['a', a]]),
    );

    expect(lanes.get('s1')).toEqual({ source: 0, target: 0 });
    expect(lanes.get('s2')).toEqual({ source: 16, target: 16 });
  });
});

describe('orthogonalRoute around classes in between', () => {
  function crosses(points: { x: number; y: number }[], rectangle: OrthoRectangle): boolean {
    const right = rectangle.x + rectangle.width;
    const bottom = rectangle.y + rectangle.height;
    return points.slice(1).some((next, index) => {
      const previous = points[index]!;
      if (previous.y === next.y) {
        return previous.y > rectangle.y && previous.y < bottom
          && Math.max(Math.min(previous.x, next.x), rectangle.x) < Math.min(Math.max(previous.x, next.x), right);
      }
      return previous.x > rectangle.x && previous.x < right
        && Math.max(Math.min(previous.y, next.y), rectangle.y) < Math.min(Math.max(previous.y, next.y), bottom);
    });
  }
  const isOrthogonal = (points: { x: number; y: number }[]) => points.slice(1)
    .every((next, index) => next.x === points[index]!.x || next.y === points[index]!.y);

  it('leaves a route alone when nothing is in the way', () => {
    const farAway = { x: 200, y: 600, width: 100, height: 60 };

    expect(orthogonalRoute(source, alignedRight, null, 0, [farAway]).points)
      .toEqual(orthogonalRoute(source, alignedRight).points);
  });

  it('goes around a class standing between two aligned classes', () => {
    const between = { x: 160, y: 20, width: 100, height: 60 };

    const direct = orthogonalRoute(source, alignedRight);
    const route = orthogonalRoute(source, alignedRight, null, 0, [between]);

    expect(crosses(direct.points, between)).toBe(true);
    expect(isOrthogonal(route.points)).toBe(true);
    expect(crosses(route.points, between)).toBe(false);
    expect(crosses(route.points, source)).toBe(false);
    expect(crosses(route.points, alignedRight)).toBe(false);
  });

  it('goes around several classes in a row', () => {
    const first = { x: 140, y: 10, width: 60, height: 80 };
    const second = { x: 210, y: -40, width: 50, height: 180 };

    const route = orthogonalRoute(source, alignedRight, null, 0, [first, second]);

    expect(isOrthogonal(route.points)).toBe(true);
    expect(crosses(route.points, first)).toBe(false);
    expect(crosses(route.points, second)).toBe(false);
  });

  it('still returns a route when no way around exists', () => {
    const wall = { x: -500, y: -500, width: 1500, height: 50 };
    const floor = { x: -500, y: 450, width: 1500, height: 50 };
    const between = { x: 160, y: -450, width: 100, height: 900 };

    const route = orthogonalRoute(source, alignedRight, null, 0, [wall, floor, between]);

    expect(route.points.length).toBeGreaterThanOrEqual(2);
    expect(isOrthogonal(route.points)).toBe(true);
  });

  it('keeps a hand-placed route as the user left it, even through a class', () => {
    const between = { x: 160, y: 20, width: 100, height: 60 };
    const adjustment = { axis: 'H' as const, corridors: [24], levels: [] };

    const route = orthogonalRoute(source, alignedRight, adjustment, 0, [between]);

    expect(route.points).toEqual(orthogonalRoute(source, alignedRight, adjustment).points);
  });
});

describe('orthogonalRoute for a class related to itself', () => {
  const self = { x: 100, y: 100, width: 200, height: 80 };
  const interior = (points: { x: number; y: number }[]) => points.slice(1).some((next, index) => {
    const previous = points[index]!;
    const inside = (x: number, y: number) => x > self.x && x < self.x + self.width && y > self.y && y < self.y + self.height;
    return inside((previous.x + next.x) / 2, (previous.y + next.y) / 2);
  });

  it('draws the UML corner loop: out of the east side, round the top-right corner, back into the north side', () => {
    const route = orthogonalRoute(self, self);

    expect(route.points[0]!.x).toBe(self.x + self.width);
    expect(route.points.at(-1)!.y).toBe(self.y);
    expect(route.points.length).toBe(5);
    expect(route.points.slice(1).every((next, index) => next.x === route.points[index]!.x
      || next.y === route.points[index]!.y)).toBe(true);
    expect(interior(route.points)).toBe(false);
  });

  it('keeps both ends on the border, apart from each other', () => {
    const route = orthogonalRoute(self, self);

    expect(route.sourceAnchor.side).toBe('E');
    expect(route.targetAnchor.side).toBe('N');
    expect(route.sourceAnchor).not.toEqual(route.targetAnchor);
  });

  it('grows the loop for each further lane so several self relationships do not overlap', () => {
    const first = orthogonalRoute(self, self, null, 0);
    const second = orthogonalRoute(self, self, null, 16);

    expect(second.points[1]!.x).toBeGreaterThan(first.points[1]!.x);
    expect(second.points[2]!.y).toBeLessThan(first.points[2]!.y);
  });
});

describe('orthogonalRoute between perpendicular sides with classes in the way', () => {
  const crossesInterior = (points: { x: number; y: number }[], rectangle: OrthoRectangle) => points.slice(1).some((next, index) => {
    const previous = points[index]!;
    const right = rectangle.x + rectangle.width;
    const bottom = rectangle.y + rectangle.height;
    if (previous.y === next.y) {
      return previous.y > rectangle.y && previous.y < bottom
        && Math.max(Math.min(previous.x, next.x), rectangle.x) < Math.min(Math.max(previous.x, next.x), right);
    }
    return previous.x > rectangle.x && previous.x < right
      && Math.max(Math.min(previous.y, next.y), rectangle.y) < Math.min(Math.max(previous.y, next.y), bottom);
  });
  const orthogonal = (points: { x: number; y: number }[]) => points.slice(1)
    .every((next, index) => next.x === points[index]!.x || next.y === points[index]!.y);

  // The source leaves through its east side and the target is entered through its north side.
  const from = { x: 0, y: 0, width: 100, height: 100 };
  const to = { x: 400, y: 300, width: 100, height: 100 };
  const anchored = {
    axis: 'H' as const,
    corridors: [0],
    levels: [],
    anchors: { source: { side: 'E' as const, offset: 0 }, target: { side: 'N' as const, offset: 0 } },
  };

  it('keeps the plain corner route when nothing is in the way', () => {
    const route = orthogonalRoute(from, to, anchored);

    expect(route.points).toEqual([{ x: 100, y: 50 }, { x: 450, y: 50 }, { x: 450, y: 300 }]);
  });

  it('finds another way when a class blocks each of the two corners', () => {
    const blocksHorizontalFirst = { x: 200, y: 20, width: 100, height: 60 };
    const blocksVerticalFirst = { x: 80, y: 150, width: 100, height: 60 };

    const route = orthogonalRoute(from, to, anchored, 0, [blocksHorizontalFirst, blocksVerticalFirst]);

    expect(orthogonal(route.points)).toBe(true);
    for (const rectangle of [from, to, blocksHorizontalFirst, blocksVerticalFirst]) {
      expect(crossesInterior(route.points, rectangle)).toBe(false);
    }
    expect(route.points[0]).toEqual({ x: 100, y: 50 });
    expect(route.points.at(-1)).toEqual({ x: 450, y: 300 });
    // It still arrives straight down into the north side, against that side's outward normal.
    expect(route.points.at(-2)!.x).toBe(450);
    expect(route.points.at(-2)!.y).toBeLessThan(300);
  });

  it('searches a way through when the usual detours of two aligned classes are blocked too', () => {
    const left = { x: 0, y: 0, width: 100, height: 100 };
    const right = { x: 400, y: 0, width: 100, height: 100 };
    // The straight line is blocked by the middle class, and the two stubs' columns are blocked above and below,
    // so none of the usual crossings or detours is clear; a way still exists between them.
    const middle = { x: 180, y: -60, width: 40, height: 160 };
    const aboveStub = { x: 110, y: -1000, width: 30, height: 960 };
    const belowStub = { x: 110, y: 60, width: 30, height: 300 };

    const route = orthogonalRoute(left, right, null, 0, [middle, aboveStub, belowStub]);

    expect(orthogonal(route.points)).toBe(true);
    for (const rectangle of [left, right, middle, aboveStub, belowStub]) {
      expect(crossesInterior(route.points, rectangle)).toBe(false);
    }
    expect(route.points[0]).toEqual({ x: 100, y: 50 });
    expect(route.points.at(-1)).toEqual({ x: 400, y: 50 });
  });

  it('stays fast and still returns a route on a crowded diagram where the search is skipped or has to give up', () => {
    const crowd = Array.from({ length: 120 }, (_, index) => ({
      x: 160 + (index % 12) * 14,
      y: -400 + Math.floor(index / 12) * 90,
      width: 10,
      height: 80,
    }));
    const started = Date.now();

    const route = orthogonalRoute(from, to, anchored, 0, crowd);

    expect(Date.now() - started).toBeLessThan(2000);
    expect(route.points.length).toBeGreaterThanOrEqual(2);
    expect(orthogonal(route.points)).toBe(true);
  });

  it('still returns a route when the target is walled in', () => {
    const walls = [
      { x: 330, y: 200, width: 250, height: 20 },
      { x: 330, y: 200, width: 20, height: 250 },
      { x: 560, y: 200, width: 20, height: 250 },
    ];

    const route = orthogonalRoute(from, to, anchored, 0, walls);

    expect(route.points.length).toBeGreaterThanOrEqual(2);
    expect(orthogonal(route.points)).toBe(true);
  });
});
