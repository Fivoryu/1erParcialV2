import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiTransportError, createApiClient } from '../api/client';
import { clearSession, loadSession } from '../api/session';
import type { ProjectResource } from '../api/types';
import InvitationsSection from '../members/InvitationsSection';
import LoginPage, { loginErrorMessage } from './LoginPage';

const api = createApiClient();

function messageFor(error: unknown): string {
  if (error instanceof ApiTransportError) {
    return loginErrorMessage(error);
  }
  return 'No se pudieron cargar los proyectos. Revisa la conexión e inténtalo de nuevo.';
}

export default function ProjectsPage() {
  const navigate = useNavigate();
  const session = loadSession();
  const [projects, setProjects] = useState<ProjectResource[]>([]);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refreshProjects = useCallback(async () => {
    setError(null);
    try {
      setProjects(await api.listProjects());
    } catch (cause: unknown) {
      setError(messageFor(cause));
    }
  }, []);

  useEffect(() => {
    let active = true;
    if (!loadSession()) {
      setLoading(false);
      return () => {
        active = false;
      };
    }

    void api.listProjects()
      .then((items) => {
        if (active) {
          setProjects(items);
          setError(null);
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(messageFor(cause));
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, []);

  async function handleLogout(): Promise<void> {
    if (loggingOut) {
      return;
    }

    setLoggingOut(true);
    try {
      await api.logout();
    } catch {
      // Remove the local credential even if the server cannot complete logout.
    } finally {
      clearSession();
      navigate('/', { replace: true });
    }
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedName = name.trim();
    if (!trimmedName) {
      setError('Enter a project name to continue.');
      return;
    }

    setSaving(true);
    setError(null);
    try {
      const project = await api.createProject({
        name: trimmedName,
        description: description.trim() || null,
      });
      navigate(`/projects/${encodeURIComponent(project.id)}`);
    } catch (cause: unknown) {
      setError(messageFor(cause));
      setSaving(false);
    }
  }

  if (!session) {
    return <LoginPage />;
  }

  return (
    <main className="page-shell">
      <header className="topbar">
        <Link className="brand" to="/" aria-label="Inicio de Class Diagrammer">
          <span className="brand-mark" aria-hidden="true">C</span>
          <span>Class Diagrammer</span>
        </Link>
        <div className="topbar-account">
          <span className="topbar-note">Sesión iniciada como <strong>{session.displayName}</strong></span>
          <button className="text-button" disabled={loggingOut} onClick={() => void handleLogout()} type="button">
            {loggingOut ? 'Cerrando sesión…' : 'Cerrar sesión'}
          </button>
        </div>
      </header>

      <div className="content-wrap">
        <section className="page-heading" aria-labelledby="projects-title">
          <p className="eyebrow">Espacio de trabajo</p>
          <h1 id="projects-title">Tus proyectos</h1>
          <p className="lede">Crea un modelo o continúa donde lo dejaste.</p>
        </section>

        <InvitationsSection onProjectAccepted={refreshProjects} />

        <div className="projects-layout">
          <section className="surface project-list-panel" aria-labelledby="project-list-title">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Biblioteca</p>
                <h2 id="project-list-title">All projects</h2>
              </div>
              <span className="count-chip">{projects.length}</span>
            </div>

            {error && (
              <div className="notice notice-error" role="alert">
                <span>{error}</span>
                <button className="text-button" type="button" onClick={() => window.location.reload()}>
                  Reintentar
                </button>
              </div>
            )}

            {loading ? (
              <p className="quiet-state" role="status">Cargando proyectos…</p>
            ) : projects.length === 0 ? (
              <div className="quiet-state empty-list">
                <span className="empty-mark" aria-hidden="true">—</span>
                <p>Aún no hay proyectos. Crea uno para empezar un diagrama de clases.</p>
              </div>
            ) : (
              <ul className="project-list">
                {projects.map((project) => (
                  <li className="project-row" key={project.id}>
                    <div className="project-row-copy">
                      <h3>{project.name}</h3>
                      <p>{project.description || 'No description'}</p>
                    </div>
                    <Link className="button button-secondary button-compact" to={`/projects/${encodeURIComponent(project.id)}`}>
                      Abrir
                      <span aria-hidden="true">↗</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="surface create-panel" aria-labelledby="create-project-title">
            <p className="eyebrow">Nuevo modelo</p>
            <h2 id="create-project-title">Crear un proyecto</h2>
            <p className="section-copy">Empieza con un nombre. Puedes agregar detalles a medida que el modelo tome forma.</p>

            <form className="project-form" onSubmit={(event) => void handleCreate(event)}>
              <label className="field-label" htmlFor="project-name">Nombre del proyecto</label>
              <input
                autoComplete="off"
                id="project-name"
                maxLength={200}
                onChange={(event) => setName(event.target.value)}
                placeholder="p. ej., sistema de biblioteca"
                required
                value={name}
              />

              <label className="field-label" htmlFor="project-description">Descripción <span>Opcional</span></label>
              <textarea
                id="project-description"
                onChange={(event) => setDescription(event.target.value)}
                placeholder="¿Qué vas a modelar?"
                rows={3}
                value={description}
              />

              <button className="button button-primary" disabled={saving} type="submit">
                {saving ? 'Creando…' : 'Crear proyecto'}
                {!saving && <span aria-hidden="true">→</span>}
              </button>
            </form>
          </section>
        </div>

        <footer className="page-footer">
          <span>Los modelos se guardan en tu espacio de trabajo.</span>
          <span>Hecho para pensar con claridad.</span>
        </footer>
      </div>
    </main>
  );
}
