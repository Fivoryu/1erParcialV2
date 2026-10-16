export type TourStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

const TOUR_SEEN_VALUE = 'seen';

export function createTourProgress(projectId: string, storage: TourStorage) {
  const key = `editor-onboarding-tour:v1:${projectId}`;

  function hasSeen(): boolean {
    return storage.getItem(key) === TOUR_SEEN_VALUE;
  }

  return {
    hasSeen,
    shouldShow: () => !hasSeen(),
    markSeen: () => storage.setItem(key, TOUR_SEEN_VALUE),
    reset: () => storage.removeItem(key),
  };
}
