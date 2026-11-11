import { useState, type ChangeEvent, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiTransportError, createApiClient } from '../api/client';
import { isSpanishText } from '../api/errorMessages';
import { saveSession } from '../api/session';
import type { LoginResponse } from '../api/types';

const api = createApiClient();
type AuthMode = 'login' | 'register';

const FRIENDLY_MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'El usuario o la contraseña son incorrectos.',
  INVALID_PAYLOAD: 'Revisa los campos obligatorios. La contraseña debe tener entre 8 y 200 caracteres.',
  USERNAME_TAKEN: 'Ese nombre de usuario ya está en uso. Elige otro.',
};
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const TECHNICAL_MESSAGE_PATTERN = /\b(?:[A-Z][A-Z0-9_]{2,}|[a-z]+[A-Z][A-Za-z0-9]*|[A-Za-z]+_[A-Za-z0-9_]+)\b/;

export function loginErrorMessage(error: unknown): string {
  if (!(error instanceof ApiTransportError)) {
    return 'No se pudo completar la solicitud. Revisa la conexión e inténtalo de nuevo.';
  }

  const fallback = FRIENDLY_MESSAGES[error.code]
    ?? 'No se pudo completar la solicitud. Revisa la conexión e inténtalo de nuevo.';
  const backendMessage = error.serverMessage.trim();
  if (!backendMessage || backendMessage === error.code || UUID_PATTERN.test(backendMessage)
    || TECHNICAL_MESSAGE_PATTERN.test(backendMessage)) {
    return fallback;
  }

  return isSpanishText(backendMessage) ? backendMessage : FRIENDLY_MESSAGES[error.code] ?? error.message;
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
        <Link className="brand" to="/" aria-label="Inicio de Class Diagrammer">
          <span className="brand-mark" aria-hidden="true">C</span>
          <span>Class Diagrammer</span>
        </Link>
        <span className="topbar-note">Espacio de trabajo UML 2.5</span>
      </header>

      <div className="content-wrap auth-content">
        <section className="surface auth-panel" aria-labelledby="auth-title">
          <p className="eyebrow">Tu espacio de trabajo</p>
          <h1 id="auth-title">{registering ? 'Crea tu cuenta' : 'Te damos la bienvenida'}</h1>
          <p className="lede">
            {registering
              ? 'Crea una cuenta para mantener tus proyectos en un solo lugar.'
              : 'Inicia sesión para abrir tus proyectos y diagramas.'}
          </p>

          {error && <div className="notice notice-error auth-error" role="alert">{error}</div>}

          <form className="project-form auth-form" onSubmit={onSubmit}>
            {registering && (
              <>
                <label className="field-label" htmlFor="auth-display-name">Nombre para mostrar</label>
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

            <label className="field-label" htmlFor="auth-username">Usuario</label>
            <input
              autoComplete="username"
              id="auth-username"
              maxLength={64}
              minLength={3}
              onChange={(event: ChangeEvent<HTMLInputElement>) => onUsernameChange(event.target.value)}
              pattern="[A-Za-z0-9._\-]+"
              required
              title="Usa entre 3 y 64 letras, números, puntos, guiones bajos o guiones."
              value={username}
            />
            {registering && <p className="auth-field-hint">Usa entre 3 y 64 letras, números, puntos, guiones bajos o guiones.</p>}

            <label className="field-label" htmlFor="auth-password">Contraseña</label>
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
            {registering && <p className="auth-field-hint">Usa entre 8 y 200 caracteres.</p>}

            {registering && (
              <>
                <label className="field-label" htmlFor="auth-email">Correo electrónico <span>Opcional</span></label>
                <input
                  autoComplete="email"
                  id="auth-email"
                  inputMode="email"
                  onChange={(event: ChangeEvent<HTMLInputElement>) => onEmailChange(event.target.value)}
                  pattern=".*@.*"
                  title="La dirección de correo electrónico debe incluir @."
                  value={email}
                />
                <p className="auth-field-hint">Si agregas un correo electrónico, debe incluir @.</p>
              </>
            )}

            <button className="button button-primary" disabled={busy} type="submit">
              {busy ? 'Espera…' : registering ? 'Crear cuenta' : 'Iniciar sesión'}
              {!busy && <span aria-hidden="true">→</span>}
            </button>
          </form>

          <p className="auth-mode-switch">
            {registering ? '¿Ya tienes una cuenta?' : '¿Nuevo en Class Diagrammer?'}{' '}
            <button className="text-button" onClick={onSwitchMode} type="button">
              {registering ? 'Iniciar sesión' : 'Crear una cuenta'}
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
