import { describe, expect, it } from 'vitest';
import { chooseHandles, connectionDirection, connectionSides, differingSides } from './UmlCanvas';

const rectangle = (x: number, y: number, width = 260, height = 200) => ({ x, y, width, height });

describe('chooseHandles', () => {
  it('routes a vertically stacked pair through the facing sides', () => {
    const upper = rectangle(540, 60, 240, 120);
    const lower = rectangle(540, 300, 280, 230);

    expect(chooseHandles(upper, lower)).toEqual({
      sourceHandle: 'source-bottom',
      targetHandle: 'target-top',
    });
    expect(chooseHandles(lower, upper)).toEqual({
      sourceHandle: 'source-top',
      targetHandle: 'target-bottom',
    });
  });

  it('routes a horizontally arranged pair through the sides', () => {
    const left = rectangle(120, 300, 300, 270);
    const right = rectangle(540, 300, 280, 230);

    expect(chooseHandles(left, right)).toEqual({
      sourceHandle: 'source-right',
      targetHandle: 'target-left',
    });
    expect(chooseHandles(right, left)).toEqual({
      sourceHandle: 'source-left',
      targetHandle: 'target-right',
    });
  });

  it('prefers the vertical axis when the offset is diagonal', () => {
    // The pair that produced the defect: the target sits above and slightly right, and the
    // vertical offset dominates. Before this chooser the edge was forced onto the sides and
    // the straight line ran through the target's box.
    const lower = rectangle(540, 300, 280, 230);
    const upperAndRight = rectangle(600, 60, 240, 120);

    expect(chooseHandles(lower, upperAndRight)).toEqual({
      sourceHandle: 'source-top',
      targetHandle: 'target-bottom',
    });
  });

  it('prefers the horizontal axis when that offset dominates', () => {
    const source = rectangle(100, 100, 200, 150);
    const target = rectangle(600, 180, 200, 150);

    expect(chooseHandles(source, target)).toEqual({
      sourceHandle: 'source-right',
      targetHandle: 'target-left',
    });
  });
});

describe('chooseHandles with stored anchors (ANC-06)', () => {
  const left = rectangle(120, 300, 300, 270);
  const right = rectangle(540, 300, 280, 230);

  it('a stored side wins even when it contradicts the dominant axis', () => {
    // The classes sit side by side (auto = right/left), but the stored anchors say N.
    expect(chooseHandles(left, right, { source: { side: 'N', offset: 0 }, target: { side: 'N', offset: 0 } }))
      .toEqual({ sourceHandle: 'source-top', targetHandle: 'target-top' });
  });

  it('maps every anchor side to its handle', () => {
    expect(chooseHandles(left, right, { source: { side: 'S', offset: 4 }, target: { side: 'E', offset: -4 } }))
      .toEqual({ sourceHandle: 'source-bottom', targetHandle: 'target-right' });
    expect(chooseHandles(left, right, { source: { side: 'W', offset: 0 }, target: { side: 'W', offset: 0 } }))
      .toEqual({ sourceHandle: 'source-left', targetHandle: 'target-left' });
  });

  it('no anchors behaves exactly as before', () => {
    const auto = chooseHandles(left, right);
    expect(auto).toEqual({ sourceHandle: 'source-right', targetHandle: 'target-left' });
    expect(chooseHandles(left, right, null)).toEqual(auto);
    expect(chooseHandles(left, right, undefined)).toEqual(auto);
  });

  it('a malformed or unknown side falls back to auto for that end only', () => {
    const auto = chooseHandles(left, right);
    expect(chooseHandles(left, right, { source: { side: 'X' as never, offset: 0 }, target: { side: 'W', offset: 0 } }))
      .toEqual({ sourceHandle: auto.sourceHandle, targetHandle: 'target-left' });
    expect(chooseHandles(left, right, { source: null as never, target: { side: 'N', offset: 0 } }))
      .toEqual({ sourceHandle: auto.sourceHandle, targetHandle: 'target-top' });
  });
});

describe('connectionDirection', () => {
  it('keeps the pair React Flow resolved when the drag started on the source class', () => {
    expect(connectionDirection('a', 'b', 'a')).toEqual({ sourceId: 'a', targetId: 'b' });
  });

  it('flips the pair when the drag started on the class React Flow resolved as target', () => {
    expect(connectionDirection('a', 'b', 'b')).toEqual({ sourceId: 'b', targetId: 'a' });
  });

  it('keeps the pair when no drag start was recorded', () => {
    expect(connectionDirection('a', 'b', null)).toEqual({ sourceId: 'a', targetId: 'b' });
  });

  it('keeps a self connection as it came', () => {
    expect(connectionDirection('a', 'a', 'a')).toEqual({ sourceId: 'a', targetId: 'a' });
  });
});

describe('connectionSides', () => {
  it('reads the side of each end from the handles, source first', () => {
    expect(connectionSides('source-right', 'target-left', false)).toEqual({ source: 'E', target: 'W' });
    expect(connectionSides('source-top', 'target-bottom', false)).toEqual({ source: 'N', target: 'S' });
  });

  it('swaps the handles when the drag started on the class React Flow resolved as target', () => {
    // The user dragged from `target-right` of class A to `source-left` of class B: A is the source.
    expect(connectionSides('source-left', 'target-right', true)).toEqual({ source: 'E', target: 'W' });
  });

  it('gives up on a side it cannot read', () => {
    expect(connectionSides(null, 'target-left', false)).toBeNull();
    expect(connectionSides('source-diagonal', 'target-left', false)).toBeNull();
  });
});

describe('differingSides', () => {
  const left = rectangle(0, 0, 100, 100);
  const right = rectangle(300, 0, 100, 100);

  it('keeps nothing when the user chose the sides the router would pick anyway', () => {
    expect(differingSides({ source: 'E', target: 'W' }, left, right)).toBeNull();
  });

  it('keeps the sides when either end was moved off the automatic one', () => {
    expect(differingSides({ source: 'S', target: 'W' }, left, right)).toEqual({ source: 'S', target: 'W' });
    expect(differingSides({ source: 'E', target: 'N' }, left, right)).toEqual({ source: 'E', target: 'N' });
  });
});
