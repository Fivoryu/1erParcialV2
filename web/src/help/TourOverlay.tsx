import { useEffect, useRef, useState, type ReactElement } from 'react';
import './tour.css';
import type { CanvasSelection } from '../diagram/selection';
import {
  clampTourStepIndex,
  getTourStep,
  TOUR_STEPS,
  type VisualTourStep,
} from './tourSteps';

export type TourOverlayProps = {
  stepIndex: number;
  selection?: CanvasSelection | null;
  attributeName?: string;
  onPrevious: () => void;
  onNext: () => void;
  onFinish: () => void;
  onReopenHelp: () => void;
};

type Highlight = { top: number; left: number; width: number; height: number };

function findTarget(
  step: VisualTourStep,
  selection: CanvasSelection | null | undefined,
  attributeName: string | undefined,
): HTMLElement | null {
  if (step.target === 'class-add' || step.target === 'attribute-add') {
    return document.querySelector<HTMLElement>(`[data-guide-target="${step.target}"]`);
  }
  if (!selection || selection.kind === 'relationship') {
    return null;
  }
  // React Flow already exposes each node's id. Compare it as data, not as a CSS selector.
  const node = Array.from(document.querySelectorAll<HTMLElement>('.react-flow__node'))
    .find((element) => element.dataset.id === selection.classifierId);
  if (!node) {
    return null;
  }
  if (step.target === 'class-name') {
    return node.querySelector<HTMLElement>('[aria-label="Editar nombre de la clase"]');
  }
  if (selection.kind !== 'attribute' || !attributeName) {
    return null;
  }
  return Array.from(node.querySelectorAll<HTMLElement>('[aria-label]'))
    .find((element) => element.getAttribute('aria-label') === `Editar atributo ${attributeName}`) ?? null;
}

function equalHighlight(a: Highlight | null, b: Highlight | null): boolean {
  return a === b || Boolean(a && b && a.top === b.top && a.left === b.left
    && a.width === b.width && a.height === b.height);
}

export default function TourOverlay({
  stepIndex,
  selection,
  attributeName,
  onPrevious,
  onNext,
  onFinish,
  onReopenHelp,
}: TourOverlayProps): ReactElement {
  const currentIndex = clampTourStepIndex(stepIndex);
  const step = getTourStep(currentIndex);
  const [cursor, setCursor] = useState({ topic: currentIndex, visual: 0 });
  const visualIndex = cursor.topic === currentIndex ? cursor.visual : 0;
  const visualSteps = step.visualSteps ?? [];
  const visualStep = visualSteps[visualIndex];
  const hasMoreVisualSteps = visualIndex < visualSteps.length - 1;
  const isFinalStep = currentIndex === TOUR_STEPS.length - 1 && !hasMoreVisualSteps;
  const targetRef = useRef<HTMLElement | null>(null);
  const [highlight, setHighlight] = useState<Highlight | null>(null);
  const [targetMissing, setTargetMissing] = useState(false);

  useEffect(() => {
    if (!visualStep) {
      targetRef.current = null;
      setHighlight(null);
      setTargetMissing(false);
      return undefined;
    }

    let frame: number | null = null;
    let observed: HTMLElement | null = null;
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    function measure(): void {
      const target = findTarget(visualStep!, selection, attributeName);
      targetRef.current = target;
      if (target !== observed) {
        if (observed) resize?.unobserve(observed);
        if (target) resize?.observe(target);
        observed = target;
      }
      const rect = target?.getBoundingClientRect();
      const next = rect && rect.width > 0 && rect.height > 0
        ? { top: rect.top, left: rect.left, width: rect.width, height: rect.height }
        : null;
      setTargetMissing(next === null);
      setHighlight((previous) => equalHighlight(previous, next) ? previous : next);
    }
    function schedule(): void {
      if (frame === null) {
        frame = window.requestAnimationFrame(() => {
          frame = null;
          measure();
        });
      }
    }

    measure();
    const mutation = new MutationObserver(schedule);
    mutation.observe(document.body, { childList: true, subtree: true, attributes: true });
    window.addEventListener('resize', schedule);
    document.addEventListener('scroll', schedule, true);
    return () => {
      mutation.disconnect();
      resize?.disconnect();
      window.removeEventListener('resize', schedule);
      document.removeEventListener('scroll', schedule, true);
      if (frame !== null) window.cancelAnimationFrame(frame);
    };
  }, [visualStep, selection, attributeName]);

  useEffect(() => {
    if (!visualStep || visualStep.advanceOn !== 'click' || !hasMoreVisualSteps) {
      return undefined;
    }
    function observeUserClick(event: MouseEvent): void {
      const target = findTarget(visualStep!, selection, attributeName);
      if (target && event.target instanceof Node && target.contains(event.target)) {
        setCursor({ topic: currentIndex, visual: visualIndex + 1 });
      }
    }
    document.addEventListener('click', observeUserClick, true);
    return () => document.removeEventListener('click', observeUserClick, true);
  }, [visualStep, selection, attributeName, hasMoreVisualSteps, currentIndex, visualIndex]);

  function previous(): void {
    if (visualIndex > 0) {
      setCursor({ topic: currentIndex, visual: visualIndex - 1 });
    } else {
      onPrevious();
    }
  }

  function next(): void {
    if (hasMoreVisualSteps) {
      setCursor({ topic: currentIndex, visual: visualIndex + 1 });
    } else {
      onNext();
    }
  }

  const cardOnLeft = highlight !== null && typeof window !== 'undefined'
    && highlight.left > window.innerWidth / 2;
  return (
    <>
      {visualStep && highlight && (
        <div
          aria-hidden="true"
          className="editor-tour-highlight"
          style={{
            top: highlight.top - 6,
            left: highlight.left - 6,
            width: highlight.width + 12,
            height: highlight.height + 12,
          }}
        />
      )}
      <aside aria-label="Recorrido del editor" className={`editor-tour-overlay${cardOnLeft ? ' editor-tour-overlay--left' : ''}`}>
        <section className="editor-tour-card">
          <p className="eyebrow">Paso {currentIndex + 1} de {TOUR_STEPS.length}</p>
          <h2>{step.title}</h2>
          <p>{step.explanation}</p>
          {visualStep && (
            <div aria-live="polite" className="editor-tour-instruction">
              <p>{visualStep.instruction}</p>
              {targetMissing && <p className="quiet-state">{visualStep.missingHint}</p>}
              {highlight && (
                <button
                  className="text-button"
                  onClick={() => targetRef.current?.scrollIntoView({ block: 'center', inline: 'nearest' })}
                  type="button"
                >
                  Mostrar dónde está
                </button>
              )}
            </div>
          )}
          <div className="diagram-toolbar-actions">
            <button
              className="button button-secondary button-compact"
              disabled={currentIndex === 0 && visualIndex === 0}
              onClick={previous}
              type="button"
            >
              Anterior
            </button>
            {isFinalStep ? (
              <button className="button button-secondary button-compact" onClick={onReopenHelp} type="button">
                Reiniciar recorrido
              </button>
            ) : (
              <button className="button button-secondary button-compact" onClick={next} type="button">
                Siguiente
              </button>
            )}
            <button className="button button-primary button-compact" onClick={onFinish} type="button">
              Finalizar recorrido
            </button>
          </div>
        </section>
      </aside>
    </>
  );
}
