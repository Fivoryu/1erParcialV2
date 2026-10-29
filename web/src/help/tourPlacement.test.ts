import { describe, expect, it } from 'vitest';

import { placeTourAboveTarget, scrollOffsetBelowHeader } from './tourPlacement';

describe('placeTourAboveTarget', () => {
  it('keeps the lower-screen controls clear by placing the card above them', () => {
    expect(placeTourAboveTarget({ top: 351, height: 62 }, 812)).toBe(true);
    expect(placeTourAboveTarget({ top: 253, height: 62 }, 568)).toBe(true);
  });

  it('leaves the card below a high target or when the target is unavailable', () => {
    expect(placeTourAboveTarget({ top: 80, height: 48 }, 812)).toBe(false);
    expect(placeTourAboveTarget(null, 812)).toBe(false);
  });

  it('moves a revealed target below the sticky header without scrolling an already visible one', () => {
    expect(scrollOffsetBelowHeader(253, 340)).toBe(-99);
    expect(scrollOffsetBelowHeader(400, 340)).toBe(0);
  });
});
