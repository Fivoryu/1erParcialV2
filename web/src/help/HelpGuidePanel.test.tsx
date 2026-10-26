import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { HelpGuideResponse } from '../api/types';
import HelpGuidePanel from './HelpGuidePanel';
import type { HelpGuidePanelState } from './helpGuide';

const answer: HelpGuideResponse = {
  schemaVersion: 1,
  answer: 'Open the generation section and start a run.',
  suggestedSlugs: ['generate-a-backend', 'export-and-import-xmi'],
};

function renderPanel(
  state: HelpGuidePanelState,
  isOpen = true,
  openSection: 'xmi' | 'collaboration' | 'members' | 'generation' | null = null,
): string {
  return renderToStaticMarkup(
    <HelpGuidePanel
      isOpen={isOpen}
      draft="How do I generate a backend?"
      state={state}
      openSection={openSection}
      onToggle={vi.fn()}
      onClose={vi.fn()}
      onDraftChange={vi.fn()}
      onSubmit={vi.fn()}
      onNavigateToSection={vi.fn()}
    />,
  );
}

describe('HelpGuidePanel', () => {
  it('renders its own floating help control and dialog without exposing topic slugs', () => {
    const markup = renderPanel({ kind: 'answered', response: answer });

    expect(markup).toContain('Cerrar la guía de ayuda');
    expect(markup).toContain('role="dialog"');
    expect(markup).toContain('Open the generation section and start a run.');
    expect(markup).toContain('Navegación sugerida');
    expect(markup).toContain('aria-label="Sección: Abrir Generación"');
    expect(markup).toContain('>Abrir Generación</button>');
    expect(markup).toContain('aria-label="Sección: Abrir Intercambio XMI"');
    expect(markup).toContain('>Abrir Intercambio XMI</button>');
    expect(markup).not.toContain('generate-a-backend');
    expect(markup).not.toContain('export-and-import-xmi');
    expect(markup).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
  });

  it('does not render navigation buttons when suggestions have no editor section', () => {
    const markup = renderPanel({
      kind: 'answered',
      response: { ...answer, suggestedSlugs: ['diagram-chat'] },
    });

    expect(markup).not.toContain('Navegación sugerida');
    expect(markup).not.toContain('aria-label="Sección: Abrir ');
    expect(markup).not.toContain('diagram-chat');
  });

  it('omits a navigation button for the section that is already open', () => {
    const markup = renderPanel({ kind: 'answered', response: answer }, true, 'generation');

    expect(markup).not.toContain('Abrir Generación');
    expect(markup).toContain('Abrir Intercambio XMI');
  });

  it('renders loading, failure, and unsupported states as distinct user-facing messages', () => {
    expect(renderPanel({ kind: 'loading' })).toContain('Preparando una respuesta');
    expect(renderPanel({ kind: 'error', message: 'The server could not be reached.' }))
      .toContain('The server could not be reached.');
    expect(renderPanel({ kind: 'unsupported', message: 'Local AI is not available here.' }))
      .toContain('Local AI is not available here.');
  });

  it('keeps the panel closed until the separate help control is opened', () => {
    const markup = renderPanel({ kind: 'idle' }, false);

    expect(markup).toContain('Abrir la guía de ayuda');
    expect(markup).not.toContain('role="dialog"');
  });
});
