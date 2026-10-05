import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearSession,
  getAuthorizationHeaderValue,
  loadSession,
  saveSession,
  SESSION_STORAGE_KEY,
} from './session';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length(): number {
    return this.values.size;
  }

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', new MemoryStorage());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const session = {
  token: 'opaque-session-token',
  userId: '11111111-1111-4111-8111-111111111111',
  username: 'diagram.user',
  displayName: 'Diagram User',
  expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
};

const expiresInSeconds = (seconds: number): string =>
  new Date(Date.now() + seconds * 1000).toISOString();

describe('browser session store', () => {
  it('round-trips a session under its named storage key', () => {
    saveSession(session);

    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).toBe(JSON.stringify(session));
    expect(loadSession()).toEqual(session);
  });

  it('returns null for corrupt stored data', () => {
    globalThis.localStorage.setItem(SESSION_STORAGE_KEY, '{not-json');

    expect(loadSession()).toBeNull();
  });

  it('discards a stored session without the required user id', () => {
    const legacySession = { ...session } as Partial<typeof session>;
    delete legacySession.userId;
    globalThis.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(legacySession));

    expect(loadSession()).toBeNull();
    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('removes the stored session when cleared', () => {
    saveSession(session);

    clearSession();

    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(loadSession()).toBeNull();
  });

  it('reflects the current bearer in the authorization header helper', () => {
    expect(getAuthorizationHeaderValue()).toBeNull();

    saveSession(session);

    expect(getAuthorizationHeaderValue()).toBe('Bearer opaque-session-token');
  });
});

describe('expired stored sessions', () => {
  const storeSession = (expiresAt: string): void => {
    globalThis.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ ...session, expiresAt }),
    );
  };

  it('returns a session that expires in the future unchanged', () => {
    const futureExpiry = expiresInSeconds(60 * 60);
    storeSession(futureExpiry);

    expect(loadSession()).toEqual({ ...session, expiresAt: futureExpiry });
  });

  it('treats a session that expired an hour ago as absent and removes it', () => {
    storeSession(expiresInSeconds(-60 * 60));

    expect(loadSession()).toBeNull();
    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('still accepts a session that expired within the clock-skew tolerance', () => {
    const recentExpiry = expiresInSeconds(-30);
    storeSession(recentExpiry);

    expect(loadSession()).toEqual({ ...session, expiresAt: recentExpiry });
  });

  it('rejects a session that expired just outside the clock-skew tolerance', () => {
    storeSession(expiresInSeconds(-90));

    expect(loadSession()).toBeNull();
  });

  it('fails closed on an unparseable expiresAt and clears it', () => {
    storeSession('not-a-timestamp');

    expect(loadSession()).toBeNull();
    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('keeps rejecting a shape-invalid stored session', () => {
    globalThis.localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ token: '', userId: 'x', username: 'x', displayName: 'x', expiresAt: expiresInSeconds(60) }),
    );

    expect(loadSession()).toBeNull();
    expect(globalThis.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });
});
