export interface Session {
  token: string;
  userId: string;
  username: string;
  displayName: string;
  expiresAt: string;
}

export const SESSION_STORAGE_KEY = 'class-diagrammer.session.v1';

type SessionListener = (session: Session | null) => void;
const listeners = new Set<SessionListener>();

// A client clock can be slightly ahead of the server, so an expiry that just crossed the client's
// clock may still be accepted by the server. 60s covers ordinary NTP-synced skew without keeping a
// meaningfully expired session alive; the server remains the final authority via in-flight 401s.
const EXPIRY_TOLERANCE_MS = 60 * 1000;

// Security limit: the bearer lives in browser storage and is therefore exposed to XSS. This is the accepted limit of this increment, not a claim of production-grade session security.

function isSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as Partial<Session>;
  return typeof candidate.token === 'string'
    && candidate.token.length > 0
    && typeof candidate.userId === 'string'
    && candidate.userId.length > 0
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

    const expiresAtMs = Date.parse(parsed.expiresAt);
    if (Number.isNaN(expiresAtMs)) {
      // Fail closed: isSession only checks that expiresAt is a string, so an unparseable value is
      // untrusted state. Dropping the session costs one re-login; keeping it forever is worse.
      globalThis.localStorage.removeItem(SESSION_STORAGE_KEY);
      return null;
    }

    if (Date.now() - expiresAtMs > EXPIRY_TOLERANCE_MS) {
      // An expired session must not be resurrected: treat it as absent. The in-flight 401 handler in
      // client.ts remains the backstop for sessions that look valid but are rejected server-side.
      globalThis.localStorage.removeItem(SESSION_STORAGE_KEY);
      return null;
    }

    return {
      token: parsed.token,
      userId: parsed.userId,
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
