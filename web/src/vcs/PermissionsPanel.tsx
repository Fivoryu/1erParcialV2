import { useEffect, useState } from 'react';
import type { Uuid } from '../api/types';
import {
  createVcsApiClient,
  permissionUpdatePayload,
  VCS_PERMISSION_ACTIONS,
  vcsErrorMessage,
  type VcsPermissionAction,
  type VcsProjectPermission,
} from './api';

const api = createVcsApiClient();

function permissionActionLabel(action: VcsPermissionAction): string {
  const words = action.toLowerCase().split('_');
  return words.map((word) => word[0]?.toUpperCase() + word.slice(1)).join(' ');
}

export interface PermissionsPanelViewProps {
  permissions: VcsProjectPermission[];
  loading: boolean;
  busyUserId: Uuid | null;
  error: string | null;
  onToggleAction(permission: VcsProjectPermission, action: VcsPermissionAction, checked: boolean): void;
  onDismissError(): void;
}

export function PermissionsPanelView({
  permissions,
  loading,
  busyUserId,
  error,
  onToggleAction,
  onDismissError,
}: PermissionsPanelViewProps) {
  return (
    <section className="surface vcs-panel permissions-panel" aria-labelledby="vcs-permissions-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Versioning</p>
          <h2 id="vcs-permissions-title">Permissions</h2>
        </div>
      </div>

      {error && (
        <div className="notice notice-error" role="alert">
          <span>{error}</span>
          <button className="text-button" type="button" onClick={onDismissError}>Dismiss</button>
        </div>
      )}

      {loading ? (
        <p className="quiet-state" role="status">Loading permissions…</p>
      ) : permissions.length === 0 ? (
        <p className="quiet-state">No project permissions are available.</p>
      ) : (
        <ul className="vcs-permission-list">
          {permissions.map((permission) => (
            <li className="vcs-permission-row" key={permission.userId}>
              <div className="vcs-permission-copy">
                <h3>{permission.displayName}</h3>
                <p>{permission.username}</p>
              </div>
              <div className="vcs-permission-actions">
                {VCS_PERMISSION_ACTIONS.map((action) => (
                  <label className="vcs-permission-action" key={action}>
                    <input
                      aria-label={`${permissionActionLabel(action)} for ${permission.displayName}`}
                      checked={permission.actions.includes(action)}
                      disabled={busyUserId !== null}
                      onChange={(event) => onToggleAction(permission, action, event.target.checked)}
                      type="checkbox"
                    />
                    <span>{permissionActionLabel(action)}</span>
                  </label>
                ))}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export interface PermissionsPanelProps {
  projectId: Uuid;
}

export default function PermissionsPanel({ projectId }: PermissionsPanelProps) {
  const [permissions, setPermissions] = useState<VcsProjectPermission[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyUserId, setBusyUserId] = useState<Uuid | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    setPermissions([]);
    void api.listPermissions(projectId)
      .then((loadedPermissions) => {
        if (active) {
          setPermissions(loadedPermissions);
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(vcsErrorMessage(cause, 'The project permissions could not be loaded.'));
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

  async function handleToggleAction(
    permission: VcsProjectPermission,
    action: VcsPermissionAction,
    checked: boolean,
  ): Promise<void> {
    if (busyUserId !== null) {
      return;
    }
    const checkedActions = checked
      ? [...permission.actions, action]
      : permission.actions.filter((current) => current !== action);
    setBusyUserId(permission.userId);
    setError(null);
    try {
      const updated = await api.updatePermissions(
        projectId,
        permission.userId,
        permissionUpdatePayload(checkedActions),
      );
      setPermissions((current) => current.map((item) => item.userId === updated.userId ? updated : item));
    } catch (cause: unknown) {
      setError(vcsErrorMessage(cause, 'The project permissions could not be updated.'));
    } finally {
      setBusyUserId(null);
    }
  }

  return (
    <PermissionsPanelView
      permissions={permissions}
      loading={loading}
      busyUserId={busyUserId}
      error={error}
      onToggleAction={(permission, action, checked) => void handleToggleAction(permission, action, checked)}
      onDismissError={() => setError(null)}
    />
  );
}
