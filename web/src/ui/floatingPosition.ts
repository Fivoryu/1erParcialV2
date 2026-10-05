/**
 * Pure geometry and storage helpers for the floating assistant button.
 * No React and no DOM access, so all behaviour is unit-testable without jsdom.
 */

export type FloatingPosition = { x: number; y: number };
export type FloatingViewport = { width: number; height: number };

/** Button diameter in px (`.floating-assistant` width/height). */
export const FLOATING_BUTTON_SIZE = 56;
/** Minimum distance from every viewport edge, in px. */
export const FLOATING_EDGE_INSET = 24;
/** Pointer travel (max of |dx|, |dy|) that turns a click into a drag gesture. */
export const DRAG_CLICK_THRESHOLD = 4;

export const FLOATING_POSITION_STORAGE_KEY = 'uml-editor.floating-assistant.position';

/** Bottom-right corner, offset by the inset, inside the given viewport. */
export function defaultPosition(viewport: FloatingViewport): FloatingPosition {
  return {
    x: viewport.width - FLOATING_BUTTON_SIZE - FLOATING_EDGE_INSET,
    y: viewport.height - FLOATING_BUTTON_SIZE - FLOATING_EDGE_INSET,
  };
}

/**
 * Keeps the whole button inside the viewport, never closer to an edge than the
 * inset. Total: a viewport smaller than button + 2*inset degenerates, so the
 * clamp floor becomes 0 (the top-left corner) rather than a negative or NaN
 * coordinate — the button stays at least partially on screen in every case.
 */
export function clampToViewport(position: FloatingPosition, viewport: FloatingViewport): FloatingPosition {
  const maxX = viewport.width - FLOATING_BUTTON_SIZE - FLOATING_EDGE_INSET;
  const maxY = viewport.height - FLOATING_BUTTON_SIZE - FLOATING_EDGE_INSET;
  const clamp = (value: number, max: number): number => {
    const finite = Number.isFinite(value) ? value : max;
    const floor = Math.max(max, 0);
    return Math.min(Math.max(finite, FLOATING_EDGE_INSET <= floor ? FLOATING_EDGE_INSET : 0), floor);
  };
  return { x: clamp(position.x, maxX), y: clamp(position.y, maxY) };
}

/**
 * True once the pointer has travelled at least `threshold` px from where it
 * went down, measured as the max of the absolute dx/dy. Used to suppress the
 * click that the browser fires after a drag, which would otherwise open the
 * dialog on every reposition.
 */
export function isDragGesture(start: FloatingPosition, end: FloatingPosition, threshold: number): boolean {
  const dx = Math.abs(end.x - start.x);
  const dy = Math.abs(end.y - start.y);
  return Math.max(dx, dy) >= threshold;
}

/**
 * Position the button should take while dragging: the offset of the pointer
 * from where the drag started, applied to the position the button had when the
 * drag started. It must NOT depend on the absolute pointer coordinates.
 * No clamping here: keeping the result on screen stays the caller's job.
 */
export function dragPosition(
  startPosition: FloatingPosition,
  startPointer: FloatingPosition,
  currentPointer: FloatingPosition,
): FloatingPosition {
  return {
    x: startPosition.x + (currentPointer.x - startPointer.x),
    y: startPosition.y + (currentPointer.y - startPointer.y),
  };
}

/**
 * Reads a stored position, returning null for anything that is not a valid
 * `{ x, y }` pair of finite numbers. A corrupt entry must never break the
 * editor, so parse and validation errors are all mapped to null.
 */
export function readStoredPosition(storage: Pick<Storage, 'getItem'>): FloatingPosition | null {
  let raw: string | null;
  try {
    raw = storage.getItem(FLOATING_POSITION_STORAGE_KEY);
  } catch {
    return null;
  }
  if (raw === null) {
    return null;
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }

  if (typeof parsed !== 'object' || parsed === null) {
    return null;
  }
  const candidate = parsed as Record<string, unknown>;
  const x = candidate.x;
  const y = candidate.y;
  if (typeof x !== 'number' || typeof y !== 'number') {
    return null;
  }
  if (!Number.isFinite(x) || !Number.isFinite(y)) {
    return null;
  }
  return { x, y };
}

/** Persists the position, swallowing storage failures (private-mode quota). */
export function writeStoredPosition(
  storage: Pick<Storage, 'setItem'>,
  position: FloatingPosition,
): void {
  try {
    storage.setItem(FLOATING_POSITION_STORAGE_KEY, JSON.stringify(position));
  } catch {
    // Storage may be unavailable or full (private mode); the position is a
    // preference, so a failed write must never break the editor.
  }
}
