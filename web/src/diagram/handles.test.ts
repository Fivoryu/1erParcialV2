import { describe, expect, it } from 'vitest';
import { chooseHandles } from './UmlCanvas';

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
