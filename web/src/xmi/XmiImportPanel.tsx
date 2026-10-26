import { useCallback, useId, useRef, useState } from 'react';
import { ApiTransportError, createApiClient } from '../api/client';
import type { ApiClient } from '../api/client';
import type {
  XmiDisagreementResource,
  XmiImportMode,
  XmiImportResource,
  XmiLossResource,
  XmiPreviewResource,
  Uuid,
} from '../api/types';

const api = createApiClient();

export async function previewXmiFile(
  apiClient: Pick<ApiClient, 'previewXmi'>,
  projectId: Uuid,
  file: Pick<File, 'arrayBuffer'>,
): Promise<{ fileBase64: string; preview: XmiPreviewResource }> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = '';
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  const fileBase64 = btoa(binary);
  const preview = await apiClient.previewXmi(projectId, { fileBase64 });
  return { fileBase64, preview };
}

export function confirmXmiImport(
  apiClient: Pick<ApiClient, 'importXmi'>,
  projectId: Uuid,
  fileBase64: string,
  preview: XmiPreviewResource,
  mode: XmiImportMode,
  idempotencyKey: string,
): Promise<XmiImportResource> {
  return apiClient.importXmi(projectId, {
    fileBase64,
    checksum: preview.checksum,
    mode,
    idempotencyKey,
  });
}

export interface XmiImportPanelViewProps {
  fileName: string | null;
  preview: XmiPreviewResource | null;
  mode: XmiImportMode | null;
  importResult: XmiImportResource | null;
  busy: boolean;
  busyMessage: string | null;
  error: string | null;
  onSelectFile(file: File): void;
  onSelectMode(mode: XmiImportMode): void;
  onConfirm(): void;
  onDismissError(): void;
}

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

