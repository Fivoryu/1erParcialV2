import { describe, expect, it } from 'vitest';
import {
  createCursorCoalescer,
  type CursorCoalescerScheduler,
} from './cursorCoalescer';

class FakeScheduler implements CursorCoalescerScheduler {
  private currentTime = 0;
  private nextHandle = 1;
  private readonly tasks = new Map<number, { at: number; callback: () => void }>();

  now(): number {
    return this.currentTime;
  }

  schedule(callback: () => void, delayMs: number): number {
    const handle = this.nextHandle++;
    this.tasks.set(handle, { at: this.currentTime + delayMs, callback });
    return handle;
  }

  cancel(handle: unknown): void {
    this.tasks.delete(handle as number);
  }

  advanceBy(milliseconds: number): void {
    const targetTime = this.currentTime + milliseconds;
    while (true) {
      const next = [...this.tasks.entries()]
        .sort((first, second) => first[1].at - second[1].at || first[0] - second[0])[0];
      if (!next || next[1].at > targetTime) {
        break;
      }
      this.currentTime = next[1].at;
      this.tasks.delete(next[0]);
      next[1].callback();
    }
    this.currentTime = targetTime;
  }
}

describe('cursor coalescer', () => {
  it('emits immediately, limits rapid updates, and flushes the last pending position', () => {
    const scheduler = new FakeScheduler();
    const emissions: Array<{ position: number; at: number }> = [];
    const coalescer = createCursorCoalescer<number>(
      (position) => emissions.push({ position, at: scheduler.now() }),
      { minIntervalMs: 50, scheduler },
    );

    coalescer.schedule(1);
    expect(emissions).toEqual([{ position: 1, at: 0 }]);
    scheduler.advanceBy(10);
    coalescer.schedule(2);
    scheduler.advanceBy(10);
    coalescer.schedule(3);
    scheduler.advanceBy(29);
    expect(emissions).toHaveLength(1);
    scheduler.advanceBy(1);
    expect(emissions).toHaveLength(2);
    expect(emissions[1]).toEqual({ position: 3, at: 50 });

    scheduler.advanceBy(1);
    coalescer.schedule(4);
    scheduler.advanceBy(19);
    coalescer.schedule(5);
    scheduler.advanceBy(30);

    expect(emissions).toHaveLength(3);
    expect(emissions[2]).toEqual({ position: 5, at: 100 });
    expect(emissions.map(({ at }) => at)).toEqual([0, 50, 100]);
  });

  it('cancel stops the trailing update and ignores later schedules', () => {
    const scheduler = new FakeScheduler();
    const emissions: number[] = [];
    const coalescer = createCursorCoalescer<number>(
      (position) => emissions.push(position),
      { minIntervalMs: 50, scheduler },
    );

    coalescer.schedule(1);
    scheduler.advanceBy(10);
    coalescer.schedule(2);
    coalescer.cancel();
    scheduler.advanceBy(100);
    coalescer.schedule(3);
    scheduler.advanceBy(100);

    expect(emissions).toEqual([1]);
  });

  it('sends 21 updates for 100 positions over one second, inside the 10–30/s SLO band', () => {
    const scheduler = new FakeScheduler();
    const emissions: Array<{ position: number; at: number }> = [];
    const coalescer = createCursorCoalescer<number>(
      (position) => emissions.push({ position, at: scheduler.now() }),
      { minIntervalMs: 50, scheduler },
    );

    for (let position = 0; position < 100; position += 1) {
      if (position > 0) {
        scheduler.advanceBy(10);
      }
      coalescer.schedule(position);
    }
    scheduler.advanceBy(10);

    expect(emissions).toHaveLength(21);
    expect(emissions.length).toBeGreaterThanOrEqual(10);
    expect(emissions.length).toBeLessThanOrEqual(30);
    expect(emissions[emissions.length - 1]).toEqual({ position: 99, at: 1000 });
  });
});
