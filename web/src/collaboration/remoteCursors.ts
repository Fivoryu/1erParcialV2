import type { PresencePerson } from '../api/types';

export interface RemoteCursorMarker {
  userId: string;
  displayName: string;
  color: string;
  x: number;
  y: number;
}

function compareText(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

export function buildRemoteCursorMarkers(
  presence: readonly PresencePerson[],
  cursorPositions: ReadonlyMap<string, { x: number; y: number }>,
  readerUserId: string | null,
): RemoteCursorMarker[] {
  return presence
    .flatMap((person) => {
      const position = cursorPositions.get(person.userId);
      if (person.userId === readerUserId || position === undefined) {
        return [];
      }
      return [{
        userId: person.userId,
        displayName: person.displayName,
        color: person.color,
        x: position.x,
        y: position.y,
      }];
    })
    .sort((left, right) => compareText(left.userId, right.userId)
      || compareText(left.displayName, right.displayName)
      || compareText(left.color, right.color));
}
