import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiTransportError } from '../api/client';
import { loadSession } from '../api/session';
import type { LoginResponse } from '../api/types';
import { loginErrorMessage, LoginPageView, saveLoginResponse } from './LoginPage';

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

function renderLoginError(error: unknown): string {
  return renderToStaticMarkup(createElement(MemoryRouter, null, createElement(LoginPageView, {
    busy: false,
    displayName: '',
    email: '',
    error: loginErrorMessage(error),
    mode: 'login',
    onDisplayNameChange: () => undefined,
    onEmailChange: () => undefined,
    onPasswordChange: () => undefined,
    onSubmit: () => undefined,
    onSwitchMode: () => undefined,
    onUsernameChange: () => undefined,
    password: '',
    username: '',
  })));
}

describe('login page', () => {
  it('shows a human-readable failed-login message without rendering an operation UUID', () => {
    const error = new ApiTransportError(
      'INVALID_CREDENTIALS',
      'INVALID_CREDENTIALS',
      [],
      '11111111-1111-4111-8111-111111111111',
      401,
    );
    const markup = renderLoginError(error);

    expect(markup).toContain('The username or password is incorrect.');
    expect(markup).not.toContain('INVALID_CREDENTIALS');
    expect(markup).not.toContain('11111111-1111-4111-8111-111111111111');
    expect(markup).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
  });

  it('keeps weak-password and taken-username failures readable', () => {
    const weakPassword = loginErrorMessage(new ApiTransportError(
      'INVALID_PAYLOAD', 'INVALID_PAYLOAD', [], null, 400,
    ));
    const takenUsername = loginErrorMessage(new ApiTransportError(
      'USERNAME_TAKEN', 'USERNAME_TAKEN', [], null, 409,
    ));

    expect(weakPassword).toContain('Passwords must be 8–200 characters.');
    expect(takenUsername).toBe('That username is already in use. Choose another.');
    expect(loginErrorMessage(new ApiTransportError(
      'INVALID_PAYLOAD', 'Passwords must be 8–200 characters.', [], null, 400,
    ))).toBe('Passwords must be 8–200 characters.');
  });

  it('saves the session from a successful login response', () => {
    const response: LoginResponse = {
      token: 'opaque-session-token',
      expiresAt: '2026-10-01T12:00:00Z',
      user: {
        id: '22222222-2222-4222-8222-222222222222',
        username: 'diagram.user',
        displayName: 'Diagram User',
        email: 'diagram@example.test',
      },
    };

    saveLoginResponse(response);

    expect(loadSession()).toEqual({
      token: 'opaque-session-token',
      username: 'diagram.user',
      displayName: 'Diagram User',
      expiresAt: '2026-10-01T12:00:00Z',
    });
  });
});
