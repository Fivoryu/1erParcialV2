import type { PersistenceStore } from '../api/types';

export const STORE_LABELS: Record<PersistenceStore, string> = {
  SQL: 'SQL',
  DOCUMENT: 'Documento',
};

export function describeStoreReason(reason: string): string {
  switch (reason) {
    case 'Set manually.':
      return 'Configurado manualmente.';
    case 'Has list attributes, which a document stores natively.':
      return 'Tiene atributos de lista, que un documento almacena de forma nativa.';
    case 'No document signal, so it stays relational.':
      return 'No hay señales de documentos, por lo que se mantiene relacional.';
    default: {
      const compositionReason = /^Part of a composition owned by (.+)\.$/.exec(reason);
      return compositionReason
        ? `Forma parte de una composición propiedad de ${compositionReason[1]}.`
        : reason;
    }
  }
}

function StoreIcon({ store }: { store: PersistenceStore }) {
  return (
    <svg
      aria-hidden="true"
      className="store-badge-icon"
      fill="none"
      height="11"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.4"
      viewBox="0 0 16 16"
      width="11"
    >
      {store === 'SQL' ? (
        <>
          <ellipse cx="8" cy="4" rx="5.5" ry="2.2" />
          <path d="M2.5 4v8c0 1.2 2.5 2.2 5.5 2.2s5.5-1 5.5-2.2V4" />
          <path d="M2.5 8c0 1.2 2.5 2.2 5.5 2.2s5.5-1 5.5-2.2" />
        </>
      ) : (
        <>
          <path d="M4 1.8h5l3 3v9.4H4z" />
          <path d="M9 1.8v3h3" />
          <path d="M6 8.2h4M6 10.8h4" />
        </>
      )}
    </svg>
  );
}

/** Where a class is persisted. A fixed choice is marked so it reads differently from an automatic one. */
export function StoreBadge({ store, manual, reason }: { store: PersistenceStore; manual: boolean; reason?: string }) {
  const label = STORE_LABELS[store];
  const title = reason ? `${label}: ${describeStoreReason(reason)}` : label;
  return (
    <span
      aria-label={`Almacenamiento persistente: ${label}${manual ? ', configurado manualmente' : ', automático'}`}
      className={`store-badge store-badge--${store === 'SQL' ? 'sql' : 'document'}${manual ? ' store-badge--manual' : ''}`}
      data-manual={manual ? 'true' : 'false'}
      data-store={store}
      data-testid="uml-store-badge"
      title={title}
    >
      <StoreIcon store={store} />
      <span className="store-badge-text">{label}</span>
      {manual && <span aria-hidden="true" className="store-badge-pin" />}
    </span>
  );
}
