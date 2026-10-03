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
        <p className="editing-lease-notice-title">In this diagram, {conflict.editorDisplayName} is editing.</p>
        <p>
          {conflict.reclaimable
            ? 'The editing lease has expired.'
            : 'Export is blocked until their lease expires.'}
        </p>
      </div>
      <button
        className="button button-secondary"
        type="button"
        onClick={onReclaim}
        disabled={!conflict.reclaimable}
        aria-busy={busy || undefined}
      >
        {busy ? 'Reclaiming…' : 'Reclaim control'}
      </button>
    </aside>
  );
}
