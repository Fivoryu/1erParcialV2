import { useState, type ReactElement } from 'react';
import type { ClassifierKind } from './commands';
import { RELATION_TOOL_KINDS, relationToolChoice, type RelationToolKind } from './relationTool';

/** UML notation of each relation kind, drawn small enough to sit in a 72px tool. */
export function RelationKindIcon({ kind }: { kind: RelationToolKind }): ReactElement {
  const common = { viewBox: '0 0 52 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, 'aria-hidden': true } as const;
  switch (kind) {
    case 'AGGREGATION':
      return <svg {...common}><path d="M16 12h31M4 12l6-5 6 5-6 5z" /></svg>;
    case 'COMPOSITION':
      return <svg {...common}><path d="M16 12h31" /><path d="M4 12l6-5 6 5-6 5z" fill="currentColor" /></svg>;
    case 'GENERALIZATION':
      return <svg {...common}><path d="M5 12h31M36 6l11 6-11 6z" /></svg>;
    case 'INTERFACE_REALIZATION':
      return <svg {...common}><path d="M5 12h31" strokeDasharray="4 3" /><path d="M36 6l11 6-11 6z" /></svg>;
    case 'DEPENDENCY':
      return <svg {...common}><path d="M5 12h42" strokeDasharray="4 3" /><path d="M38 6l9 6-9 6" /></svg>;
    case 'ASSOCIATION_CLASS':
      return (
        <svg {...common}>
          <path d="M5 6h42" />
          <path d="M26 6v8" strokeDasharray="2 2" />
          <rect x="18" y="14" width="16" height="8" fill="var(--paper)" />
        </svg>
      );
    default:
      return <svg {...common}><path d="M5 12h42" /></svg>;
  }
}

const CLASSIFIER_TOOLS: ReadonlyArray<{ kind: ClassifierKind; label: string; glyph: string }> = [
  { kind: 'CLASS', label: 'Clase', glyph: '▤' },
  { kind: 'ABSTRACT_CLASS', label: 'Abstracta', glyph: '▥' },
  { kind: 'INTERFACE', label: 'Interfaz', glyph: '◇' },
  { kind: 'ENUMERATION', label: 'Enumeración', glyph: '☰' },
  { kind: 'PACKAGE', label: 'Paquete', glyph: '▭' },
];

export interface ToolRailProps {
  relationKind: RelationToolKind;
  relationActive: boolean;
  onSelectTool: () => void;
  onAddClassifier: (kind: ClassifierKind) => void;
  onToggleRelation: () => void;
  onRelationKindChange: (kind: RelationToolKind) => void;
}

/**
 * Left rail of the editor. The relation tool shows the current kind; its small ▾ unfolds the
 * other kinds right below it, inside the rail, so choosing a kind never covers the diagram.
 */
export default function ToolRail(props: ToolRailProps): ReactElement {
  const [pickerOpen, setPickerOpen] = useState(false);
  const current = relationToolChoice(props.relationKind);

  return (
    <nav className="tool-rail" aria-label="Herramientas del diagrama">
      <p className="tool-rail-title">Herramientas</p>
      <button
        aria-pressed={!props.relationActive}
        className="tool-button"
        onClick={props.onSelectTool}
        type="button"
      >
        <b aria-hidden="true">↖</b>
        Seleccionar
      </button>
      {CLASSIFIER_TOOLS.map((tool) => (
        <button
          className="tool-button"
          key={tool.kind}
          onClick={() => props.onAddClassifier(tool.kind)}
          title={`Agregar ${tool.label.toLowerCase()}`}
          type="button"
        >
          <b aria-hidden="true">{tool.glyph}</b>
          {tool.label}
        </button>
      ))}
      <div className="relation-anchor">
        <button
          aria-controls="relation-setup"
          aria-expanded={props.relationActive}
          aria-pressed={props.relationActive}
          className="tool-button relation-tool"
          onClick={props.onToggleRelation}
          title={`Crear ${current.label.toLowerCase()}`}
          type="button"
        >
          <b aria-hidden="true"><RelationKindIcon kind={current.kind} /></b>
          <span>{current.label}</span>
        </button>
        <button
          aria-controls="relation-picker"
          aria-expanded={pickerOpen}
          aria-label="Elegir tipo de relación"
          className="relation-toggle"
          onClick={() => setPickerOpen((open) => !open)}
          type="button"
        >
          <span aria-hidden="true">▾</span>
        </button>
      </div>
      {pickerOpen && (
        <div aria-label="Tipos de relación UML" className="relation-picker" id="relation-picker" role="group">
          {RELATION_TOOL_KINDS.map((choice) => (
            <button
              aria-pressed={choice.kind === props.relationKind}
              className="relation-choice"
              key={choice.kind}
              onClick={() => {
                props.onRelationKindChange(choice.kind);
                setPickerOpen(false);
              }}
              title={choice.description}
              type="button"
            >
              <RelationKindIcon kind={choice.kind} />
              <strong>{choice.label}</strong>
            </button>
          ))}
        </div>
      )}
    </nav>
  );
}
