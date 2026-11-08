// Synthetic fixture for reviewer-browser-check.mjs; no backend or real session is used.
import { createRoot } from 'react-dom/client';

const branch = (name: string) => ({ name, isProtected: false, headCommitId: null,
  headMessage: null, headAuthorDisplayName: null, headCreatedAt: null });
const members = [
  { userId: 'member-1', username: 'one', displayName: 'Member One', actions: ['VIEW'] },
  { userId: 'member-2', username: 'two', displayName: 'Member Two', actions: ['VIEW'] },
];
window.fetch = async (input, init) => {
  const path = String(input);
  if (init?.method === 'POST') {
    (window as unknown as { submitted: unknown }).submitted = JSON.parse(String(init.body));
    return Response.json({ mergeRequestId: 'request-1', title: 'Synthetic review', state: 'OPEN',
      sourceBranch: 'feature', targetBranch: 'main', approvals: [], approvalRequired: false,
      openedByUserId: null, openedByDisplayName: 'Synthetic', hasConflicts: false, conflicts: [],
      reviewers: [], createdAt: '2026-01-01T00:00:00Z' });
  }
  if (path.endsWith('/branches')) return Response.json([branch('main'), branch('feature')]);
  if (path.endsWith('/permissions')) return Response.json(members);
  if (path.endsWith('/merge-requests')) return Response.json([]);
  return new Response(null, { status: 404 });
};
const { default: MergeRequestsPanel } = await import('./MergeRequestsPanel');
createRoot(document.getElementById('root')!).render(<MergeRequestsPanel projectId="synthetic-project" />);
