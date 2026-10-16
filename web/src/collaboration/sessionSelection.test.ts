import { describe, expect, it } from 'vitest';
import { activeSessionFor, defaultSessionFor } from './sessionSelection';

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

  it('prefers the initial General session even if the list contains other sessions first', () => {
    const earlierSession = { id: '55555555-5555-4555-8555-555555555555', viewId: defaultView.id, title: 'Review' };
    expect(defaultSessionFor([defaultView], [earlierSession, generalSession])).toEqual(generalSession);
  });

  it('preserves a selected session on refresh and falls back to General if it disappeared', () => {
    const reviewSession = { id: '55555555-5555-4555-8555-555555555555', viewId: defaultView.id, title: 'Review' };
    expect(activeSessionFor([defaultView], [generalSession, reviewSession], reviewSession.id)).toEqual(reviewSession);
    expect(activeSessionFor([defaultView], [generalSession], reviewSession.id)).toEqual(generalSession);
  });

  it('returns null without a default view or without sessions', () => {
    expect(defaultSessionFor([], [])).toBeNull();
    expect(defaultSessionFor([defaultView], [])).toBeNull();
    expect(activeSessionFor([], [generalSession], generalSession.id)).toBeNull();
  });
});
