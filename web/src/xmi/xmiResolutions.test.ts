import { describe, expect, it } from 'vitest';
import type { XmiImportIssueResource } from '../api/types';
import {
  buildResolutions,
  canConfirmImport,
  isCollisionCode,
  mergeSuggestions,
  splitIssues,
  type ResolutionChoices,
} from './xmiResolutions';

function issue(overrides: Partial<XmiImportIssueResource> = {}): XmiImportIssueResource {
  return {
    code: 'NAME_COLLISION',
    severity: 'BLOCKER',
    subject: 'id-1',
    name: 'Customer',
    message: 'Ya existe Customer',
    options: ['RENAME', 'SKIP'],
    ...overrides,
  };
}

describe('xmi resolutions state helpers', () => {
  it('splits issues into blockers and warnings', () => {
    const blocker = issue();
    const warning = issue({ severity: 'WARNING', options: [], subject: 'id-2' });
    expect(splitIssues([blocker, warning])).toEqual({ blockers: [blocker], warnings: [warning] });
    expect(splitIssues(undefined)).toEqual({ blockers: [], warnings: [] });
  });

  it('recognises collision codes including the legacy key', () => {
    expect(isCollisionCode('NAME_COLLISION')).toBe(true);
    expect(isCollisionCode('NAME_COLLISION_IN_FILE')).toBe(true);
    expect(isCollisionCode('DIAGRAM_NAME_COLLISION')).toBe(true);
    expect(isCollisionCode('NAME_COLLISION_BLOCKS')).toBe(true);
    expect(isCollisionCode('OTHER')).toBe(false);
  });

  it('builds the payload only from complete choices of blockers', () => {
    const blockers = [
      issue({ subject: 'a' }),
      issue({ subject: 'b' }),
      issue({ subject: 'c' }),
      issue({ subject: 'd', options: ['SKIP'] }),
    ];
    const choices: ResolutionChoices = {
      a: { action: 'RENAME', newName: '  Customer 2 ' },
      b: { action: 'RENAME', newName: '   ' },
      c: { action: 'SKIP', newName: 'ignored' },
      d: { action: 'RENAME', newName: 'x' },
      zzz: { action: 'SKIP', newName: '' },
    };
    expect(buildResolutions(blockers, choices)).toEqual([
      { subject: 'a', action: 'RENAME', newName: 'Customer 2' },
      { subject: 'c', action: 'SKIP' },
    ]);
  });

  it('merges AI suggestions over the user choices and keeps untouched ones', () => {
    const current: ResolutionChoices = { a: { action: 'SKIP', newName: '' }, keep: { action: 'SKIP', newName: '' } };
    const merged = mergeSuggestions(current, [
      { subject: 'a', action: 'RENAME', newName: 'A (2)' },
      { subject: 'b', action: 'SKIP' },
    ]);
    expect(merged).toEqual({
      a: { action: 'RENAME', newName: 'A (2)' },
      b: { action: 'SKIP', newName: '' },
      keep: { action: 'SKIP', newName: '' },
    });
  });

  it('enables import only with a mode, not busy and no blockers left', () => {
    const clean = { issues: [issue({ severity: 'WARNING', options: [] })] };
    expect(canConfirmImport(clean, 'AGREGAR', false)).toBe(true);
    expect(canConfirmImport(clean, null, false)).toBe(false);
    expect(canConfirmImport(clean, 'AGREGAR', true)).toBe(false);
    expect(canConfirmImport({ issues: [issue()] }, 'AGREGAR', false)).toBe(false);
    expect(canConfirmImport({}, 'REEMPLAZAR', false)).toBe(true);
  });
});
