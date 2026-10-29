// @ts-expect-error Node built-ins are available in Vitest, but this browser project omits Node type declarations.
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import TourOverlay, { findTarget } from './TourOverlay';
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
        role="OWNER"
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

  it('avoids suggesting edits while the current role is not yet known', () => {
    const markup = renderToStaticMarkup(
      <TourOverlay
        stepIndex={0}
        role={null}
        onPrevious={vi.fn()}
        onNext={vi.fn()}
        onFinish={vi.fn()}
        onReopenHelp={vi.fn()}
      />,
    );

    expect(markup).toContain('confirmar tu permiso');
    expect(markup).not.toContain('Hacé clic en «Clase»');
    expect(markup).toContain('Siguiente');
  });

  it('leaves the floating help trigger accessible on narrow screens', () => {
    const tourCss = readFileSync(new URL('./tour.css', import.meta.url), 'utf8');
    expect(tourCss).toMatch(/@media\s*\(max-width:\s*600px\)[\s\S]*?bottom:\s*96px/);
    expect(tourCss).toMatch(/\.editor-tour-overlay--top\s*\{[^}]*top:\s*12px/s);
    expect(tourCss).toMatch(/\.editor-tour-overlay--modal\s*\{[^}]*position:\s*relative/s);
  });

  it('explains read-only access instead of asking a viewer to create a class', () => {
    const markup = renderToStaticMarkup(
      <TourOverlay
        stepIndex={0}
        role="VIEWER"
        onPrevious={vi.fn()}
        onNext={vi.fn()}
        onFinish={vi.fn()}
        onReopenHelp={vi.fn()}
      />,
    );

    expect(markup).toContain('Tu acceso es de lectura');
    expect(markup).not.toContain('Hacé clic en «Clase»');
    expect(markup).toContain('Siguiente');
  });

  it('finds the actual validation strip instead of treating it as missing', () => {
    const strip = {} as HTMLElement;
    const querySelector = vi.fn((selector: string) => selector === '.validation-strip' ? strip : null);
    vi.stubGlobal('document', { querySelector });
    try {
      const validation = TOUR_STEPS[0]!.visualSteps.find((step) => step.target === 'validation')!;
      expect(findTarget(validation, null, undefined, null)).toBe(strip);
      expect(querySelector).toHaveBeenCalledWith('.validation-strip');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('pauses the visual overlay while the help panel is open', () => {
    const markup = renderToStaticMarkup(
      <TourOverlay
        stepIndex={0}
        paused
        onPrevious={vi.fn()}
        onNext={vi.fn()}
        onFinish={vi.fn()}
        onReopenHelp={vi.fn()}
      />,
    );

    expect(markup).not.toContain('Recorrido del editor');
    expect(markup).not.toContain('editor-tour-highlight');
  });

  it('keeps a selected topic separate from the preceding topic', () => {
    const markup = renderToStaticMarkup(
      <TourOverlay
        stepIndex={1}
        topicOnly
        onPrevious={vi.fn()}
        onNext={vi.fn()}
        onFinish={vi.fn()}
        onReopenHelp={vi.fn()}
      />,
    );

    expect(markup).toContain('Menú contextual y multiplicidad');
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>Anterior<\/button>/);
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
