import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { Handle, NodeResizer, Position, type Node, type NodeProps } from '@xyflow/react';
import type { ClassifierResource } from '../api/types';
import { formatAttribute, formatOperation, operationIsStatic, operationNameIsAbstract, orderedAttributes, orderedOperations } from './uml-format';
import type { CanvasSelection } from './selection';
import type { TouchedBy } from './touch-highlight';

export interface ClassNodeData extends Record<string, unknown> {
  classifier: ClassifierResource;
  onSelectElement?: (selection: CanvasSelection) => void;
  onRenameClassifier?: (classifierId: string, name: string) => void;
  onRenameMember?: (kind: 'attribute' | 'operation', classifierId: string, elementId: string, name: string) => void;
  touchedBy?: TouchedBy[];
  onResizeGeometry?: (
    classifierId: string,
    rectangle: { x: number; y: number; width: number; height: number },
  ) => void;
}

export type ClassFlowNode = Node<ClassNodeData, 'umlClass'>;

const connectionHandleKeyboardProps = {
  tabIndex: 0,
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      event.stopPropagation();
      event.currentTarget.click();
    }
  },
};

interface EditableTextProps {
  displayValue: string;
  editValue: string;
  ariaLabel: string;
  className?: string;
  onCommit: (value: string) => void;
  onSelect?: () => void;
}

