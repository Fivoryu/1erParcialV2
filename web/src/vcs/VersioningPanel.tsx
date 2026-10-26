import { useCallback, useEffect, useRef, useState } from 'react';
import type { ModelResource, Uuid } from '../api/types';
import BranchesPanel from './BranchesPanel';
import HistoryPanel from './HistoryPanel';
import MergeRequestsPanel from './MergeRequestsPanel';
import PermissionsPanel from './PermissionsPanel';
import {
  createVcsApiClient,
  type VcsBranch,
  type VcsStatus,
} from './api';
import { relativeTimeLabel } from './presentation';

const api = createVcsApiClient();

export type VersioningTab = 'branches' | 'history' | 'reviews' | 'permissions';
export type VersioningTabDirection = 'next' | 'previous';

const VERSIONING_TABS: ReadonlyArray<{ id: VersioningTab; label: string }> = [
  { id: 'branches', label: 'Ramas' },
  { id: 'history', label: 'Historial' },
  { id: 'reviews', label: 'Revisiones' },
  { id: 'permissions', label: 'Permisos' },
];

export function nextVersioningTab(current: VersioningTab, direction: VersioningTabDirection): VersioningTab {
  const currentIndex = VERSIONING_TABS.findIndex((tab) => tab.id === current);
  const offset = direction === 'next' ? 1 : -1;
  const nextIndex = (currentIndex + offset + VERSIONING_TABS.length) % VERSIONING_TABS.length;
  return VERSIONING_TABS[nextIndex]!.id;
}

export interface VersioningStatusBarProps {
  status: VcsStatus | null;
  headBranch: VcsBranch | null;
  loading: boolean;
  error: string | null;
}

export function VersioningStatusBar({ status, headBranch, loading, error }: VersioningStatusBarProps) {
  if (status === null) {
    return (
      <div className="vcs-status-bar" role={error ? 'alert' : 'status'}>
        <p className="vcs-status-message">
          {error ?? (loading ? 'Cargando el estado del versionado…' : 'El estado del versionado no está disponible.')}
        </p>
      </div>
    );
  }

  return (
    <div className="vcs-status-bar" role="status" aria-label="Estado del versionado">
      <span className="vcs-current-branch" aria-label={`Rama actual: ${status.branch}`}>
        Rama actual <strong>{status.branch}</strong>
      </span>
      <span className={`vcs-dirty-indicator${status.dirty ? ' is-dirty' : ' is-clean'}`}>
        {status.dirty ? 'cambios sin confirmar' : 'limpio'}
      </span>
      <p className="vcs-head-summary">
        {headBranch?.headMessage ?? (status.headCommitId === null
          ? 'Aún no hay confirmaciones.'
          : loading ? 'Cargando el último cambio…' : 'Los detalles del cambio no están disponibles.')}
        {headBranch?.headAuthorDisplayName && ` · ${headBranch.headAuthorDisplayName}`}
        {headBranch?.headCreatedAt && ` · ${relativeTimeLabel(headBranch.headCreatedAt)}`}
      </p>
    </div>
  );
}

interface VersioningPanelViewProps {
  projectId: Uuid;
  status: VcsStatus | null;
  headBranch: VcsBranch | null;
  loading: boolean;
  error: string | null;
  onStatusChanged(): void;
  onModelCheckout(model: ModelResource): void;
  historyDetailOpen: boolean;
  onHistoryDetailOpenChange(open: boolean): void;
}

