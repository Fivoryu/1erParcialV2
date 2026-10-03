import { useState, type ChangeEvent, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiTransportError, createApiClient } from '../api/client';
import { saveSession } from '../api/session';
import type { LoginResponse } from '../api/types';

const api = createApiClient();
type AuthMode = 'login' | 'register';

const FRIENDLY_MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'The username or password is incorrect.',
  INVALID_PAYLOAD: 'Check the required fields. Passwords must be 8–200 characters.',
  USERNAME_TAKEN: 'That username is already in use. Choose another.',
};
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const TECHNICAL_MESSAGE_PATTERN = /\b(?:[A-Z][A-Z0-9_]{2,}|[a-z]+[A-Z][A-Za-z0-9]*|[A-Za-z]+_[A-Za-z0-9_]+)\b/;

export function loginErrorMessage(error: unknown): string {
  if (!(error instanceof ApiTransportError)) {
    return 'Your request could not be completed. Check the connection and try again.';
  }

  const fallback = FRIENDLY_MESSAGES[error.code]
    ?? 'Your request could not be completed. Check the connection and try again.';
  const backendMessage = error.message.trim();
  if (!backendMessage || backendMessage === error.code || UUID_PATTERN.test(backendMessage)
    || TECHNICAL_MESSAGE_PATTERN.test(backendMessage)) {
    return fallback;
  }

  return backendMessage;
}

export function saveLoginResponse(response: LoginResponse): void {
  saveSession({
    token: response.token,
    userId: response.user.id,
    username: response.user.username,
    displayName: response.user.displayName,
    expiresAt: response.expiresAt,
  });
}

interface LoginPageViewProps {
  mode: AuthMode;
  username: string;
  password: string;
  displayName: string;
  email: string;
  busy: boolean;
  error: string | null;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onUsernameChange: (value: string) => void;
  onPasswordChange: (value: string) => void;
  onDisplayNameChange: (value: string) => void;
  onEmailChange: (value: string) => void;
  onSwitchMode: () => void;
}

export function LoginPageView({
  mode,
  username,
  password,
  displayName,
  email,
  busy,
  error,
  onSubmit,
  onUsernameChange,
  onPasswordChange,
  onDisplayNameChange,
  onEmailChange,
  onSwitchMode,
}: LoginPageViewProps) {
  const registering = mode === 'register';

  return (
    <main className="page-shell">
      <header className="topbar">
        <Link className="brand" to="/" aria-label="Class Diagrammer home">
          <span className="brand-mark" aria-hidden="true">C</span>
          <span>Class Diagrammer</span>
        </Link>
        <span className="topbar-note">UML 2.5 workspace</span>
      </header>

      <div className="content-wrap auth-content">
        <section className="surface auth-panel" aria-labelledby="auth-title">
          <p className="eyebrow">Your workspace</p>
          <h1 id="auth-title">{registering ? 'Create your account' : 'Welcome back'}</h1>
          <p className="lede">
            {registering
              ? 'Create an account to keep your projects together.'
              : 'Sign in to open your projects and diagrams.'}
          </p>

          {error && <div className="notice notice-error auth-error" role="alert">{error}</div>}

          <form className="project-form auth-form" onSubmit={onSubmit}>
            {registering && (
              <>
                <label className="field-label" htmlFor="auth-display-name">Display name</label>
                <input
                  autoComplete="name"
                  id="auth-display-name"
                  maxLength={160}
                  minLength={1}
                  onChange={(event: ChangeEvent<HTMLInputElement>) => onDisplayNameChange(event.target.value)}
                  required
                  value={displayName}
                />
              </>
            )}

            <label className="field-label" htmlFor="auth-username">Username</label>
            <input
              autoComplete="username"
              id="auth-username"
              maxLength={64}
              minLength={3}
              onChange={(event: ChangeEvent<HTMLInputElement>) => onUsernameChange(event.target.value)}
              pattern="[A-Za-z0-9._-]+"
              required
              title="Use 3–64 letters, numbers, dots, underscores, or hyphens."
              value={username}
            />
            {registering && <p className="auth-field-hint">Use 3–64 letters, numbers, dots, underscores, or hyphens.</p>}

            <label className="field-label" htmlFor="auth-password">Password</label>
            <input
              autoComplete={registering ? 'new-password' : 'current-password'}
              id="auth-password"
              maxLength={200}
              minLength={8}
              onChange={(event: ChangeEvent<HTMLInputElement>) => onPasswordChange(event.target.value)}
              required
              type="password"
              value={password}
            />
            {registering && <p className="auth-field-hint">Use 8–200 characters.</p>}

            {registering && (
              <>
                <label className="field-label" htmlFor="auth-email">Email <span>Optional</span></label>
                <input
                  autoComplete="email"
                  id="auth-email"
                  inputMode="email"
                  onChange={(event: ChangeEvent<HTMLInputElement>) => onEmailChange(event.target.value)}
                  pattern=".*@.*"
                  title="An email address must contain @."
                  value={email}
                />
                <p className="auth-field-hint">If you add an email, it must contain @.</p>
              </>
            )}

            <button className="button button-primary" disabled={busy} type="submit">
              {busy ? 'Please wait…' : registering ? 'Create account' : 'Sign in'}
              {!busy && <span aria-hidden="true">→</span>}
            </button>
          </form>

          <p className="auth-mode-switch">
            {registering ? 'Already have an account?' : 'New to Class Diagrammer?'}{' '}
            <button className="text-button" onClick={onSwitchMode} type="button">
              {registering ? 'Sign in' : 'Create an account'}
            </button>
          </p>
        </section>
      </div>
    </main>
  );
}

export default function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<AuthMode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (busy) {
      return;
    }

    setBusy(true);
    setError(null);
    const credentials = { username: username.trim(), password };
    try {
      if (mode === 'register') {
        await api.register({
          ...credentials,
          displayName: displayName.trim(),
          ...(email.trim() ? { email: email.trim() } : {}),
        });
      }

      const response = await api.login(credentials);
      saveLoginResponse(response);
      navigate('/', { replace: true });
    } catch (cause: unknown) {
      setError(loginErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <LoginPageView
      busy={busy}
      displayName={displayName}
      email={email}
      error={error}
      mode={mode}
      onDisplayNameChange={setDisplayName}
      onEmailChange={setEmail}
      onPasswordChange={setPassword}
      onSubmit={(event) => void handleSubmit(event)}
      onSwitchMode={() => {
        setMode((current) => current === 'login' ? 'register' : 'login');
        setError(null);
      }}
      onUsernameChange={setUsername}
      password={password}
      username={username}
    />
  );
}
