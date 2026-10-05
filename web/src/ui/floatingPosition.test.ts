import { describe, expect, it } from 'vitest';
import {
  clampToViewport,
  defaultPosition,
  dragPosition,
  isDragGesture,
  readStoredPosition,
  writeStoredPosition,
  resolveRenderedPosition,
  withViewport,
  shouldReposition,
  type FloatingState,
  DRAG_CLICK_THRESHOLD,
  FLOATING_BUTTON_SIZE,
  FLOATING_EDGE_INSET,
  FLOATING_POSITION_STORAGE_KEY,
  type FloatingPosition,
  type FloatingViewport,
} from './floatingPosition';

class MemoryStorage {
  private readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

class ThrowingStorage {
  getItem(): string | null {
    return null;
  }

  setItem(): void {
    throw new Error('quota exceeded');
  }
}

const VIEWPORT: FloatingViewport = { width: 1280, height: 720 };

describe('floatingPosition constants', () => {
  it('exposes the frozen geometry constants', () => {
    expect(FLOATING_BUTTON_SIZE).toBe(56);
    expect(FLOATING_EDGE_INSET).toBe(24);
    expect(DRAG_CLICK_THRESHOLD).toBe(4);
    expect(FLOATING_POSITION_STORAGE_KEY).toBe('uml-editor.floating-assistant.position');
  });
});

describe('defaultPosition', () => {
  it('places the button in the bottom-right corner with the edge inset', () => {
    expect(defaultPosition(VIEWPORT)).toEqual({
      x: 1280 - 56 - 24,
      y: 720 - 56 - 24,
    });
  });
});

describe('clampToViewport', () => {
  it('keeps an in-range position unchanged', () => {
    const position = { x: 400, y: 300 };
    expect(clampToViewport(position, VIEWPORT)).toEqual(position);
  });

  it('clamps negative coordinates to the inset', () => {
    expect(clampToViewport({ x: -100, y: -1 }, VIEWPORT)).toEqual({ x: 24, y: 24 });
  });

  it('clamps positions past the right and bottom edges', () => {
    const maxX = 1280 - 56 - 24;
    const maxY = 720 - 56 - 24;
    expect(clampToViewport({ x: 5000, y: 9000 }, VIEWPORT)).toEqual({ x: maxX, y: maxY });
  });

  it('clamps to a non-negative position even when the viewport is smaller than the button', () => {
    const tiny: FloatingViewport = { width: 40, height: 10 };
    const clamped = clampToViewport({ x: 20, y: 20 }, tiny);
    expect(Number.isFinite(clamped.x)).toBe(true);
    expect(Number.isFinite(clamped.y)).toBe(true);
    expect(clamped.x).toBeGreaterThanOrEqual(0);
    expect(clamped.y).toBeGreaterThanOrEqual(0);
  });

  it('collapses a degenerate viewport (button + 2*inset exceeds it) to exactly {x:0,y:0}', () => {
    // This collapse is the seed of the ratchet defect: at 80x80 the clamp
    // floor becomes 0, so any position maps to the top-left corner. Writing
    // THIS value back as the remembered position is what pinned the button
    // there; it is documented here so the suite explains why the clamp result
    // must never be treated as the user's desired position.
    expect(clampToViewport({ x: 1360, y: 820 }, { width: 80, height: 80 })).toEqual({ x: 0, y: 0 });
  });
});

describe('resolveRenderedPosition', () => {
  it('returns null when desired is null (server rendering keeps no inline position)', () => {
    expect(resolveRenderedPosition({ desired: null, viewport: VIEWPORT })).toBeNull();
  });

  it('returns null when viewport is null (before hydration)', () => {
    expect(resolveRenderedPosition({ desired: { x: 100, y: 100 }, viewport: null })).toBeNull();
  });

  it('returns the desired position unchanged when it fits the viewport', () => {
    expect(resolveRenderedPosition({ desired: { x: 400, y: 300 }, viewport: VIEWPORT })).toEqual({
      x: 400,
      y: 300,
    });
  });

  it('clamps a desired position that sits outside the current viewport', () => {
    expect(
      resolveRenderedPosition({ desired: { x: 5000, y: 9000 }, viewport: VIEWPORT }),
    ).toEqual({ x: 1280 - 56 - 24, y: 720 - 56 - 24 });
  });

  it('is ANTI-RATCHET: restoring the viewport restores the rendered position, never the clamp floor', () => {
    // Regression for the defect where the resize handler wrote the clamped
    // result back as the current position: one transient degenerate viewport
    // pinned the button to the top-left forever, because clamping {0,0} again
    // a restored large viewport yields {24,24}, not the remembered corner.
    // A clamp result must NEVER be written back as `desired` — `withViewport`
    // exists precisely so the viewport can change without touching it.
    const desired: FloatingPosition = { x: 1360, y: 820 };
    const large: FloatingViewport = { width: 1440, height: 900 };
    let state: FloatingState = { desired, viewport: large };

    // Fit on the large viewport: rendered exactly where the user put it.
    expect(resolveRenderedPosition(state)).toEqual({ x: 1360, y: 820 });

    // Shrink to a degenerate viewport: the rendered position may legitimately
    // collapse (that is what clampToViewport documents).
    state = withViewport(state, { width: 80, height: 80 });
    expect(resolveRenderedPosition(state)).toEqual({ x: 0, y: 0 });

    // Restore the viewport: the rendered position MUST be the remembered
    // corner again — explicitly NOT {x:24,y:24}, which is what the poisoned
    // {0,0} desired position would clamp to.
    state = withViewport(state, large);
    const restored = resolveRenderedPosition(state);
    expect(restored).toEqual({ x: 1360, y: 820 });
    expect(restored).not.toEqual({ x: 24, y: 24 });
  });
});

describe('withViewport', () => {
  it('keeps desired intact for a normal viewport', () => {
    const state: FloatingState = { desired: { x: 1360, y: 820 }, viewport: { width: 1440, height: 900 } };
    const next = withViewport(state, VIEWPORT);
    expect(next.desired).toEqual({ x: 1360, y: 820 });
    expect(next.viewport).toEqual(VIEWPORT);
  });

  it('keeps desired intact even for a degenerate viewport', () => {
    const state: FloatingState = { desired: { x: 1360, y: 820 }, viewport: { width: 1440, height: 900 } };
    const next = withViewport(state, { width: 80, height: 80 });
    expect(next.desired).toEqual({ x: 1360, y: 820 });
    expect(next.viewport).toEqual({ width: 80, height: 80 });
  });

  it('returns a new object and never mutates its input', () => {
    const state: FloatingState = { desired: { x: 100, y: 200 }, viewport: { width: 1440, height: 900 } };
    const snapshot = JSON.parse(JSON.stringify(state)) as FloatingState;
    const next = withViewport(state, VIEWPORT);
    expect(next).not.toBe(state);
    expect(state).toEqual(snapshot);
  });
});

describe('shouldReposition', () => {
  it('is false for a 1px move below the threshold', () => {
    expect(shouldReposition(false, { x: 100, y: 100 }, { x: 101, y: 100 }, 4)).toBe(false);
  });

  it('is false for a 3px move below the threshold', () => {
    expect(shouldReposition(false, { x: 100, y: 100 }, { x: 103, y: 100 }, 4)).toBe(false);
  });

  it('is true at exactly the threshold', () => {
    expect(shouldReposition(false, { x: 100, y: 100 }, { x: 104, y: 100 }, 4)).toBe(true);
  });

  it('is true beyond the threshold', () => {
    expect(shouldReposition(false, { x: 100, y: 100 }, { x: 150, y: 120 }, 4)).toBe(true);
  });

  it('is true when already dragging even with a 0px move (tracking continues near the origin)', () => {
    expect(shouldReposition(true, { x: 100, y: 100 }, { x: 100, y: 100 }, 4)).toBe(true);
  });

  it('counts a large move on a single axis', () => {
    expect(shouldReposition(false, { x: 100, y: 100 }, { x: 100, y: 400 }, 4)).toBe(true);
  });
});

describe('isDragGesture', () => {
  it('is false below the threshold', () => {
    expect(isDragGesture({ x: 0, y: 0 }, { x: 3, y: 0 }, 4)).toBe(false);
  });

  it('is true at and above the threshold', () => {
    expect(isDragGesture({ x: 0, y: 0 }, { x: 4, y: 0 }, 4)).toBe(true);
    expect(isDragGesture({ x: 10, y: 10 }, { x: 10, y: 0 }, 4)).toBe(true);
  });

  it('is true for a large move on a single axis', () => {
    expect(isDragGesture({ x: 0, y: 0 }, { x: 0, y: 250 }, 4)).toBe(true);
  });
});

describe('dragPosition', () => {
  it('moves the button by the pointer delta, not to the pointer coordinates', () => {
    // Regression for the VC-04 defect: the drag origin used to be initialised
    // with the pointer coordinates as `startPosition`, so the arithmetic
    // collapsed to `next = currentPointer` and the button's top-left corner
    // snapped to the cursor. A 10px pointer move must move the button 10px
    // from where it was, never teleport it to the pointer's absolute spot.
    expect(
      dragPosition({ x: 1400, y: 900 }, { x: 1450, y: 950 }, { x: 1460, y: 950 }),
    ).toEqual({ x: 1410, y: 900 });
  });

  it('returns the original position when the pointer returns to the drag origin', () => {
    expect(
      dragPosition({ x: 300, y: 500 }, { x: 320, y: 540 }, { x: 320, y: 540 }),
    ).toEqual({ x: 300, y: 500 });
  });

  it('changes only y for a pure vertical move and only x for a pure horizontal move', () => {
    expect(
      dragPosition({ x: 300, y: 500 }, { x: 320, y: 540 }, { x: 320, y: 600 }),
    ).toEqual({ x: 300, y: 560 });
    expect(
      dragPosition({ x: 300, y: 500 }, { x: 320, y: 540 }, { x: 400, y: 540 }),
    ).toEqual({ x: 380, y: 500 });
  });

  it('supports negative deltas (dragging up and left)', () => {
    expect(
      dragPosition({ x: 300, y: 500 }, { x: 320, y: 540 }, { x: 200, y: 240 }),
    ).toEqual({ x: 180, y: 200 });
  });

  it('is invariant under a common coordinate offset (absolute-magnitude independence)', () => {
    // The property that makes the original bug impossible to write: shifting
    // every pointer coordinate by the same offset shifts the result by that
    // offset and nothing else. If the result depended on the absolute pointer
    // coordinates (as the collapsed arithmetic did), this would fail.
    const offset = { x: 1000, y: 2000 };
    const shift = (p: FloatingPosition): FloatingPosition => ({
      x: p.x + offset.x,
      y: p.y + offset.y,
    });
    const base = dragPosition({ x: 300, y: 500 }, { x: 320, y: 540 }, { x: 360, y: 610 });
    const shifted = dragPosition(shift({ x: 300, y: 500 }), shift({ x: 320, y: 540 }), shift({ x: 360, y: 610 }));
    expect(shifted).toEqual({ x: base.x + offset.x, y: base.y + offset.y });
  });
});

describe('readStoredPosition', () => {
  it('round-trips a valid stored value', () => {
    const storage = new MemoryStorage();
    writeStoredPosition(storage, { x: 120, y: 300 });
    expect(readStoredPosition(storage)).toEqual({ x: 120, y: 300 });
  });

  it('returns null when the key is missing', () => {
    expect(readStoredPosition(new MemoryStorage())).toBeNull();
  });

  it('returns null for malformed JSON instead of throwing', () => {
    const storage = new MemoryStorage();
    storage.setItem(FLOATING_POSITION_STORAGE_KEY, '{not-json');
    expect(() => readStoredPosition(storage)).not.toThrow();
    expect(readStoredPosition(storage)).toBeNull();
  });

  it('returns null for a value that is valid JSON but the wrong shape', () => {
    const shapes = [
      '"just a string"',
      '42',
      'null',
      '{}',
      '{"x":10}',
      '{"x":"10","y":20}',
      '{"x":10,"y":"20"}',
      '{"x":10.5,"y":NaN}',
      '{"x":10,"y":Infinity}',
    ];
    for (const shape of shapes) {
      const storage = new MemoryStorage();
      storage.setItem(FLOATING_POSITION_STORAGE_KEY, shape);
      expect(readStoredPosition(storage), shape).toBeNull();
    }
  });
});

describe('writeStoredPosition', () => {
  it('writes a value that readStoredPosition can read back', () => {
    const storage = new MemoryStorage();
    writeStoredPosition(storage, { x: 64, y: 96 });
    expect(readStoredPosition(storage)).toEqual({ x: 64, y: 96 });
  });

  it('swallows a throwing setItem instead of breaking the editor', () => {
    const storage = new ThrowingStorage();
    expect(() => writeStoredPosition(storage, { x: 1, y: 2 })).not.toThrow();
  });
});