export function VersioningPanelView({
  projectId,
  status,
  headBranch,
  loading,
  error,
  onStatusChanged,
  onModelCheckout,
  historyDetailOpen,
  onHistoryDetailOpenChange,
}: VersioningPanelViewProps) {
  const [activeTab, setActiveTab] = useState<VersioningTab>('history');
  const tabRefs = useRef<Partial<Record<VersioningTab, HTMLButtonElement | null>>>({});

  function changeTab(tab: VersioningTab, focus = false): void {
    if (historyDetailOpen && tab !== 'history') {
      return;
    }
    setActiveTab(tab);
    if (focus) {
      tabRefs.current[tab]?.focus();
    }
  }

  function handleTabKeyDown(event: React.KeyboardEvent<HTMLButtonElement>, current: VersioningTab): void {
    let next: VersioningTab | null = null;
    if (event.key === 'ArrowRight') {
      next = nextVersioningTab(current, 'next');
    } else if (event.key === 'ArrowLeft') {
      next = nextVersioningTab(current, 'previous');
    } else if (event.key === 'Home') {
      next = VERSIONING_TABS[0]!.id;
    } else if (event.key === 'End') {
      next = VERSIONING_TABS[VERSIONING_TABS.length - 1]!.id;
    }
    if (next !== null) {
      event.preventDefault();
      changeTab(next, true);
    }
  }

  return (
    <div className="vcs-workspace">
      <VersioningStatusBar
        status={status}
        headBranch={headBranch}
        loading={loading}
        error={error}
      />
      <div className="vcs-tab-list" role="tablist" aria-label="Secciones de versionado">
        {VERSIONING_TABS.map((tab) => {
          const selected = activeTab === tab.id;
          const disabled = historyDetailOpen && tab.id !== 'history';
          return (
            <button
              aria-controls={`vcs-panel-${tab.id}`}
              aria-selected={selected}
              className="vcs-tab"
              disabled={disabled}
              id={`vcs-tab-${tab.id}`}
              key={tab.id}
              onClick={() => changeTab(tab.id)}
              onKeyDown={(event) => handleTabKeyDown(event, tab.id)}
              ref={(node) => {
                tabRefs.current[tab.id] = node;
              }}
              role="tab"
              tabIndex={selected ? 0 : -1}
              type="button"
            >
              {tab.label}
            </button>
          );
        })}
      </div>
      {VERSIONING_TABS.map((tab) => (
        <div
          aria-labelledby={`vcs-tab-${tab.id}`}
          className="vcs-tabpanel"
          hidden={activeTab !== tab.id}
          id={`vcs-panel-${tab.id}`}
          key={tab.id}
          role="tabpanel"
          tabIndex={0}
        >
          {tab.id === 'branches' && (
            <BranchesPanel
              projectId={projectId}
              onModelCheckout={onModelCheckout}
              onStatusChanged={onStatusChanged}
            />
          )}
          {tab.id === 'history' && (
            <HistoryPanel
              projectId={projectId}
              onStatusChanged={onStatusChanged}
              onDetailOpenChange={onHistoryDetailOpenChange}
            />
          )}
          {tab.id === 'reviews' && <MergeRequestsPanel projectId={projectId} />}
          {tab.id === 'permissions' && <PermissionsPanel projectId={projectId} />}
        </div>
      ))}
    </div>
  );
}

export interface VersioningPanelProps {
  projectId: Uuid;
  onModelCheckout(model: ModelResource): void;
}

export default function VersioningPanel({ projectId, onModelCheckout }: VersioningPanelProps) {
  const [status, setStatus] = useState<VcsStatus | null>(null);
  const [branches, setBranches] = useState<VcsBranch[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshSequence, setRefreshSequence] = useState(0);
  const [historyDetailOpen, setHistoryDetailOpen] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setStatus(null);
    setBranches([]);
    void Promise.allSettled([api.getStatus(projectId), api.listBranches(projectId)])
      .then(([statusResult, branchesResult]) => {
        if (!active) {
          return;
        }
        if (statusResult.status === 'fulfilled') {
          setStatus(statusResult.value);
        } else {
          setError('No se pudo cargar el estado del versionado.');
        }
        if (branchesResult.status === 'fulfilled') {
          setBranches(branchesResult.value);
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
  }, [projectId, refreshSequence]);

  const onStatusChanged = useCallback(() => {
    setRefreshSequence((current) => current + 1);
  }, []);

  const onHistoryDetailOpenChange = useCallback((open: boolean) => {
    setHistoryDetailOpen(open);
  }, []);
  const headBranch = branches.find((branch) => branch.name === status?.branch) ?? null;

  return (
    <VersioningPanelView
      projectId={projectId}
      status={status}
      headBranch={headBranch}
      loading={loading}
      error={error}
      onStatusChanged={onStatusChanged}
      onModelCheckout={onModelCheckout}
      historyDetailOpen={historyDetailOpen}
      onHistoryDetailOpenChange={onHistoryDetailOpenChange}
    />
  );
}
