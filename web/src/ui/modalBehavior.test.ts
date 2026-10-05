import { describe, expect, it } from 'vitest';
import { FOCUSABLE_SELECTOR, isDismissKey, nextFocusIndex } from './modalBehavior';

describe('FOCUSABLE_SELECTOR', () => {
  it('matches every focusable element kind while excluding disabled controls and tabindex="-1"', () => {
    expect(FOCUSABLE_SELECTOR).toBe(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), '
        + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
  });
});

describe('isDismissKey', () => {
  it('accepts Escape only when the dialog is dismissible', () => {
    expect(isDismissKey('Escape', true)).toBe(true);
  });

  it('accepts the legacy Esc key name when the dialog is dismissible', () => {
    expect(isDismissKey('Esc', true)).toBe(true);
  });

  it('rejects Escape when the dialog is not dismissible', () => {
    expect(isDismissKey('Escape', false)).toBe(false);
    expect(isDismissKey('Esc', false)).toBe(false);
  });

  it('rejects every other key even when the dialog is dismissible', () => {
    expect(isDismissKey('Tab', true)).toBe(false);
    expect(isDismissKey('Enter', true)).toBe(false);
    expect(isDismissKey('escape', true)).toBe(false);
    expect(isDismissKey('', true)).toBe(false);
  });
});

describe('nextFocusIndex', () => {
  it('advances forward from a focused element', () => {
    expect(nextFocusIndex(0, 3, false)).toBe(1);
    expect(nextFocusIndex(1, 3, false)).toBe(2);
  });

  it('wraps forward from the last element back to the first', () => {
    expect(nextFocusIndex(2, 3, false)).toBe(0);
  });

  it('moves backward with shift from the first element to the last', () => {
    expect(nextFocusIndex(0, 3, true)).toBe(2);
  });

  it('treats an unfocused dialog (index -1) as entering at the first element, or the last on shift', () => {
    expect(nextFocusIndex(-1, 3, false)).toBe(0);
    expect(nextFocusIndex(-1, 3, true)).toBe(2);
  });

  it('returns -1 when the dialog has no focusable elements', () => {
    expect(nextFocusIndex(-1, 0, false)).toBe(-1);
    expect(nextFocusIndex(0, 0, false)).toBe(-1);
    expect(nextFocusIndex(2, 0, true)).toBe(-1);
    expect(nextFocusIndex(-1, -2, false)).toBe(-1);
  });

  it('normalizes an out-of-range index with modulo before stepping, so a stale index recovers into range', () => {
    // currentIndex 7 with count 3 normalizes to 1; forward -> 2, backward -> 0.
    expect(nextFocusIndex(7, 3, false)).toBe(2);
    expect(nextFocusIndex(7, 3, true)).toBe(0);
    // The result is always inside [0, count - 1].
    expect(nextFocusIndex(99, 4, false)).toBeGreaterThanOrEqual(0);
    expect(nextFocusIndex(99, 4, false)).toBeLessThan(4);
  });
});
