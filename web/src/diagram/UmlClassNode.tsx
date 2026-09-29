import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { Handle, NodeResizer, Position, type Node, type NodeProps } from '@xyflow/react';
import type { ClassifierResource } from '../api/types';
import { formatAttribute, formatOperation, operationIsStatic, operationNameIsAbstract, orderedAttributes, orderedOperations } from './uml-format';
import type { CanvasSelection } from './selection';

export interface ClassNodeData extends Record<string, unknown> {
  classifier: ClassifierResource;
  onSelectElement?: (selection: CanvasSelection) => void;
  onRenameClassifier?: (classifierId: string, name: string) => void;
  onRenameMember?: (kind: 'attribute' | 'operation', classifierId: string, elementId: string, name: string) => void;
  onResizeGeometry?: (
    classifierId: string,
    rectangle: { x: number; y: number; width: number; height: number },
  ) => void;
}

export type ClassFlowNode = Node<ClassNodeData, 'umlClass'>;

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
}

export function UmlClassCard({ classifier, onSelectElement, onRenameClassifier, onRenameMember }: UmlClassCardProps) {
  const attributes = orderedAttributes(classifier);
  const operations = orderedOperations(classifier);

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
              <div className={`uml-member-row ${modifiers}`} key={operation.id}>
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
      <Handle aria-label="Connection target" id="target-left" position={Position.Left} type="target" />
      <Handle aria-label="Connection source" id="source-right" position={Position.Right} type="source" />
      <UmlClassCard
        classifier={data.classifier}
        {...(data.onRenameClassifier ? { onRenameClassifier: data.onRenameClassifier } : {})}
        {...(data.onRenameMember ? { onRenameMember: data.onRenameMember } : {})}
        {...(data.onSelectElement ? { onSelectElement: data.onSelectElement } : {})}
      />
    </div>
  );
}
