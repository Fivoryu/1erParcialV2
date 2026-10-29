import { useEffect, useLayoutEffect, useRef, useState, type ReactElement } from 'react';
import { createPortal } from 'react-dom';
import './tour.css';
import type { MemberRole } from '../api/types';
import type { CanvasSelection } from '../diagram/selection';
import type { EditorSectionId } from '../pages/editorSections';
import { placeTourAboveTarget, scrollOffsetBelowHeader } from './tourPlacement';
import {
  clampTourStepIndex,
  getTourStep,
  TOUR_STEPS,
  type TourTarget,
  type VisualTourStep,
} from './tourSteps';

export type TourOverlayProps = {
  stepIndex: number;
  selection?: CanvasSelection | null;
  attributeName?: string;
  openSection?: EditorSectionId | null;
  topicOnly?: boolean;
  paused?: boolean;
  role?: MemberRole | null;
  onPrevious: () => void;
  onNext: () => void;
  onFinish: () => void;
  onReopenHelp: () => void;
};

type Highlight = { top: number; left: number; width: number; height: number };

const EDIT_TARGETS = new Set<TourTarget>([
  'class-add', 'class-name', 'attribute-add', 'attribute-name', 'operation-add', 'relationship-select',
]);

const TARGET_SECTIONS: Partial<Record<TourTarget, EditorSectionId>> = {
  'xmi-export': 'xmi', 'xmi-file': 'xmi',
  presence: 'collaboration', 'chat-session': 'collaboration', 'chat-input': 'collaboration',
  'chat-suggest': 'collaboration',
  'members-list': 'members', 'members-invite': 'members', 'members-role': 'members',
  'review-run': 'review', 'review-results': 'review',
  'generation-new': 'generation', 'generation-target': 'generation',
  'generation-run': 'generation', 'generation-deployment': 'generation',
  'version-tabs': 'vcs',
};

const TARGET_SELECTORS: Partial<Record<TourTarget, string>> = {
  'class-add': '[data-guide-target="class-add"]',
  'attribute-add': '[data-guide-target="attribute-add"]',
  'operation-add': '[data-guide-target="operation-add"]',
  'relationship-select': '[aria-label="Elegir tipo de relación"]',
  validation: '.validation-strip',
  canvas: '.react-flow',
  'project-list': '.editor-breadcrumb a',
  'xmi-export': '[aria-labelledby="xmi-export-title"] .generation-actions button',
  'xmi-file': '[aria-labelledby="xmi-import-title"] input[type="file"]',
  presence: '.presence-panel',
  'chat-session': '#chat-session-selector',
  'chat-input': '#chat-message-draft',
  'chat-suggest': '.chat-suggest-change',
  'members-list': '.members-panel',
  'members-invite': '.member-invite-form input',
  'members-role': '.member-invite-form select',
  'review-run': '.review-run-button',
  'review-results': '.review-panel',
  'generation-new': '[aria-labelledby="generation-title"] .generation-heading button',
  'generation-target': '[aria-labelledby="generation-title"] .generation-target-list',
  'generation-run': '[aria-labelledby="generation-title"] .generation-actions > button:first-child',
  'generation-deployment': '.generation-deployment',
  'version-tabs': '.vcs-tab-list',
};

