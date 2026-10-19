import { describe, expect, it } from 'vitest';

import { createTourProgress } from './tourProgress';

type MemoryStorage = {
  values: Map<string, string>;
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
  removeItem: (key: string) => void;
};

function createMemoryStorage(): MemoryStorage {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

describe('project tour progress', () => {
  it('shows the tour for a project that has not seen it', () => {
    const storage = createMemoryStorage();
    const tour = createTourProgress('project-a', storage);

    expect(tour.hasSeen()).toBe(false);
    expect(tour.shouldShow()).toBe(true);
  });

  it('keeps a completed tour hidden for that project', () => {
    const storage = createMemoryStorage();
    createTourProgress('project-a', storage).markSeen();

    const reopened = createTourProgress('project-a', storage);
    expect(reopened.hasSeen()).toBe(true);
    expect(reopened.shouldShow()).toBe(false);
    expect(createTourProgress('project-b', storage).shouldShow()).toBe(true);
  });

  it('shows the tour again after progress is reset', () => {
    const storage = createMemoryStorage();
    const tour = createTourProgress('project-a', storage);

    tour.markSeen();
    tour.reset();

    expect(tour.hasSeen()).toBe(false);
    expect(tour.shouldShow()).toBe(true);
  });

  it('uses a separate storage key for each project', () => {
    const storage = createMemoryStorage();
    createTourProgress('project-a', storage).markSeen();
    createTourProgress('project-b', storage).markSeen();

    expect([...storage.values.keys()]).toHaveLength(2);
    expect([...storage.values.keys()].every((key) => key.includes('project-'))).toBe(true);
  });
});
