import type { PresencePerson } from '../api/types';
import { describe, expect, it } from 'vitest';
import { groupTouchesByElement } from './touch-highlight';

function person(userId: string, targetElementId: string | null, displayName: string, color: string): PresencePerson {
  return { userId, targetElementId, displayName, color, cursorX: null, cursorY: null };
}

describe('groupTouchesByElement', () => {
  it('groups touches by element and orders both touchers by user id', () => {
    const result = groupTouchesByElement([
      person('user-z', 'class-b', 'Zoe', '#b45566'),
      person('user-b', 'class-a', 'Bea', '#6a55b4'),
      person('user-a', 'class-a', 'Alex', '#458d64'),
    ]);

    expect(result).toEqual([
      {
        elementId: 'class-a',
        touchedBy: [
          { userId: 'user-a', displayName: 'Alex', color: '#458d64' },
          { userId: 'user-b', displayName: 'Bea', color: '#6a55b4' },
        ],
      },
      {
        elementId: 'class-b',
        touchedBy: [{ userId: 'user-z', displayName: 'Zoe', color: '#b45566' }],
      },
    ]);
  });

  it('skips people whose target is null', () => {
    const result = groupTouchesByElement([
      person('user-a', null, 'Alex', '#458d64'),
      person('user-b', 'class-a', 'Bea', '#6a55b4'),
    ]);

    expect(result).toEqual([{
      elementId: 'class-a',
      touchedBy: [{ userId: 'user-b', displayName: 'Bea', color: '#6a55b4' }],
    }]);
  });

  it('produces the same sorted result when the same presence entries arrive in a different order', () => {
    const firstInput = [
      person('user-z', 'class-b', 'Zoe', '#b45566'),
      person('user-b', 'class-a', 'Bea', '#6a55b4'),
      person('user-a', 'class-a', 'Alex', '#458d64'),
    ];
    const secondInput = [firstInput[2]!, firstInput[0]!, firstInput[1]!];
    const firstResult = groupTouchesByElement(firstInput);

    expect(groupTouchesByElement(secondInput)).toEqual(firstResult);
    expect(firstResult.map(({ elementId }) => elementId)).toEqual(['class-a', 'class-b']);
    expect(firstResult[0]?.touchedBy.map(({ userId }) => userId)).toEqual(['user-a', 'user-b']);
  });

  it('deduplicates conflicting entries for one user deterministically', () => {
    const ada = person('user-a', 'class-a', 'Ada', '#458d64');
    const zoe = person('user-a', 'class-a', 'Zoe', '#b45566');
    const firstResult = groupTouchesByElement([zoe, ada]);

    // This exact-array assertion requires one canonical entry, not two duplicate user ids.
    expect(firstResult[0]?.touchedBy).toEqual([
      { userId: 'user-a', displayName: 'Ada', color: '#458d64' },
    ]);
    expect(groupTouchesByElement([ada, zoe])).toEqual(firstResult);
  });

  it('returns no touched elements for an empty presence list', () => {
    expect(groupTouchesByElement([])).toEqual([]);
  });
});
