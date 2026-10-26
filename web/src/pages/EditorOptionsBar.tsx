import { createElement, type ReactElement } from 'react';
import { EDITOR_SECTIONS, type EditorSectionId } from './editorSections';

export type EditorOptionsBarProps = {
  activeSection: EditorSectionId | null;
  onOpenSection: (id: EditorSectionId) => void;
  badges?: Partial<Record<EditorSectionId, number>>;
};

/**
 * Presentational option bar for the compact editor view: one pill button per
 * frozen section, in registry order. The bar owns no toggle semantics — it
 * only reports which button was pressed; the parent decides the next state
 * (via nextActiveSection) and reflects it back through `activeSection`.
 */
export default function EditorOptionsBar({
  activeSection,
  onOpenSection,
  badges,
}: EditorOptionsBarProps): ReactElement {
  return createElement(
    'div',
    { className: 'editor-options-bar', role: 'toolbar', 'aria-label': 'Opciones del editor' },
    EDITOR_SECTIONS.map((section) =>
      createElement(
        'button',
        {
          key: section.id,
          type: 'button',
          className: 'options-bar-button',
          'data-section': section.id,
          'aria-haspopup': 'dialog',
          'aria-expanded': section.id === activeSection ? 'true' : 'false',
          onClick: () => onOpenSection(section.id),
        },
        createElement('span', { className: 'options-bar-label' }, section.label),
        typeof badges?.[section.id] === 'number'
          ? createElement('span', { className: 'options-bar-badge' }, badges[section.id])
          : null,
      ),
    ),
  );
}
