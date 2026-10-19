import type { ModalSize } from '../ui/Modal';

export type EditorSectionId = 'xmi' | 'collaboration' | 'members' | 'review' | 'generation';

export type EditorSection = {
  id: EditorSectionId;
  /** Short text shown on the option-bar button. */
  label: string;
  /** Accessible name of the dialog the button opens. */
  title: string;
  /** Dialog width, consumed by the shared Modal primitive. */
  size: ModalSize;
};

/**
 * Frozen registry of the editor sections collapsed into the compact view.
 * The order here is the order the option bar renders, and it is part of the
 * plan contract: xmi, collaboration, members, review, generation.
 */
export const EDITOR_SECTIONS: readonly EditorSection[] = [
  { id: 'xmi', label: 'XMI interchange', title: 'Import an XMI file', size: 'md' },
  { id: 'collaboration', label: 'Collaboration', title: 'Live collaboration', size: 'lg' },
  { id: 'members', label: 'Members', title: 'Members', size: 'lg' },
  { id: 'review', label: 'Review', title: 'Review diagram', size: 'md' },
  { id: 'generation', label: 'Generation', title: 'Generate a backend', size: 'md' },
];

/** Returns the matching section, or throws naming the unknown id. */
export function findEditorSection(id: EditorSectionId): EditorSection {
  const section = EDITOR_SECTIONS.find((candidate) => candidate.id === id);
  if (section === undefined) {
    throw new Error(`Unknown editor section: ${id}`);
  }
  return section;
}

/**
 * Pressing a section button toggles: if the requested section is already the
 * open one, close it; otherwise open the requested one. Sections never stack.
 */
export function nextActiveSection(
  current: EditorSectionId | null,
  requested: EditorSectionId,
): EditorSectionId | null {
  return current === requested ? null : requested;
}
