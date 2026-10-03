import type { PresencePerson } from '../api/types';

export interface TouchedBy {
  userId: string;
  displayName: string;
  color: string;
}

export interface TouchedElement {
  elementId: string;
  touchedBy: TouchedBy[];
}

function compareText(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

export function groupTouchesByElement(presence: readonly PresencePerson[]): TouchedElement[] {
  const grouped = new Map<string, Map<string, TouchedBy>>();

  for (const person of presence) {
    if (person.targetElementId === null) {
      continue;
    }

    const touchers = grouped.get(person.targetElementId) ?? new Map<string, TouchedBy>();
    const candidate = {
      userId: person.userId,
      displayName: person.displayName,
      color: person.color,
    };
    const existing = touchers.get(person.userId);

    // Conflicting duplicate entries keep the lexicographically smallest name/color pair,
    // so the winner is deterministic instead of depending on presence arrival order.
    if (
      existing === undefined
      || compareText(candidate.displayName, existing.displayName) < 0
      || (
        candidate.displayName === existing.displayName
        && compareText(candidate.color, existing.color) < 0
      )
    ) {
      touchers.set(person.userId, candidate);
    }
    grouped.set(person.targetElementId, touchers);
  }

  return [...grouped.entries()]
    .map(([elementId, touchers]) => ({
      elementId,
      touchedBy: [...touchers.values()].sort((left, right) => compareText(left.userId, right.userId)),
    }))
    .sort((left, right) => compareText(left.elementId, right.elementId));
}
