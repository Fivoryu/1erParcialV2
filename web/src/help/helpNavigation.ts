import { EDITOR_SECTIONS, type EditorSectionId } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';

export type HelpNavigationSuggestion = {
  section: EditorSectionId;
  label: string;
};

export function getHelpSectionForSlug(slug: string): EditorSectionId | null {
  const topic = HELP_TOPICS.find((candidate) => candidate.slug === slug);
  if (topic?.section === undefined) {
    return null;
  }

  return EDITOR_SECTIONS.find((section) => section.id === topic.section)?.id ?? null;
}

export function getHelpNavigationSuggestions(
  suggestedSlugs: readonly string[],
  openSection: EditorSectionId | null,
): HelpNavigationSuggestion[] {
  const seenSections = new Set<EditorSectionId>();
  const suggestions: HelpNavigationSuggestion[] = [];

  for (const slug of suggestedSlugs) {
    const sectionId = getHelpSectionForSlug(slug);
    if (sectionId === null || sectionId === openSection || seenSections.has(sectionId)) {
      continue;
    }

    const section = EDITOR_SECTIONS.find((candidate) => candidate.id === sectionId);
    if (section !== undefined) {
      seenSections.add(sectionId);
      suggestions.push({ section: section.id, label: `Abrir ${section.label}` });
    }
  }

  return suggestions;
}
