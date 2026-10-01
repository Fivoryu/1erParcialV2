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
  return 'Projects could not be loaded. Check the connection and try again.';
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
        <Link className="brand" to="/" aria-label="Class Diagrammer home">
          <span className="brand-mark" aria-hidden="true">C</span>
          <span>Class Diagrammer</span>
        </Link>
        <div className="topbar-account">
          <span className="topbar-note">Signed in as <strong>{session.displayName}</strong></span>
          <button className="text-button" disabled={loggingOut} onClick={() => void handleLogout()} type="button">
            {loggingOut ? 'Signing out…' : 'Log out'}
          </button>
        </div>
      </header>

      <div className="content-wrap">
        <section className="page-heading" aria-labelledby="projects-title">
          <p className="eyebrow">Workspace</p>
          <h1 id="projects-title">Your projects</h1>
          <p className="lede">Create a model or pick up where you left off.</p>
        </section>

        <InvitationsSection onProjectAccepted={refreshProjects} />

        <div className="projects-layout">
          <section className="surface project-list-panel" aria-labelledby="project-list-title">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Library</p>
                <h2 id="project-list-title">All projects</h2>
              </div>
              <span className="count-chip">{projects.length}</span>
            </div>

            {error && (
              <div className="notice notice-error" role="alert">
                <span>{error}</span>
                <button className="text-button" type="button" onClick={() => window.location.reload()}>
                  Retry
                </button>
              </div>
            )}

            {loading ? (
              <p className="quiet-state" role="status">Loading projects…</p>
            ) : projects.length === 0 ? (
              <div className="quiet-state empty-list">
                <span className="empty-mark" aria-hidden="true">—</span>
                <p>No projects yet. Create one to start a class diagram.</p>
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
                      Open
                      <span aria-hidden="true">↗</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="surface create-panel" aria-labelledby="create-project-title">
            <p className="eyebrow">New model</p>
            <h2 id="create-project-title">Create a project</h2>
            <p className="section-copy">Start with a name. You can add detail as the model takes shape.</p>

            <form className="project-form" onSubmit={(event) => void handleCreate(event)}>
              <label className="field-label" htmlFor="project-name">Project name</label>
              <input
                autoComplete="off"
                id="project-name"
                maxLength={200}
                onChange={(event) => setName(event.target.value)}
                placeholder="e.g. Library system"
                required
                value={name}
              />

              <label className="field-label" htmlFor="project-description">Description <span>Optional</span></label>
              <textarea
                id="project-description"
                onChange={(event) => setDescription(event.target.value)}
                placeholder="What are you modelling?"
                rows={3}
                value={description}
              />

              <button className="button button-primary" disabled={saving} type="submit">
                {saving ? 'Creating…' : 'Create project'}
                {!saving && <span aria-hidden="true">→</span>}
              </button>
            </form>
          </section>
        </div>

        <footer className="page-footer">
          <span>Models are saved to your workspace.</span>
          <span>Built for clear thinking.</span>
        </footer>
      </div>
    </main>
  );
}
