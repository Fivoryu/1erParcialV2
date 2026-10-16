import type { Uuid } from '../api/types';

/** Minimal view reference for session resolution (T7/L47). */
export interface SessionViewRef {
  id: Uuid;
  isDefault: boolean;
}

/** Minimal session reference for session resolution (T7/L47). */
export interface SessionRef {
  id: Uuid;
  viewId: Uuid;
  title: string;
}

/**
 * Resolves the chat session the panel binds to (T7/L47): the session of the diagram's default
 * view, or an honest null when either the default view or its session is missing — the caller
 * surfaces it instead of silently falling back to the legacy endpoints.
 */
export function defaultSessionFor(views: SessionViewRef[], sessions: SessionRef[]): SessionRef | null {
  const defaultView = views.find((view) => view.isDefault);
  if (!defaultView) {
    return null;
  }
  return sessions.find((session) => session.viewId === defaultView.id) ?? null;
}
