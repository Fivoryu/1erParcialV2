import { Handle, NodeResizer, Position, type Node, type NodeProps } from '@xyflow/react';
import type { NoteResource } from '../api/types';
import { noteSummary } from './uml-format';

export interface NoteNodeData extends Record<string, unknown> {
  note: NoteResource;
  onSelectNote?: (noteId: string) => void;
  onResizeGeometry?: (
    noteId: string,
    rectangle: { x: number; y: number; width: number; height: number },
  ) => void;
}

export type NoteFlowNode = Node<NoteNodeData, 'umlNote'>;

/** Invisible 1x1 stand-in for a relationship, so a note link can end on the middle of a relationship. */
export interface NoteAnchorData extends Record<string, unknown> {
  relationshipId: string;
}

export type NoteAnchorFlowNode = Node<NoteAnchorData, 'umlNoteAnchor'>;

/** The body of a note as the canvas draws it: wrapped text inside a rectangle with a folded corner. */
export function UmlNoteCard({ note }: { note: NoteResource }) {
  return (
    <div aria-label={`Nota: ${noteSummary(note.body)}`} className="uml-note-card" data-note-id={note.id} role="note">
      <p className="uml-note-body">{note.body}</p>
    </div>
  );
}

const SIDES = [
  ['top', Position.Top],
  ['right', Position.Right],
  ['bottom', Position.Bottom],
  ['left', Position.Left],
] as const;

export function UmlNoteNode({ data, selected }: NodeProps<NoteFlowNode>) {
  return (
    <div className="uml-flow-node uml-note-node" onClick={() => data.onSelectNote?.(data.note.id)}>
      <NodeResizer
        color="#8b3c86"
        isVisible={selected}
        minHeight={56}
        minWidth={120}
        handleClassName="uml-resize-handle"
        onResizeEnd={(_, params) => data.onResizeGeometry?.(data.note.id, {
          x: params.x,
          y: params.y,
          width: params.width,
          height: params.height,
        })}
      />
      {/* Links are drawn from the note; the user never starts a relationship from one. */}
      {SIDES.map(([side, position]) => (
        <Handle id={`source-${side}`} isConnectable={false} key={`source-${side}`} position={position} type="source" />
      ))}
      <UmlNoteCard note={data.note} />
    </div>
  );
}

export function UmlNoteAnchorNode() {
  return (
    <div className="uml-note-anchor" aria-hidden="true">
      {SIDES.map(([side, position]) => (
        <Handle id={`target-${side}`} isConnectable={false} key={`target-${side}`} position={position} type="target" />
      ))}
    </div>
  );
}
