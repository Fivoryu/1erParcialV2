import { lazy, Suspense, useEffect, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { loadSession, subscribeToSession, type Session } from './api/session';
import LoginPage from './pages/LoginPage';
import ProjectsPage from './pages/ProjectsPage';

const ProjectEditorPage = lazy(() => import('./pages/ProjectEditorPage'));

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
            <Suspense fallback={<p role="status">Opening project…</p>}>
              <ProjectEditorPage />
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
