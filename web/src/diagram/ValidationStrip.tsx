import { useEffect, useState, type ReactElement } from 'react';
import type { ModelResource, ValidationDiagnosticResource } from '../api/types';
import { summarizeValidation } from './liveValidation';

export interface ValidationStripProps {
  model: ModelResource;
  /** Reads the server report; called again after every model revision. */
  loadDiagnostics: () => Promise<ValidationDiagnosticResource[]>;
}

type LoadState = { kind: 'loading' } | { kind: 'ready'; diagnostics: ValidationDiagnosticResource[] } | { kind: 'failed' };

/**
 * Live validation above the canvas: error and warning counts always visible, and the details
 * folded by default in a list of capped height, so many findings never push the diagram away.
 */
export default function ValidationStrip({ model, loadDiagnostics }: ValidationStripProps): ReactElement {
  const [state, setState] = useState<LoadState>({ kind: 'loading' });
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let active = true;
    // A short pause lets a burst of edits (a paste replays many commands) settle into one request.
    const timer = window.setTimeout(() => {
      loadDiagnostics()
        .then((diagnostics) => { if (active) setState({ kind: 'ready', diagnostics }); })
        .catch(() => { if (active) setState({ kind: 'failed' }); });
    }, 400);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [model.revision, loadDiagnostics]);

  const summary = summarizeValidation(model, state.kind === 'ready' ? state.diagnostics : []);
  const findings = [
    ...summary.errors.map((finding) => ({ ...finding, severity: 'error' as const })),
    ...summary.warnings.map((finding) => ({ ...finding, severity: 'warning' as const })),
  ];

  return (
    <section aria-labelledby="validation-title" className="validation-strip">
      <div className="validation-head">
        <div className="validation-title">
          <span aria-hidden="true" className="validation-mark">
            <svg fill="none" stroke="currentColor" strokeWidth="1.8" viewBox="0 0 24 24">
              <circle cx="10" cy="10" r="6" />
              <path d="m15 15 5 5M7 10h6M10 7v6" />
            </svg>
          </span>
          <div>
            <strong id="validation-title">Validación en vivo</strong>
            <small>
              {state.kind === 'failed'
                ? 'No se pudo consultar el validador; se reintenta con el próximo cambio.'
                : state.kind === 'loading' ? 'Revisando el modelo…' : 'Se actualiza con cada cambio del modelo.'}
            </small>
          </div>
        </div>
        <div className="validation-stats">
          <span aria-live="polite" className="validation-count validation-error" data-empty={state.kind !== 'ready' || summary.errors.length === 0}>
            Errores <strong>{state.kind === 'ready' ? summary.errors.length : '—'}</strong>
          </span>
          <span aria-live="polite" className="validation-count validation-warning" data-empty={state.kind !== 'ready' || summary.warnings.length === 0}>
            Advertencias <strong>{state.kind === 'ready' ? summary.warnings.length : '—'}</strong>
          </span>
          <button
            aria-controls="validation-details"
            aria-expanded={open}
            className="button button-secondary button-compact"
            disabled={findings.length === 0}
            onClick={() => setOpen((value) => !value)}
            type="button"
          >
            {open ? 'Ocultar detalles' : 'Ver detalles'}
          </button>
        </div>
      </div>
      {open && findings.length > 0 && (
        <ul aria-label="Errores y advertencias del modelo" className="validation-list" id="validation-details" tabIndex={0}>
          {findings.map((finding) => (
            <li className={`validation-item validation-item--${finding.severity}`} key={`${finding.severity}:${finding.key}`}>
              <span className="validation-item-severity">{finding.severity === 'error' ? 'Error' : 'Advertencia'}</span>
              {finding.text}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
