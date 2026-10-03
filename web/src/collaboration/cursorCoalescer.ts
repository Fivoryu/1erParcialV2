export interface CursorCoalescerScheduler {
  now(): number;
  schedule(callback: () => void, delayMs: number): unknown;
  cancel(handle: unknown): void;
}

export interface CursorCoalescerOptions {
  minIntervalMs?: number;
  scheduler?: CursorCoalescerScheduler;
}

export interface CursorCoalescer<T> {
  schedule(position: T): void;
  cancel(): void;
}

export const DEFAULT_CURSOR_MIN_INTERVAL_MS = 50;

const browserScheduler: CursorCoalescerScheduler = {
  now: () => Date.now(),
  schedule: (callback, delayMs) => setTimeout(callback, delayMs),
  cancel: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

/** Rate-limit cursor reports while retaining the final position in each burst. */
export function createCursorCoalescer<T>(
  emit: (position: T) => void,
  options: CursorCoalescerOptions = {},
): CursorCoalescer<T> {
  // 50 ms yields 20 sends/second, inside RNF-11's 10–30 updates/second band.
  const minIntervalMs = options.minIntervalMs ?? DEFAULT_CURSOR_MIN_INTERVAL_MS;
  if (!Number.isFinite(minIntervalMs) || minIntervalMs < 34 || minIntervalMs > 100) {
    throw new RangeError('Cursor minIntervalMs must be between 34 and 100 ms.');
  }
  const scheduler = options.scheduler ?? browserScheduler;

  let lastSentAt: number | undefined;
  let pendingPosition: T | undefined;
  let hasPendingPosition = false;
  let timerHandle: unknown;
  let hasTimer = false;
  let active = true;

  function flushPending(): void {
    hasTimer = false;
    timerHandle = undefined;
    if (!active || !hasPendingPosition) {
      return;
    }

    const position = pendingPosition as T;
    pendingPosition = undefined;
    hasPendingPosition = false;
    lastSentAt = scheduler.now();
    emit(position);
  }

  return {
    schedule(position) {
      if (!active) {
        return;
      }

      const now = scheduler.now();
      const elapsed = lastSentAt === undefined ? Infinity : now - lastSentAt;
      if (lastSentAt === undefined || elapsed >= minIntervalMs) {
        if (hasTimer) {
          scheduler.cancel(timerHandle);
          hasTimer = false;
          timerHandle = undefined;
        }
        pendingPosition = undefined;
        hasPendingPosition = false;
        lastSentAt = now;
        emit(position);
        return;
      }

      pendingPosition = position;
      hasPendingPosition = true;
      if (!hasTimer) {
        timerHandle = scheduler.schedule(flushPending, minIntervalMs - elapsed);
        hasTimer = true;
      }
    },
    cancel() {
      active = false;
      if (hasTimer) {
        scheduler.cancel(timerHandle);
        hasTimer = false;
        timerHandle = undefined;
      }
      pendingPosition = undefined;
      hasPendingPosition = false;
    },
  };
}
