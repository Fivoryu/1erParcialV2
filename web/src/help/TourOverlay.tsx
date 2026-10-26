import type { CSSProperties, ReactElement } from 'react';
import {
  clampTourStepIndex,
  getTourStep,
  TOUR_STEPS,
  type TourStep,
} from './tourSteps';

export type TourOverlayProps = {
  stepIndex: number;
  onPrevious: () => void;
  onNext: () => void;
  onFinish: () => void;
  onReopenHelp: () => void;
};

const overlayStyle: CSSProperties = {
  position: 'fixed',
  zIndex: 50,
  right: '24px',
  bottom: '96px',
  width: 'min(28rem, calc(100vw - 48px))',
  maxHeight: 'calc(100vh - 120px)',
  overflowY: 'auto',
};

const cardStyle: CSSProperties = {
  background: 'var(--surface)',
  border: '1px solid var(--line)',
  borderRadius: 'var(--radius)',
  padding: '20px',
  boxShadow: '0 16px 48px rgba(15, 23, 42, 0.24)',
};

export default function TourOverlay({
  stepIndex,
  onPrevious,
  onNext,
  onFinish,
  onReopenHelp,
}: TourOverlayProps): ReactElement {
  const currentIndex = clampTourStepIndex(stepIndex);
  const step: TourStep = getTourStep(currentIndex);
  const isFinalStep = currentIndex === TOUR_STEPS.length - 1;

  return (
    <aside aria-label="Recorrido del editor" className="editor-tour-overlay" style={overlayStyle}>
      <section className="editor-tour-card" style={cardStyle}>
        <p className="eyebrow">Paso {currentIndex + 1} de {TOUR_STEPS.length}</p>
        <h2>{step.title}</h2>
        <p>{step.explanation}</p>
        <div className="diagram-toolbar-actions">
          <button
            className="button button-secondary button-compact"
            disabled={currentIndex === 0}
            onClick={onPrevious}
            type="button"
          >
            Anterior
          </button>
          {isFinalStep ? (
            <button className="button button-secondary button-compact" onClick={onReopenHelp} type="button">
              Reiniciar recorrido
            </button>
          ) : (
            <button className="button button-secondary button-compact" onClick={onNext} type="button">
              Siguiente
            </button>
          )}
          <button className="button button-primary button-compact" onClick={onFinish} type="button">
            Finalizar recorrido
          </button>
        </div>
      </section>
    </aside>
  );
}
