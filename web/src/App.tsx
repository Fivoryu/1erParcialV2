import { lazy, Suspense, useEffect, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useParams } from 'react-router-dom';
import { loadSession, subscribeToSession, type Session } from './api/session';
import LoginPage from './pages/LoginPage';
import ProjectsPage from './pages/ProjectsPage';

const ProjectEditorPage = lazy(() => import('./pages/ProjectEditorPage'));

/**
 * Keyed by project so switching projects remounts the editor instead of reusing an instance whose refs belong to the
 * previous one. The remount discards that state and aborts its presence stream, so no late answer from the previous
 * project can land on the project now on screen. It does not cancel commands the user already queued: those keep
 * applying to the project they were issued for, which is deliberate.
 */
function EditorRoute() {
  const { projectId } = useParams<{ projectId: string }>();
  return <ProjectEditorPage key={projectId ?? 'no-project'} />;
}

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession);

  useEffect(() => {
    const unsubscribe = subscribeToSession(setSession);
    setSession(loadSession());
    return unsubscribe;
  }, []);

  return (
    <BrowserRouter>
      {session ? (
        <Routes>
          <Route path="/" element={<ProjectsPage />} />
          <Route path="/projects/:projectId" element={(
            <Suspense fallback={<p role="status">Abriendo proyecto…</p>}>
              <EditorRoute />
            </Suspense>
          )} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      ) : (
        <Routes>
          <Route path="*" element={<LoginPage />} />
        </Routes>
      )}
    </BrowserRouter>
  );
}
