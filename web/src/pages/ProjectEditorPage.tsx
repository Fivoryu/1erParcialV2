import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiTransportError, createApiClient } from '../api/client';
import type { ModelResource, ProjectResource } from '../api/types';

const api = createApiClient();

function messageFor(error: unknown): string {
  if (error instanceof ApiTransportError) {
    return error.message;
  }
  return 'This project could not be opened. Check the connection and try again.';
}

export default function ProjectEditorPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const [project, setProject] = useState<ProjectResource | null>(null);
  const [model, setModel] = useState<ModelResource | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    if (!projectId) {
      setError('A project was not selected.');
      setLoading(false);
      return () => {
        active = false;
      };
    }

    void Promise.all([api.getProject(projectId), api.getModel(projectId)])
      .then(([projectResource, modelResource]) => {
        if (active) {
          setProject(projectResource);
          setModel(modelResource);
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
  }, [projectId]);

  const attributeCount = model?.classifiers.reduce((count, classifier) => count + classifier.attributes.length, 0) ?? 0;
  const operationCount = model?.classifiers.reduce((count, classifier) => count + classifier.operations.length, 0) ?? 0;
  const relationshipCount = model?.relationships.length ?? 0;
  const geometryCount = model?.views.reduce(
    (count, view) => count + view.element_geometry.length + view.connector_path.length,
    0,
  ) ?? 0;

  return (
    <main className="page-shell">
      <header className="topbar">
        <Link className="brand" to="/" aria-label="Class Diagrammer home">
          <span className="brand-mark" aria-hidden="true">C</span>
          <span>Class Diagrammer</span>
        </Link>
        <Link className="topbar-link" to="/">All projects <span aria-hidden="true">↗</span></Link>
      </header>

      <div className="content-wrap editor-wrap">
        {loading ? (
          <p className="quiet-state" role="status">Opening project…</p>
        ) : error ? (
          <div className="notice notice-error" role="alert">
            <span>{error}</span>
            <Link className="text-button" to="/">Back to projects</Link>
          </div>
        ) : project && model ? (
          <>
            <section className="editor-heading" aria-labelledby="project-title">
              <div>
                <p className="eyebrow">Project workspace</p>
                <h1 id="project-title">{project.name}</h1>
                {project.description && <p className="lede">{project.description}</p>}
              </div>
              <div className="revision-chip"><span className="status-dot" /> Revision {model.revision}</div>
            </section>

            <section className="model-overview" aria-label="Model contents">
              <article className="metric-card">
                <span className="metric-value">{model.classifiers.length}</span>
                <span className="metric-label">Classifiers</span>
              </article>
              <article className="metric-card">
                <span className="metric-value">{attributeCount}</span>
                <span className="metric-label">Attributes</span>
              </article>
              <article className="metric-card">
                <span className="metric-value">{operationCount}</span>
                <span className="metric-label">Operations</span>
              </article>
              <article className="metric-card">
                <span className="metric-value">{relationshipCount}</span>
                <span className="metric-label">Relationships</span>
              </article>
              <article className="metric-card">
                <span className="metric-value">{geometryCount}</span>
                <span className="metric-label">Layout items</span>
              </article>
            </section>

            <section className="surface canvas-placeholder" aria-labelledby="canvas-title">
              <div className="placeholder-toolbar">
                <div>
                  <p className="eyebrow">Class diagram</p>
                  <h2 id="canvas-title">Model overview</h2>
                </div>
                <span className="mode-chip">Canvas coming next</span>
              </div>
              <div className="placeholder-body">
                <div className="placeholder-grid" aria-hidden="true">
                  <span className="grid-node grid-node-one" />
                  <span className="grid-node grid-node-two" />
                  <span className="grid-line" />
                </div>
                <div className="placeholder-copy">
                  <span className="empty-mark" aria-hidden="true">＋</span>
                  <p className="eyebrow">Editor foundation</p>
                  <h3>Your model is ready for the canvas</h3>
                  <p>The project and model are connected. Class editing and diagram layout arrive in the next step.</p>
                </div>
              </div>
            </section>
          </>
        ) : null}
      </div>
    </main>
  );
}
