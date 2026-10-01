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
  expiresAt: '2026-10-01T12:00:00Z',
};

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
