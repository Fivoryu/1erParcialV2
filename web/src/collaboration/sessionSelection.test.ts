import { describe, expect, it } from 'vitest';
import { defaultSessionFor } from './sessionSelection';

/**
 * Pure-logic tests for T7/L47 session resolution: the chat panel binds to the General (default)
 * session of the diagram's default view, and an honest null when either is missing (T9 will add
 * the full selector).
 */
const defaultView = { id: '11111111-1111-4111-8111-111111111111', isDefault: true };
const otherView = { id: '22222222-2222-4222-8222-222222222222', isDefault: false };
const generalSession = { id: '33333333-3333-4333-8333-333333333333', viewId: defaultView.id, title: 'General' };

describe('defaultSessionFor', () => {
  it('returns the session bound to the default view', () => {
    expect(defaultSessionFor([defaultView, otherView], [generalSession])).toEqual(generalSession);
  });

  it('ignores sessions that belong to non-default views', () => {
    const straySession = { id: '44444444-4444-4444-8444-444444444444', viewId: otherView.id, title: 'Diseño' };
    expect(defaultSessionFor([otherView], [straySession])).toBeNull();
    expect(defaultSessionFor([defaultView, otherView], [straySession])).toBeNull();
  });

  it('returns null without a default view or without sessions', () => {
    expect(defaultSessionFor([], [])).toBeNull();
    expect(defaultSessionFor([defaultView], [])).toBeNull();
  });
});
