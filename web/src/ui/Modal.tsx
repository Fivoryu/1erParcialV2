import { useEffect, useRef, useId, type ReactElement, type ReactNode } from 'react';
import { FOCUSABLE_SELECTOR, isDismissKey, nextFocusIndex } from './modalBehavior';

export type ModalSize = 'sm' | 'md' | 'lg';

export type ModalProps = {
  open: boolean;
  title: string;
  eyebrow?: string;
  description?: string;
  /**
   * When false the whole title block (eyebrow, heading, description) is not
   * rendered and the dialog is named via `aria-label={title}` instead of
   * `aria-labelledby`. Used by section dialogs whose content panel already
   * renders its own heading; defaults to true (previous behaviour).
   */
  titleVisible?: boolean;
  size?: ModalSize;
  dismissible?: boolean;
  onClose: () => void;
  children: ReactNode;
};

/**
 * Accessible modal rendered inline (no portal) as a fixed overlay, so it works
 * under server rendering. Every DOM effect is browser-only, guarded by `open`,
 * and cleaned up on close/unmount. When closed it renders nothing at all, which
 * is what keeps the heavy panels from mounting until they are needed.
 */
export default function Modal({
  open,
  title,
  eyebrow,
  description,
  titleVisible = true,
  size = 'md',
  dismissible = true,
  onClose,
  children,
}: ModalProps): ReactElement | null {
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useRef<HTMLDivElement | null>(null);
  // The description is only rendered inside the visible title block, so its id
  // must not be referenced when that block is suppressed.
  const showDescription = titleVisible && description !== undefined;

  // Focus lifecycle. Deliberately depends on `open` alone: a parent re-render while the
  // dialog is open (typing inside one of its panels, for instance) must not recapture
  // document.activeElement, or the element to restore focus to would become one inside
  // the dialog and the trigger would be lost for good.
  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const dialog = dialogRef.current;
    const initialFocus = dialog?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? dialog;
    initialFocus?.focus();

    return () => {
      if (previouslyFocused?.isConnected) {
        previouslyFocused.focus();
      }
    };
  }, [open]);

  // Scroll lock owns its own lifecycle so it can never be entangled with focus.
  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  // Dismissal and the Tab trap. Re-registering one listener per render is cheap and
  // keeps it out of the focus lifecycle.
  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (isDismissKey(event.key, dismissible)) {
        event.preventDefault();
        onClose();
        return;
      }

      if (event.key !== 'Tab') {
        return;
      }

      // Trap Tab inside the dialog: wrap around its own focusable elements.
      const dialog = dialogRef.current;
      const focusable = dialog
        ? Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
        : [];
      const activeElement =
        document.activeElement instanceof HTMLElement ? document.activeElement : null;
      const currentIndex = activeElement ? focusable.indexOf(activeElement) : -1;
      const nextIndex = nextFocusIndex(currentIndex, focusable.length, event.shiftKey);
      event.preventDefault();
      if (nextIndex !== -1) {
        focusable[nextIndex]?.focus();
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open, dismissible, onClose]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="editor-modal-backdrop"
      onClick={(event) => {
        if (dismissible && event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        ref={dialogRef}
        className={`editor-modal editor-modal-${size}`}
        role="dialog"
        aria-modal="true"
        tabIndex={-1}
        aria-label={titleVisible ? undefined : title}
        aria-labelledby={titleVisible ? titleId : undefined}
        aria-describedby={showDescription ? descriptionId : undefined}
      >
        <header className="editor-modal-header">
          {titleVisible ? (
            <div>
              {eyebrow === undefined ? null : <p className="eyebrow">{eyebrow}</p>}
              <h2 className="editor-modal-title" id={titleId}>{title}</h2>
              {description === undefined ? null : (
                <p className="modal-description" id={descriptionId}>{description}</p>
              )}
            </div>
          ) : null}
          <button
            type="button"
            className="modal-close"
            aria-label="Close dialog"
            disabled={!dismissible}
            onClick={dismissible ? onClose : undefined}
          >
            ×
          </button>
        </header>
        <div className="editor-modal-body">{children}</div>
      </div>
    </div>
  );
}
