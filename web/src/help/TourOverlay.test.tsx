import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import TourOverlay from './TourOverlay';
import { getTourStep, TOUR_STEPS } from './tourSteps';

describe('TourOverlay', () => {
  it('renders the current step and navigation controls as static markup', () => {
    const markup = renderToStaticMarkup(
      <TourOverlay
        stepIndex={0}
        onPrevious={vi.fn()}
        onNext={vi.fn()}
        onFinish={vi.fn()}
        onReopenHelp={vi.fn()}
      />,
    );

    expect(markup).toContain(getTourStep(0).title);
    expect(markup).toContain(getTourStep(0).explanation);
    expect(markup).toContain('Anterior');
    expect(markup).toContain('Siguiente');
    expect(markup).toContain('Finalizar recorrido');
    expect(markup).toContain('disabled=""');
  });

  it('offers a way to reopen the tour on the final step without exposing technical identifiers', () => {
    const markup = renderToStaticMarkup(
      <TourOverlay
        stepIndex={TOUR_STEPS.length - 1}
        onPrevious={vi.fn()}
        onNext={vi.fn()}
        onFinish={vi.fn()}
        onReopenHelp={vi.fn()}
      />,
    );

    expect(markup).toContain('Reiniciar recorrido');
    expect(markup).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    expect(markup).not.toMatch(/data-(?:project|section|step)/i);
  });
});
