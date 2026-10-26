import { useCallback, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { ApiTransportError, createApiClient } from '../api/client';
import type { XmiExportResource, XmiLossResource, Uuid } from '../api/types';
import { decodeBase64ToBytes, exportFileName } from './xmiExport';

const api = createApiClient();

export interface XmiExportPanelProps {
  projectId: Uuid;
  projectName: string;
}

/**
 * Triggers the browser download of the exported document. The Base64 never
 * touches innerHTML, a data: href, or any attribute: it is decoded to bytes,
 * wrapped in a Blob, and offered through a temporary object-URL anchor that is
 * revoked right after the click. This mirrors the proven path in
 * GenerationPanel's download action.
 */
function downloadExportedFile(fileBase64: string, projectName: string): void {
  const bytes = decodeBase64ToBytes(fileBase64);
  const blob = new Blob([bytes], { type: 'application/xml' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = exportFileName(projectName);
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

// Duplicated minimal losses listing: LossReport is local to XmiImportPanel and
// is intentionally not refactored (declared debt, plan §3 risk 1).
function LossReport({ losses }: { losses: XmiLossResource[] }) {
  return (
    <div className="generation-section">
      <h3>Pérdidas detectadas</h3>
      {losses.length === 0 ? (
        <p className="property-hint">No se detectaron pérdidas.</p>
      ) : (
        <ul className="generation-diagnostics">
          {losses.map((loss, index) => (
            <li key={`${loss.name}-${index}`}>
              <strong>{loss.name}</strong>
              <span>Elementos afectados: {loss.count}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export interface XmiExportPanelViewProps {
  busy: boolean;
  busyMessage: string | null;
  error: string | null;
  result: XmiExportResource | null;
  onExport(): void;
  onDismissError(): void;
}

export function XmiExportPanelView({
  busy,
  busyMessage,
  error,
  result,
  onExport,
  onDismissError,
}: XmiExportPanelViewProps) {
  return (
    <section className="generation-panel" aria-labelledby="xmi-export-title">
      <div className="generation-heading">
        <div>
          <p className="eyebrow">Intercambio XMI</p>
          <h2 id="xmi-export-title">Exportar el modelo como XMI</h2>
        </div>
      </div>

      <p className="property-hint">
        Solo descarga: exportar este archivo no modifica el proyecto. El modelo solo cambia desde el editor o al importar.
      </p>

      {error && (
        <div className="notice notice-error" role="alert">
          <span>{error}</span>
          <button className="text-button" type="button" onClick={onDismissError}>Descartar</button>
        </div>
      )}

      {busy && <p className="generation-notice" role="status">{busyMessage ?? 'Procesando…'}</p>}

      <div className="generation-section">
        <h3>Descargar el archivo</h3>
        <div className="generation-actions">
          <button
            className="button button-primary"
            type="button"
            onClick={onExport}
            disabled={busy}
          >
            {busy && busyMessage?.startsWith('Exportando') ? 'Exportando…' : 'Exportar XMI'}
          </button>
        </div>
      </div>

      {result && (
        <div className="generation-run" aria-live="polite">
          <p className="generation-run-state">Se descargó el archivo XMI.</p>
          <div className="generation-section">
            <h3>Suma de verificación</h3>
            <dl>
              <div>
                <dt>SHA-256</dt>
                <dd>{result.checksum}</dd>
              </div>
            </dl>
          </div>
          <LossReport losses={result.losses} />
        </div>
      )}
    </section>
  );
}

function messageFor(error: unknown): string {
  if (error instanceof ApiTransportError) {
    // Includes the server's own message, so an editing-lease conflict or a
    // missing project surfaces through the established error mapping without
    // duplicating the server-side rule on the client.
    return error.message;
  }
  return 'No se pudo completar la exportación XMI. Inténtalo de nuevo en un momento.';
}

export default function XmiExportPanel({ projectId, projectName }: XmiExportPanelProps): ReactElement {
  const [busy, setBusy] = useState(false);
  const [busyMessage, setBusyMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<XmiExportResource | null>(null);
  const busyRef = useRef(false);

  const runExport = useCallback(async () => {
    if (busyRef.current) {
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setBusyMessage('Exportando…');
    setError(null);
    try {
      const resource = await api.exportXmi(projectId);
      downloadExportedFile(resource.fileBase64, projectName);
      setResult(resource);
    } catch (cause: unknown) {
      setError(messageFor(cause));
    } finally {
      busyRef.current = false;
      setBusy(false);
      setBusyMessage(null);
    }
  }, [projectId, projectName]);

  return (
    <XmiExportPanelView
      busy={busy}
      busyMessage={busyMessage}
      error={error}
      result={result}
      onExport={() => {
        void runExport();
      }}
      onDismissError={() => setError(null)}
    />
  );
}
