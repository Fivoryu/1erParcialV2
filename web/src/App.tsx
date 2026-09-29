import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import ProjectsPage from './pages/ProjectsPage';

const ProjectEditorPage = lazy(() => import('./pages/ProjectEditorPage'));

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<ProjectsPage />} />
        <Route path="/projects/:projectId" element={(
          <Suspense fallback={<p role="status">Opening project…</p>}>
            <ProjectEditorPage />
          </Suspense>
        )} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
