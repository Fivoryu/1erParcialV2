export interface GeometryCoalescer<T> {
  schedule(elementId: string, value: T): void;
  flush(elementId: string): void;
  flushAll(): void;
  cancelAll(): void;
}

/** Keep only the latest geometry update for an element until its gesture settles. */
export function createGeometryCoalescer<T>(
  emit: (elementId: string, value: T) => void,
  idleDelayMs = 240,
): GeometryCoalescer<T> {
  const pending = new Map<string, { value: T; timer: ReturnType<typeof setTimeout> }>();

  function flush(elementId: string): void {
    const update = pending.get(elementId);
    if (!update) {
      return;
    }
    clearTimeout(update.timer);
    pending.delete(elementId);
    emit(elementId, update.value);
  }

  return {
    schedule(elementId, value) {
      const current = pending.get(elementId);
      if (current) {
        clearTimeout(current.timer);
      }
      const timer = setTimeout(() => flush(elementId), idleDelayMs);
      pending.set(elementId, { value, timer });
    },
    flush,
    flushAll() {
      for (const elementId of [...pending.keys()]) {
        flush(elementId);
      }
    },
    cancelAll() {
      for (const update of pending.values()) {
        clearTimeout(update.timer);
      }
      pending.clear();
    },
  };
}