function DisagreementReport({ disagreements }: { disagreements: XmiDisagreementResource[] }) {
  return (
    <div className="generation-section">
      <h3>Discrepancias</h3>
      {disagreements.length === 0 ? (
        <p className="property-hint">No se detectaron discrepancias.</p>
      ) : (
        <ul className="generation-diagnostics">
          {disagreements.map((disagreement, index) => (
            <li key={`${disagreement.kind}-${disagreement.subjectId}-${index}`}>
              <strong>{disagreement.kind}</strong>
              <span>Sujeto: {disagreement.subjectId}</span>
              <span>Valor semántico: {disagreement.semanticValue}</span>
              <span>Valor de extensión: {disagreement.extensionValue}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function XmiImportPanelView({
  fileName,
  preview,
  mode,
  importResult,
  busy,
  busyMessage,
  error,
  onSelectFile,
  onSelectMode,
  onConfirm,
  onDismissError,
}: XmiImportPanelViewProps) {
  const fieldPrefix = useId();
  const replaceModeId = `${fieldPrefix}-replace`;
  const addModeId = `${fieldPrefix}-add`;
  const collisionDiagnostics = importResult?.command.diagnostics.filter(
    (diagnostic) => diagnostic.code === 'NAME_COLLISION_BLOCKS',
  ) ?? [];
  const hasNameCollision = collisionDiagnostics.length > 0;
  const counts = preview === null ? null : [
    ['Clasificadores', preview.counts.classifiers],
    ['Relaciones', preview.counts.relationships],
    ['Vistas', preview.counts.views],
    ['Ubicaciones de figuras', preview.counts.figurePlacements],
    ['Ubicaciones de conectores', preview.counts.connectorPlacements],
  ] as const;
  const confirmLabel = mode === 'REEMPLAZAR'
    ? 'Confirmar importación y reemplazar el modelo existente'
    : mode === 'AGREGAR'
      ? 'Confirmar importación y agregar al modelo existente'
      : 'Elige un modo de importación';

  return (
    <section className="generation-panel" aria-labelledby="xmi-import-title">
      <div className="generation-heading">
        <div>
          <p className="eyebrow">Intercambio XMI</p>
          <h2 id="xmi-import-title">Importar un archivo XMI</h2>
        </div>
      </div>

      <p className="property-hint">
        Solo vista previa: revisar este archivo no modifica el proyecto. El modelo solo cambia cuando confirmas la importación.
      </p>

      {error && (
        <div className="notice notice-error" role="alert">
          <span>{error}</span>
          <button className="text-button" type="button" onClick={onDismissError}>Descartar</button>
        </div>
      )}

      {busy && <p className="generation-notice" role="status">{busyMessage ?? 'Procesando…'}</p>}

      <div className="generation-section">
        <h3>Elegir un archivo</h3>
        <div className="generation-target-form">
          <label htmlFor={`${fieldPrefix}-file`}>Archivo XMI</label>
          <input
            id={`${fieldPrefix}-file`}
            type="file"
            accept=".xmi,.xml"
            disabled={busy}
            onChange={(event) => {
              const file = event.currentTarget.files?.[0];
              if (file) {
                onSelectFile(file);
              }
              event.currentTarget.value = '';
            }}
          />
          {fileName && <p className="property-hint">Archivo seleccionado: {fileName}</p>}
        </div>
      </div>

      {preview && counts && (
        <div className="generation-run" aria-live="polite">
          <p className="generation-run-state">Vista previa lista. No se realizaron cambios en el proyecto.</p>
          <div className="generation-section">
            <h3>Contenido reconocido</h3>
            <dl>
              {counts.map(([label, count]) => (
                <div key={label}>
                  <dt>{label}</dt><dd>{count}</dd>
                </div>
              ))}
            </dl>
          </div>
          <LossReport losses={preview.losses} />
          <DisagreementReport disagreements={preview.disagreements} />
        </div>
      )}

      {preview && (
        <div className="generation-section">
          <h3>Elige cómo importar</h3>
          <div className="generation-target-form">
            <label htmlFor={replaceModeId}>
              <input
                id={replaceModeId}
                type="radio"
                name={`${fieldPrefix}-mode`}
                value="REEMPLAZAR"
                checked={mode === 'REEMPLAZAR'}
                onChange={() => onSelectMode('REEMPLAZAR')}
                disabled={busy}
              />
              {' '}REEMPLAZAR — reemplazar el modelo existente
            </label>
            <label htmlFor={addModeId}>
              <input
                id={addModeId}
                type="radio"
                name={`${fieldPrefix}-mode`}
                value="AGREGAR"
                checked={mode === 'AGREGAR'}
                onChange={() => onSelectMode('AGREGAR')}
                disabled={busy}
              />
              {' '}AGREGAR — agregar junto al contenido existente
            </label>
          </div>
          {mode === 'REEMPLAZAR' && (
            <div className="notice notice-error" role="alert">
              REEMPLAZAR eliminará todo el contenido existente del modelo. Esta acción no se puede deshacer.
            </div>
          )}
          <div className="generation-actions">
            <button
              className="button button-primary"
              type="button"
              onClick={onConfirm}
              disabled={busy || mode === null}
            >
              {busy && busyMessage?.startsWith('Importando') ? 'Importando…' : confirmLabel}
            </button>
          </div>
        </div>
      )}

      {importResult && (
        <div className="generation-run" aria-live="polite">
          <h3>Resultado de la importación</h3>
          <p className="generation-run-state" data-state={importResult.outcome}>
            Resultado de importación: {importResult.outcome}
          </p>
          {hasNameCollision && (
            <div className="notice notice-error" role="alert">
              <strong>La importación está bloqueada por un conflicto de nombres.</strong>
              <ul>
                {collisionDiagnostics.map((diagnostic, index) => (
                  <li key={`${diagnostic.code}-${index}`}>{diagnostic.message}</li>
                ))}
              </ul>
            </div>
          )}
          {!hasNameCollision && importResult.command.status !== 'ACCEPTED' && (
            <div className="notice notice-error" role="alert">
              <strong>No se aplicó la importación.</strong>
              {importResult.command.diagnostics.length > 0 && (
                <ul>
                  {importResult.command.diagnostics.map((diagnostic, index) => (
                    <li key={`${diagnostic.code}-${index}`}>{diagnostic.message}</li>
                  ))}
                </ul>
              )}
            </div>
          )}
          <LossReport losses={importResult.losses} />
          <DisagreementReport disagreements={importResult.disagreements} />
        </div>
      )}
    </section>
  );
}

export interface XmiImportPanelProps {
  projectId: Uuid;
}

function messageFor(error: unknown): string {
  if (error instanceof ApiTransportError) {
    return error.message;
  }
  return 'No se pudo completar la solicitud XMI. Revisa el archivo e inténtalo de nuevo.';
}

export default function XmiImportPanel({ projectId }: XmiImportPanelProps) {
  const [fileName, setFileName] = useState<string | null>(null);
  const [fileBase64, setFileBase64] = useState<string | null>(null);
  const [preview, setPreview] = useState<XmiPreviewResource | null>(null);
  const [mode, setMode] = useState<XmiImportMode | null>(null);
  const [importResult, setImportResult] = useState<XmiImportResource | null>(null);
  const [busy, setBusy] = useState(false);
  const [busyMessage, setBusyMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const previewSequence = useRef(0);

  const selectFile = useCallback(async (file: File) => {
    const sequence = previewSequence.current + 1;
    previewSequence.current = sequence;
    setFileName(file.name);
    setFileBase64(null);
    setPreview(null);
    setMode(null);
    setImportResult(null);
    setError(null);
    setBusy(true);
    setBusyMessage('Preparando vista previa…');
    try {
      const loaded = await previewXmiFile(api, projectId, file);
      if (previewSequence.current === sequence) {
        setFileBase64(loaded.fileBase64);
        setPreview(loaded.preview);
      }
    } catch (cause: unknown) {
      if (previewSequence.current === sequence) {
        setError(messageFor(cause));
      }
    } finally {
      if (previewSequence.current === sequence) {
        setBusy(false);
        setBusyMessage(null);
      }
    }
  }, [projectId]);

  const chooseMode = useCallback((nextMode: XmiImportMode) => {
    setMode(nextMode);
    setImportResult(null);
    setError(null);
  }, []);

  const confirm = useCallback(async () => {
    if (!preview || !fileBase64 || !mode) {
      return;
    }
    setBusy(true);
    setBusyMessage('Importando…');
    setError(null);
    setImportResult(null);
    try {
      const result = await confirmXmiImport(
        api,
        projectId,
        fileBase64,
        preview,
        mode,
        crypto.randomUUID(),
      );
      setImportResult(result);
    } catch (cause: unknown) {
      setError(messageFor(cause));
    } finally {
      setBusy(false);
      setBusyMessage(null);
    }
  }, [fileBase64, mode, preview, projectId]);

  return (
    <XmiImportPanelView
      fileName={fileName}
      preview={preview}
      mode={mode}
      importResult={importResult}
      busy={busy}
      busyMessage={busyMessage}
      error={error}
      onSelectFile={(file) => {
        void selectFile(file);
      }}
      onSelectMode={chooseMode}
      onConfirm={() => {
        void confirm();
      }}
      onDismissError={() => setError(null)}
    />
  );
}
