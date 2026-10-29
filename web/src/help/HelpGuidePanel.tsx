import type { CSSProperties, FormEvent, ReactElement } from 'react';
import './helpGuide.css';

import type { EditorSectionId } from '../pages/editorSections';
import { HELP_TOPICS } from './helpCorpus';
import { getHelpGuideStatusText, type HelpGuidePanelState } from './helpGuide';
import { getHelpNavigationSuggestions } from './helpNavigation';

export type HelpGuidePanelProps = {
  isOpen: boolean;
  draft: string;
  state: HelpGuidePanelState;
  openSection: EditorSectionId | null;
  onToggle: () => void;
  onClose: () => void;
  onDraftChange: (draft: string) => void;
  onSubmit: (question: string) => void;
  onNavigateToSection: (section: EditorSectionId) => void;
  onStartTour: (slug: string) => void;
};

const triggerStyle: CSSProperties = {
  position: 'fixed',
  zIndex: 30,
  right: '96px',
  bottom: '24px',
  width: '56px',
  height: '56px',
  borderRadius: '50%',
  border: '1px solid var(--line)',
  background: 'var(--paper)',
  color: 'var(--ink)',
  boxShadow: 'var(--shadow-soft)',
  fontWeight: 700,
  cursor: 'pointer',
};

const panelStyle: CSSProperties = {
  position: 'fixed',
  zIndex: 35,
  right: '24px',
  bottom: '96px',
  width: 'min(28rem, calc(100vw - 32px))',
  maxHeight: 'min(70vh, 42rem)',
  overflowY: 'auto',
  padding: '20px',
  background: 'var(--paper)',
  border: '1px solid var(--line)',
  borderRadius: 'var(--radius)',
  boxShadow: '0 16px 48px rgba(15, 23, 42, 0.24)',
};

export default function HelpGuidePanel({
  isOpen,
  draft,
  state,
  openSection,
  onToggle,
  onClose,
  onDraftChange,
  onSubmit,
  onNavigateToSection,
  onStartTour,
}: HelpGuidePanelProps): ReactElement {
  const statusText = getHelpGuideStatusText(state);
  const navigationSuggestions = state.kind === 'answered'
    ? getHelpNavigationSuggestions(state.response.suggestedSlugs, openSection)
    : [];

  function submit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    const question = draft.trim();
    if (question.length > 0 && state.kind !== 'loading') {
      onSubmit(question);
    }
  }

  return (
    <>
      <button
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        aria-label={isOpen ? 'Cerrar la guía de ayuda' : 'Abrir la guía de ayuda'}
        className="help-guide-trigger"
        onClick={onToggle}
        style={triggerStyle}
        type="button"
      >
        Ayuda
      </button>
      {isOpen && (
        <aside aria-label="Guía de ayuda de la aplicación" aria-modal="false" className="help-guide-panel" role="dialog" style={panelStyle}>
          <header className="section-heading">
            <div>
              <p className="eyebrow">Ayuda de la aplicación</p>
              <h2>Pregunta sobre este editor</h2>
            </div>
            <button className="button button-secondary button-compact" onClick={onClose} type="button">
              Cerrar
            </button>
          </header>
          <p className="quiet-state">
            Las respuestas se limitan a los temas de ayuda documentados de la aplicación y no usan el historial del chat ni los recuerdos del diagrama.
          </p>

          <details className="help-guide-tours">
            <summary>Recorridos visuales</summary>
            <p className="quiet-state">Elegí un tema para ver dónde están sus controles, sin necesitar IA.</p>
            <ul>
              {HELP_TOPICS.map((topic) => (
                <li key={topic.slug}>
                  <button className="text-button" onClick={() => onStartTour(topic.slug)} type="button">
                    {topic.title}
                  </button>
                </li>
              ))}
            </ul>
          </details>

          {statusText !== null && (
            <p className={state.kind === 'error' ? 'notice notice-error' : 'quiet-state'}
              role={state.kind === 'error' ? 'alert' : 'status'}>
              {statusText}
            </p>
          )}
          {state.kind === 'answered' && (
            <>
              <p className="help-guide-answer">{state.response.answer}</p>
              {navigationSuggestions.length > 0 && (
                <section aria-label="Navegación sugerida" className="help-guide-suggestions">
                  <h3>Navegación sugerida</h3>
                  <ul>
                    {navigationSuggestions.map((suggestion) => (
                      <li key={suggestion.section}>
                        <button
                          aria-label={`Sección: ${suggestion.label}`}
                          className="button button-secondary button-compact"
                          onClick={() => onNavigateToSection(suggestion.section)}
                          type="button"
                        >
                          {suggestion.label}
                        </button>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
            </>
          )}

          <form className="help-guide-compose" onSubmit={submit}>
            <label className="sr-only">
              Pregunta
              <textarea
                autoComplete="off"
                maxLength={1000}
                onChange={(event) => onDraftChange(event.target.value)}
                placeholder="¿Cómo exporto mi diagrama?"
                value={draft}
              />
            </label>
            <button className="button button-primary" disabled={state.kind === 'loading' || !draft.trim()} type="submit">
              {state.kind === 'loading' ? 'Preparando…' : 'Preguntar a la guía'}
            </button>
          </form>
        </aside>
      )}
    </>
  );
}
