import { describe, expect, it } from 'vitest';
import { EDITOR_SECTIONS, findEditorSection, nextActiveSection } from './editorSections';

describe('editor sections registry', () => {
  it('lists exactly the six sections with versioning last', () => {
    expect(EDITOR_SECTIONS.map((section) => section.id)).toEqual([
      'xmi',
      'collaboration',
      'members',
      'review',
      'generation',
      'vcs',
    ]);
    expect(findEditorSection('vcs')).toMatchObject({
      label: 'Versionado',
      title: 'Historial, ramas y revisiones',
      size: 'lg',
    });
  });

  it('has unique ids and no empty label or title', () => {
    const ids = EDITOR_SECTIONS.map((section) => section.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const section of EDITOR_SECTIONS) {
      expect(section.label.length).toBeGreaterThan(0);
      expect(section.title.length).toBeGreaterThan(0);
    }
  });

  it('finds each section by id', () => {
    for (const section of EDITOR_SECTIONS) {
      expect(findEditorSection(section.id)).toBe(section);
    }
  });

  it('throws naming the unknown id', () => {
    expect(() => findEditorSection('nope' as never)).toThrow(/nope/);
  });
});

describe('nextActiveSection', () => {
  it('opens a section from nothing', () => {
    expect(nextActiveSection(null, 'members')).toBe('members');
  });

  it('toggles the active section closed', () => {
    expect(nextActiveSection('members', 'members')).toBeNull();
  });

  it('switches sections instead of accumulating', () => {
    expect(nextActiveSection('members', 'generation')).toBe('generation');
    expect(nextActiveSection('generation', 'vcs')).toBe('vcs');
  });
});
