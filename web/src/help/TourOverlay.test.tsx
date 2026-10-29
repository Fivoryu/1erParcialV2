// @ts-expect-error Node built-ins are available in Vitest, but this browser project omits Node type declarations.
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import TourOverlay from './TourOverlay';
import { getTourStep, TOUR_STEPS } from './tourSteps';

describe('TourOverlay', () => {
  it('keeps instructions legible over the editor with an opaque theme surface', () => {
    const themeCss = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');
    const tourCss = readFileSync(new URL('./tour.css', import.meta.url), 'utf8');
    expect(themeCss).toMatch(/--paper:\s*#[0-9a-f]{6};/i);
    expect(tourCss).toMatch(/\.editor-tour-card\s*\{[^}]*background:\s*var\(--paper\)/s);
  });

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

  it('explains the first real action accessibly while leaving navigation available', () => {
    const markup = renderToStaticMarkup(
      <TourOverlay
        stepIndex={0}
        onPrevious={vi.fn()}
        onNext={vi.fn()}
        onFinish={vi.fn()}
        onReopenHelp={vi.fn()}
      />,
    );

    expect(markup).toContain('Hacé clic en «Clase»');
    expect(markup).toContain('aria-live="polite"');
    expect(markup).toContain('Siguiente');
    expect(markup).toContain('Finalizar recorrido');
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
