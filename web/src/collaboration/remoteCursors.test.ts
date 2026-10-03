import type { PresencePerson } from '../api/types';
import { describe, expect, it } from 'vitest';
import { buildRemoteCursorMarkers } from './remoteCursors';

function person(userId: string, displayName: string, color: string): PresencePerson {
  return {
    userId,
    displayName,
    color,
    targetElementId: null,
    cursorX: null,
    cursorY: null,
  };
}

const readerId = 'reader-id';
const alex = person('user-a', 'Alex', '#458d64');
const bea = person('user-b', 'Bea', '#6a55b4');
const zoe = person('user-z', 'Zoe', '#b45566');

describe('buildRemoteCursorMarkers', () => {
  it('excludes the reader from remote markers', () => {
    const result = buildRemoteCursorMarkers(
      [person(readerId, 'Current Reader', '#111111'), alex],
      new Map([[readerId, { x: 1, y: 2 }], ['user-a', { x: 3, y: 4 }]]),
      readerId,
    );

    // This exact result fails if the reader is included as a remote cursor.
    expect(result).toEqual([{ userId: 'user-a', displayName: 'Alex', color: '#458d64', x: 3, y: 4 }]);
  });

  it('skips people without a cursor position', () => {
    const result = buildRemoteCursorMarkers(
      [alex, bea],
      new Map([['user-a', { x: 12, y: 18 }]]),
      readerId,
    );

    // This exact result fails if a person without a position is emitted.
    expect(result).toEqual([{ userId: 'user-a', displayName: 'Alex', color: '#458d64', x: 12, y: 18 }]);
  });

  it('orders markers by user id independently of presence arrival order', () => {
    const positions = new Map([
      ['user-a', { x: 1, y: 1 }],
      ['user-b', { x: 2, y: 2 }],
      ['user-z', { x: 3, y: 3 }],
    ]);
    const first = buildRemoteCursorMarkers([zoe, bea, alex], positions, readerId);
    const reversed = buildRemoteCursorMarkers([alex, bea, zoe], positions, readerId);

    // Both assertions fail if deterministic user-id ordering is removed.
    expect(first).toEqual(reversed);
    expect(first.map(({ userId }) => userId)).toEqual(['user-a', 'user-b', 'user-z']);
  });

  it("carries each person's own display name and color", () => {
    const result = buildRemoteCursorMarkers(
      [bea, alex],
      new Map([
        ['user-a', { x: 10, y: 20 }],
        ['user-b', { x: 30, y: 40 }],
      ]),
      readerId,
    );

    // The paired objects fail if names or colors are swapped between people.
    expect(result).toEqual([
      { userId: 'user-a', displayName: 'Alex', color: '#458d64', x: 10, y: 20 },
      { userId: 'user-b', displayName: 'Bea', color: '#6a55b4', x: 30, y: 40 },
    ]);
  });

  it('returns no markers for an empty presence list', () => {
    // This assertion fails if empty input produces any marker.
    expect(buildRemoteCursorMarkers([], new Map(), readerId)).toEqual([]);
  });
});
