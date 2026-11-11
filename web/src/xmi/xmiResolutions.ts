import type {
  XmiImportIssueResource,
  XmiImportMode,
  XmiResolutionRequest,
} from '../api/types';

export interface ResolutionChoice {
  action: 'RENAME' | 'SKIP';
  newName: string;
}

export type ResolutionChoices = Record<string, ResolutionChoice>;

const COLLISION_CODES = new Set([
  'NAME_COLLISION',
  'NAME_COLLISION_IN_FILE',
  'DIAGRAM_NAME_COLLISION',
  'NAME_COLLISION_BLOCKS',
]);

export function isCollisionCode(code: string): boolean {
  return COLLISION_CODES.has(code);
}

export function splitIssues(issues: XmiImportIssueResource[] | undefined): {
  blockers: XmiImportIssueResource[];
  warnings: XmiImportIssueResource[];
} {
  const all = issues ?? [];
  return {
    blockers: all.filter((item) => item.severity === 'BLOCKER'),
    warnings: all.filter((item) => item.severity === 'WARNING'),
  };
}

/** The payload for preview/import: only complete choices for blockers that offer that action. */
export function buildResolutions(
  blockers: XmiImportIssueResource[],
  choices: ResolutionChoices,
): XmiResolutionRequest[] {
  const resolutions: XmiResolutionRequest[] = [];
  for (const blocker of blockers) {
    const choice = choices[blocker.subject ?? ''];
    if (!choice || !blocker.options.includes(choice.action)) {
      continue;
    }
    if (choice.action === 'RENAME') {
      const newName = choice.newName.trim();
      if (newName !== '') {
        resolutions.push({ subject: blocker.subject ?? '', action: 'RENAME', newName });
      }
    } else {
      resolutions.push({ subject: blocker.subject ?? '', action: 'SKIP' });
    }
  }
  return resolutions;
}

/** Fills the user's choices with the suggestions; the user can still edit them afterwards. */
export function mergeSuggestions(
  current: ResolutionChoices,
  suggestions: XmiResolutionRequest[],
): ResolutionChoices {
  const merged: ResolutionChoices = { ...current };
  for (const suggestion of suggestions) {
    if (suggestion.action === 'RENAME' || suggestion.action === 'SKIP') {
      merged[suggestion.subject] = {
        action: suggestion.action,
        newName: suggestion.newName ?? '',
      };
    }
  }
  return merged;
}

export function canConfirmImport(
  preview: { issues?: XmiImportIssueResource[] },
  mode: XmiImportMode | null,
  busy: boolean,
): boolean {
  return !busy && mode !== null && splitIssues(preview.issues).blockers.length === 0;
}
