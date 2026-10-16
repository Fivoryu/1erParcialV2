import type { CSSProperties, FormEvent, ReactElement } from 'react';

import type { EditorSectionId } from '../pages/editorSections';
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
  background: 'var(--surface)',
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
  background: 'var(--surface)',
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
        aria-label={isOpen ? 'Close help guide' : 'Open help guide'}
        className="help-guide-trigger"
        onClick={onToggle}
        style={triggerStyle}
        type="button"
      >
        Help
      </button>
      {isOpen && (
        <aside aria-label="App help guide" aria-modal="false" className="help-guide-panel" role="dialog" style={panelStyle}>
          <header className="section-heading">
            <div>
              <p className="eyebrow">App help</p>
              <h2>Ask about this editor</h2>
            </div>
            <button className="button button-secondary button-compact" onClick={onClose} type="button">
              Close
            </button>
          </header>
          <p className="quiet-state">
            Answers are limited to the app’s documented help topics and do not use diagram chat history or memory.
          </p>

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
                <section aria-label="Suggested navigation" className="help-guide-suggestions">
                  <h3>Suggested navigation</h3>
                  <ul>
                    {navigationSuggestions.map((suggestion) => (
                      <li key={suggestion.section}>
                        <button
                          aria-label={`${suggestion.label} section`}
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
              Question
              <textarea
                autoComplete="off"
                maxLength={1000}
                onChange={(event) => onDraftChange(event.target.value)}
                placeholder="How do I export my diagram?"
                value={draft}
              />
            </label>
            <button className="button button-primary" disabled={state.kind === 'loading' || !draft.trim()} type="submit">
              {state.kind === 'loading' ? 'Preparing…' : 'Ask guide'}
            </button>
          </form>
        </aside>
      )}
    </>
  );
}
