import type { EditingLeaseConflict } from '../api/types';

export interface EditingLeaseNoticeProps {
  conflict: EditingLeaseConflict | null;
  onReclaim?: (() => void) | undefined;
  busy: boolean;
}

export default function EditingLeaseNotice({ conflict, onReclaim, busy }: EditingLeaseNoticeProps) {
  if (conflict === null) {
    return null;
  }

  return (
    <aside className="notice editing-lease-notice" role="status">
      <div>
        <p className="editing-lease-notice-title">{conflict.editorDisplayName} está editando este diagrama.</p>
        <p>
          {conflict.reclaimable
            ? 'El permiso de edición ha expirado.'
            : 'La exportación estará bloqueada hasta que expire su permiso.'}
        </p>
      </div>
      <button
        className="button button-secondary"
        type="button"
        onClick={onReclaim}
        disabled={!conflict.reclaimable}
        aria-busy={busy || undefined}
      >
        {busy ? 'Recuperando el control…' : 'Recuperar el control'}
      </button>
    </aside>
  );
}
