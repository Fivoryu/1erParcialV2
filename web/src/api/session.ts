export interface Session {
  token: string;
  username: string;
  displayName: string;
  expiresAt: string;
}

export const SESSION_STORAGE_KEY = 'class-diagrammer.session.v1';

type SessionListener = (session: Session | null) => void;
const listeners = new Set<SessionListener>();

// Security limit: the bearer lives in browser storage and is therefore exposed to XSS. This is the accepted limit of this increment, not a claim of production-grade session security.

function isSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as Partial<Session>;
  return typeof candidate.token === 'string'
    && candidate.token.length > 0
    && typeof candidate.username === 'string'
    && typeof candidate.displayName === 'string'
    && typeof candidate.expiresAt === 'string';
}

function notifyListeners(session: Session | null): void {
  for (const listener of listeners) {
    listener(session);
  }
}

export function loadSession(): Session | null {
  try {
    const stored = globalThis.localStorage.getItem(SESSION_STORAGE_KEY);
    if (stored === null) {
      return null;
    }

    const parsed: unknown = JSON.parse(stored);
    if (!isSession(parsed)) {
      globalThis.localStorage.removeItem(SESSION_STORAGE_KEY);
      return null;
    }

    return {
      token: parsed.token,
      username: parsed.username,
      displayName: parsed.displayName,
      expiresAt: parsed.expiresAt,
    };
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  globalThis.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  notifyListeners(session);
}

export function clearSession(): void {
  globalThis.localStorage.removeItem(SESSION_STORAGE_KEY);
  notifyListeners(null);
}

export function getAuthorizationHeaderValue(): string | null {
  const session = loadSession();
  return session ? `Bearer ${session.token}` : null;
}

export function subscribeToSession(listener: SessionListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