function EditableText({ displayValue, editValue, ariaLabel, className, onCommit, onSelect }: EditableTextProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(editValue);
  const completed = useRef(false);

  useEffect(() => {
    if (!editing) {
      setDraft(editValue);
    }
  }, [editValue, editing]);

  function startEditing(event: MouseEvent<HTMLSpanElement>) {
    event.stopPropagation();
    completed.current = false;
    setDraft(editValue);
    setEditing(true);
  }

  function commit() {
    if (completed.current) {
      return;
    }
    completed.current = true;
    setEditing(false);
    const value = draft.trim();
    if (value && value !== editValue) {
      onCommit(value);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    event.stopPropagation();
    if (event.key === 'Enter') {
      event.preventDefault();
      commit();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      completed.current = true;
      setDraft(editValue);
      setEditing(false);
    }
  }

  if (editing) {
    return (
      <input
        aria-label={ariaLabel}
        autoFocus
        className="uml-inline-editor nodrag"
        onBlur={commit}
        onChange={(event) => setDraft(event.target.value)}
        onClick={(event) => event.stopPropagation()}
        onKeyDown={handleKeyDown}
        onMouseDown={(event) => event.stopPropagation()}
        value={draft}
      />
    );
  }

  return (
    <span
      aria-label={ariaLabel}
      className={className}
      onClick={(event) => {
        if (onSelect) {
          event.stopPropagation();
          onSelect();
        }
      }}
      onDoubleClick={startEditing}
      title="Double-click to edit"
    >
      {displayValue}
    </span>
  );
}

export interface UmlClassCardProps {
  classifier: ClassifierResource;
  onSelectElement?: (selection: CanvasSelection) => void;
  onRenameClassifier?: (classifierId: string, name: string) => void;
  onRenameMember?: (kind: 'attribute' | 'operation', classifierId: string, elementId: string, name: string) => void;
  touchedBy?: TouchedBy[];
}

function TouchHighlights({ touchedBy }: { touchedBy?: TouchedBy[] | undefined }) {
  if (!touchedBy?.length) {
    return null;
  }

  return (
    <div className="uml-touch-highlights" aria-label="People touching this element">
      {touchedBy.map((toucher) => (
        <span className="uml-touch-chip" key={toucher.userId}>
          <span
            aria-hidden="true"
            className="uml-touch-chip-swatch"
            style={{ backgroundColor: toucher.color }}
          />
          <span className="uml-touch-chip-name">{toucher.displayName}</span>
        </span>
      ))}
    </div>
  );
}

export function UmlClassCard({ classifier, onSelectElement, onRenameClassifier, onRenameMember, touchedBy }: UmlClassCardProps) {
  const attributes = orderedAttributes(classifier);
  const operations = orderedOperations(classifier);

  if (classifier.kind === 'PACKAGE') {
    return (
      <div className="uml-package-card" data-testid="uml-package-card">
        {/* Namespace is semantic only: members keep independent canvas positions; geometric containment and grouped dragging are not implemented. */}
        <div className="uml-package-tab" aria-hidden="true" />
        <div className="uml-package-name">
          <TouchHighlights touchedBy={touchedBy} />
          <EditableText
            ariaLabel="Edit package name"
            className="uml-editable-label"
            displayValue={classifier.name}
            editValue={classifier.name}
            onCommit={(name) => onRenameClassifier?.(classifier.id, name)}
          />
        </div>
      </div>
    );
  }

  return (
    <div className="uml-class-card" data-testid="uml-class-card">
      <div className="uml-class-heading">
        {classifier.kind === 'INTERFACE' && <div className="uml-stereotype">«interface»</div>}
        <div className={`uml-class-name ${classifier.isAbstract ? 'uml-class-name--abstract' : ''}`}>
          {classifier.isAbstract ? (
            <i>
              <EditableText
                ariaLabel="Edit class name"
                className="uml-editable-label"
                displayValue={classifier.name}
                editValue={classifier.name}
                onCommit={(name) => onRenameClassifier?.(classifier.id, name)}
              />
            </i>
          ) : (
            <EditableText
              ariaLabel="Edit class name"
              className="uml-editable-label"
              displayValue={classifier.name}
              editValue={classifier.name}
              onCommit={(name) => onRenameClassifier?.(classifier.id, name)}
            />
          )}
        </div>
        <TouchHighlights touchedBy={touchedBy} />
      </div>

      {attributes.length > 0 && (
        <div className="uml-compartment uml-compartment--attributes" aria-label="Attributes">
          {attributes.map((attribute) => {
            const selection: CanvasSelection = {
              kind: 'attribute',
              classifierId: classifier.id,
              elementId: attribute.id,
            };
            return (
              <div className="uml-member-row" key={attribute.id}>
                <EditableText
                  ariaLabel={`Edit attribute ${attribute.name}`}
                  className="uml-member-text"
                  displayValue={formatAttribute(attribute)}
                  editValue={attribute.name}
                  onCommit={(name) => onRenameMember?.('attribute', classifier.id, attribute.id, name)}
                  onSelect={() => onSelectElement?.(selection)}
                />
              </div>
            );
          })}
        </div>
      )}

      {operations.length > 0 && (
        <div className="uml-compartment uml-compartment--operations" aria-label="Operations">
          {operations.map((operation) => {
            const selection: CanvasSelection = {
              kind: 'operation',
              classifierId: classifier.id,
              elementId: operation.id,
            };
            const modifiers = [
              operationNameIsAbstract(operation) ? 'uml-member--abstract' : '',
              operationIsStatic(operation) ? 'uml-member--static' : '',
            ].filter(Boolean).join(' ');
            return (
              <div className={`uml-member-row ${modifiers}`} key={operation.id} data-element-kind="operation">
                <EditableText
                  ariaLabel={`Edit operation ${operation.name}`}
                  className="uml-member-text"
                  displayValue={formatOperation(operation)}
                  editValue={operation.name}
                  onCommit={(name) => onRenameMember?.('operation', classifier.id, operation.id, name)}
                  onSelect={() => onSelectElement?.(selection)}
                />
              </div>
            );
          })}
        </div>
      )}

      {classifier.constraints.length > 0 && (
        <div className="uml-compartment uml-compartment--constraints" aria-label="Constraints">
          {classifier.constraints.map((constraint) => (
            <div
              className="uml-constraint-row"
              key={constraint.id}
              onClick={(event) => {
                event.stopPropagation();
                onSelectElement?.({ kind: 'constraint', classifierId: classifier.id, elementId: constraint.id });
              }}
            >
              {`{${constraint.expression}}`}
              {constraint.severity !== 'ERROR' && (
                <span
                  aria-label={constraint.severity === 'WARNING' ? 'Warning constraint' : 'Informational constraint'}
                  className="uml-constraint-severity"
                  title={constraint.severity === 'WARNING' ? 'Warning severity' : 'Informational severity'}
                >{constraint.severity === 'WARNING' ? '⚠' : 'ⓘ'}</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function UmlClassNode({ data, selected }: NodeProps<ClassFlowNode>) {
  return (
    <div className="uml-flow-node">
      <NodeResizer
        color="#216b61"
        isVisible={selected}
        minHeight={96}
        minWidth={190}
        handleClassName="uml-resize-handle"
        onResizeEnd={(_, params) => data.onResizeGeometry?.(data.classifier.id, {
          x: params.x,
          y: params.y,
          width: params.width,
          height: params.height,
        })}
      />
      <Handle {...connectionHandleKeyboardProps} aria-label="Connection target, left" id="target-left" position={Position.Left} type="target" />
      <Handle {...connectionHandleKeyboardProps} aria-label="Connection source, right" id="source-right" position={Position.Right} type="source" />
      <Handle {...connectionHandleKeyboardProps} aria-label="Connection target, right" id="target-right" position={Position.Right} type="target" />
      <Handle {...connectionHandleKeyboardProps} aria-label="Connection source, left" id="source-left" position={Position.Left} type="source" />
      {/*
        Every side offers both a source and a target handle. An edge between vertically stacked
        classes has to leave through the bottom and enter through the top; with only left and right
        it is forced onto the sides and the straight line crosses the class box, which also drags
        the UML arrowhead onto the wrong border.

        Offering them is not enough on its own, and that was measured: React Flow falls back to the
        first handle of each type, so the path stayed at `M 825 415 L 535 120` when the sides were
        the only option. What chooses the side is `chooseHandles` in UmlCanvas, which derives it
        from the two node rectangles.
      */}
      <Handle {...connectionHandleKeyboardProps} aria-label="Connection target, top" id="target-top" position={Position.Top} type="target" />
      <Handle {...connectionHandleKeyboardProps} aria-label="Connection target, bottom" id="target-bottom" position={Position.Bottom} type="target" />
      <Handle {...connectionHandleKeyboardProps} aria-label="Connection source, top" id="source-top" position={Position.Top} type="source" />
      <Handle {...connectionHandleKeyboardProps} aria-label="Connection source, bottom" id="source-bottom" position={Position.Bottom} type="source" />
      <UmlClassCard
        classifier={data.classifier}
        {...(data.onRenameClassifier ? { onRenameClassifier: data.onRenameClassifier } : {})}
        {...(data.onRenameMember ? { onRenameMember: data.onRenameMember } : {})}
        {...(data.onSelectElement ? { onSelectElement: data.onSelectElement } : {})}
        {...(data.touchedBy ? { touchedBy: data.touchedBy } : {})}
      />
    </div>
  );
}
