// Pure focus and dismissal helpers for the accessible modal primitive.
// No React and no DOM types: this module is unit-tested without a DOM environment.

export const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), '
  + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * True only for a dismissal key ('Escape', or the legacy 'Esc' name) while the
 * dialog is dismissible. Any other key, or a non-dismissible dialog, is false.
 */
export function isDismissKey(key: string, dismissible: boolean): boolean {
  if (!dismissible) {
    return false;
  }
  return key === 'Escape' || key === 'Esc';
}

/**
 * Wrap-around Tab traversal that traps focus inside the dialog.
 * - No focusable elements -> -1 (nothing to focus).
 * - Unfocused dialog (currentIndex < 0) -> first element, or last on Shift+Tab.
 * - Otherwise -> one step forward/backward with modulo wrap. An out-of-range
 *   currentIndex (e.g. a stale index after the content changed) is normalized
 *   with modulo first, so the result always stays inside [0, focusableCount - 1].
 */
export function nextFocusIndex(currentIndex: number, focusableCount: number, shiftKey: boolean): number {
  if (focusableCount <= 0) {
    return -1;
  }

  if (currentIndex < 0) {
    return shiftKey ? focusableCount - 1 : 0;
  }

  const normalized = ((currentIndex % focusableCount) + focusableCount) % focusableCount;
  if (shiftKey) {
    return (normalized - 1 + focusableCount) % focusableCount;
  }
  return (normalized + 1) % focusableCount;
}