export function findTarget(
  step: VisualTourStep,
  selection: CanvasSelection | null | undefined,
  attributeName: string | undefined,
  openSection: EditorSectionId | null,
): HTMLElement | null {
  const requiredSection = TARGET_SECTIONS[step.target];
  if (requiredSection ? requiredSection !== openSection : openSection !== null) {
    return null;
  }
  if (step.target.startsWith('section-')) {
    const section = step.target.slice('section-'.length);
    return document.querySelector<HTMLElement>(`[data-section="${section}"]`);
  }
  const selector = TARGET_SELECTORS[step.target];
  if (selector) {
    return document.querySelector<HTMLElement>(selector);
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
  if (step.target !== 'attribute-name' || selection.kind !== 'attribute' || !attributeName) {
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
  openSection = null,
  topicOnly = false,
  paused = false,
  role = null,
  onPrevious,
  onNext,
  onFinish,
  onReopenHelp,
}: TourOverlayProps): ReactElement {
  const currentIndex = clampTourStepIndex(stepIndex);
  const step = getTourStep(currentIndex);
  const [cursor, setCursor] = useState({ topic: currentIndex, visual: 0 });
  const [modalHost, setModalHost] = useState<HTMLElement | null>(null);
  const visualIndex = cursor.topic === currentIndex ? cursor.visual : 0;
  const visualSteps = step.visualSteps;
  const visualStep = visualSteps[visualIndex];
  const editStep = visualStep !== undefined && EDIT_TARGETS.has(visualStep.target);
  const readOnlyStep = role === 'VIEWER' && editStep;
  const unconfirmedRoleStep = role === null && editStep;
  const hasMoreVisualSteps = visualIndex < visualSteps.length - 1;
  const isFinalStep = (topicOnly || currentIndex === TOUR_STEPS.length - 1) && !hasMoreVisualSteps;
  const targetRef = useRef<HTMLElement | null>(null);
  const [highlight, setHighlight] = useState<Highlight | null>(null);
  const [targetMissing, setTargetMissing] = useState(false);
  const [viewport, setViewport] = useState(() => ({
    width: typeof window === 'undefined' ? 0 : window.innerWidth,
    height: typeof window === 'undefined' ? 0 : window.innerHeight,
  }));

  // Keep the same tour state mounted while placing its controls inside the active modal's focus trap.
  useLayoutEffect(() => {
    setModalHost(openSection === null ? null : document.querySelector<HTMLElement>('.editor-modal[aria-modal="true"]'));
  }, [openSection]);

  useEffect(() => {
    if (!visualStep || paused) {
      targetRef.current = null;
      setHighlight(null);
      setTargetMissing(false);
      return undefined;
    }

    let frame: number | null = null;
    let observed: HTMLElement | null = null;
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    function measure(): void {
      setViewport((previous) => previous.width === window.innerWidth && previous.height === window.innerHeight
        ? previous : { width: window.innerWidth, height: window.innerHeight });
      const target = findTarget(visualStep!, selection, attributeName, openSection);
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
      const disabled = target instanceof HTMLButtonElement || target instanceof HTMLInputElement || target instanceof HTMLSelectElement
        ? target.disabled : false;
      setTargetMissing(next === null || disabled);
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
  }, [visualStep, selection, attributeName, openSection, paused]);

  useEffect(() => {
    if (paused || readOnlyStep || unconfirmedRoleStep || !visualStep || visualStep.advanceOn !== 'click' || !hasMoreVisualSteps) {
      return undefined;
    }
    function observeUserClick(event: MouseEvent): void {
      const target = findTarget(visualStep!, selection, attributeName, openSection);
      if (target && event.target instanceof Node && target.contains(event.target)) {
        setCursor({ topic: currentIndex, visual: visualIndex + 1 });
      }
    }
    document.addEventListener('click', observeUserClick, true);
    return () => document.removeEventListener('click', observeUserClick, true);
  }, [visualStep, selection, attributeName, openSection, paused, readOnlyStep, unconfirmedRoleStep, hasMoreVisualSteps, currentIndex, visualIndex]);

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

  function showTarget(): void {
    const target = targetRef.current;
    if (!target) return;
    target.scrollIntoView({ block: 'center', inline: 'nearest' });

    const header = document.querySelector<HTMLElement>('.editor-header');
    if (openSection !== null || !header || header.contains(target)) return;
    const offset = scrollOffsetBelowHeader(target.getBoundingClientRect().top, header.getBoundingClientRect().bottom);
    if (offset < 0) window.scrollBy({ top: offset, behavior: 'auto' });
  }

  const cardOnLeft = highlight !== null && highlight.left > viewport.width / 2;
  const cardOnTop = placeTourAboveTarget(highlight, viewport.height);
  const content = (
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
      <aside aria-label="Recorrido del editor" className={`editor-tour-overlay${cardOnLeft ? ' editor-tour-overlay--left' : ''}${cardOnTop ? ' editor-tour-overlay--top' : ''}${openSection !== null ? ' editor-tour-overlay--modal' : ''}`}>
        <section className="editor-tour-card">
          <p className="eyebrow">
            {topicOnly ? `Paso ${visualIndex + 1} de ${visualSteps.length}` : `Tema ${currentIndex + 1} de ${TOUR_STEPS.length}`}
          </p>
          <h2>{step.title}</h2>
          <p>{step.explanation}</p>
          {visualStep && (
            <div aria-live="polite" className="editor-tour-instruction">
              <p>{readOnlyStep
                ? 'Tu acceso es de lectura. Para modificar el diagrama necesitás permiso de edición; podés seguir explorando la guía.'
                : unconfirmedRoleStep
                  ? 'Todavía no pudimos confirmar tu permiso. Para modificar el diagrama necesitás permiso de edición; podés seguir explorando la guía.'
                  : visualStep.instruction}</p>
              {targetMissing && (
                <p className="quiet-state">
                  {openSection !== null && !TARGET_SECTIONS[visualStep.target]
                    ? 'Cerrá la ventana actual para volver al editor y continuar.' : visualStep.missingHint}
                </p>
              )}
              {highlight && (
                <button
                  className="text-button"
                  onClick={showTarget}
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
              disabled={(topicOnly || currentIndex === 0) && visualIndex === 0}
              onClick={previous}
              type="button"
            >
              Anterior
            </button>
            {isFinalStep ? (
              <button className="button button-secondary button-compact" onClick={() => {
                setCursor({ topic: currentIndex, visual: 0 });
                onReopenHelp();
              }} type="button">
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

  if (paused) {
    return <></>;
  }
  if (openSection !== null) {
    return modalHost?.isConnected ? createPortal(content, modalHost) : <></>;
  }
  return content;
}
