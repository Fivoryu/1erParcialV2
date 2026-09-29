import { useEffect, useId, useRef, useState, type PointerEvent } from 'react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  useReactFlow,
  type Edge,
  type EdgeProps,
} from '@xyflow/react';
import type { RelationshipEndResource, RelationshipResource } from '../api/types';
import { formatRelationshipEndLabel } from './uml-format';

export type RelationshipMarker = 'open-arrow' | 'hollow-diamond' | 'filled-diamond' | 'hollow-triangle' | null;

export interface RelationshipMarkerPresentation {
  lineStyle: 'solid' | 'dashed';
  sourceMarker: RelationshipMarker;
  targetMarker: RelationshipMarker;
  realizationLabelAtTarget: boolean;
}

export function relationshipMarkerPresentation(
  kind: string,
  ends: RelationshipEndResource[],
): RelationshipMarkerPresentation {
  const orderedEnds = [...ends].sort((left, right) => left.ordinal - right.ordinal);
  const sourceEnd = orderedEnds[0];
  const targetEnd = orderedEnds[1];
  switch (kind) {
    case 'AGGREGATION':
      return { lineStyle: 'solid', sourceMarker: 'hollow-diamond', targetMarker: null, realizationLabelAtTarget: false };
    case 'COMPOSITION':
      return { lineStyle: 'solid', sourceMarker: 'filled-diamond', targetMarker: null, realizationLabelAtTarget: false };
    case 'GENERALIZATION':
      return { lineStyle: 'solid', sourceMarker: null, targetMarker: 'hollow-triangle', realizationLabelAtTarget: false };
    case 'INTERFACE_REALIZATION':
      return { lineStyle: 'dashed', sourceMarker: null, targetMarker: 'hollow-triangle', realizationLabelAtTarget: true };
    case 'DEPENDENCY':
      return { lineStyle: 'dashed', sourceMarker: null, targetMarker: 'open-arrow', realizationLabelAtTarget: false };
    case 'ASSOCIATION':
    default:
      return {
        lineStyle: 'solid',
        sourceMarker: sourceEnd?.navigability === 'NAVIGABLE' ? 'open-arrow' : null,
        targetMarker: targetEnd?.navigability === 'NAVIGABLE' ? 'open-arrow' : null,
        realizationLabelAtTarget: false,
      };
  }
}

export interface RelationshipEdgeData extends Record<string, unknown> {
  relationship: RelationshipResource;
  sourceEnd: RelationshipEndResource;
  targetEnd: RelationshipEndResource;
  waypoints: Array<{ x: number; y: number }>;
  onWaypointsChange?: (
    relationshipId: string,
    waypoints: Array<{ x: number; y: number }>,
    settled: boolean,
  ) => void;
}

export type RelationshipFlowEdge = Edge<RelationshipEdgeData, 'umlRelationship'>;

export function RelationshipEndLabels({
  sourceEnd,
  targetEnd,
  realizationLabelAtTarget = false,
}: {
  sourceEnd: RelationshipEndResource;
  targetEnd: RelationshipEndResource;
  realizationLabelAtTarget?: boolean;
}) {
  const sourceLabel = formatRelationshipEndLabel(sourceEnd);
  const targetLabel = formatRelationshipEndLabel(targetEnd);
  return (
    <div className="uml-edge-labels">
      {sourceLabel && <span className="uml-edge-end-label uml-edge-end-label--source">{sourceLabel}</span>}
      {targetLabel && <span className="uml-edge-end-label uml-edge-end-label--target">{targetLabel}</span>}
      {realizationLabelAtTarget && <span className="uml-edge-stereotype uml-edge-stereotype--target">«realize»</span>}
    </div>
  );
}

function RelationshipEndLabel({ end, side }: { end: RelationshipEndResource; side: 'source' | 'target' }) {
  const label = formatRelationshipEndLabel(end);
  return label ? <span className={`uml-edge-end-label uml-edge-end-label--${side}`}>{label}</span> : null;
}

const MARKER_SUFFIXES: Record<Exclude<RelationshipMarker, null>, string> = {
  'open-arrow': 'open-arrow',
  'hollow-diamond': 'hollow-diamond',
  'filled-diamond': 'filled-diamond',
  'hollow-triangle': 'hollow-triangle',
};

function markerUrl(marker: RelationshipMarker, markerIds: Record<Exclude<RelationshipMarker, null>, string>): string | undefined {
  return marker ? `url(#${markerIds[marker]})` : undefined;
}

function pointAlongSegment(start: { x: number; y: number }, end: { x: number; y: number }, fraction: number) {
  return {
    x: start.x + (end.x - start.x) * fraction,
    y: start.y + (end.y - start.y) * fraction,
  };
}

function roundToGrid(value: number): number {
  return Math.round(value / 24) * 24;
}

export default function UmlRelationshipEdge(props: EdgeProps<RelationshipFlowEdge>) {
  const { screenToFlowPosition } = useReactFlow();
  const markerPrefix = useId().replaceAll(':', '');
  const markerIds = Object.fromEntries(Object.entries(MARKER_SUFFIXES)
    .map(([marker, suffix]) => [marker, `${markerPrefix}-${suffix}`])) as Record<Exclude<RelationshipMarker, null>, string>;
  const data = props.data!;
  const relationship = data.relationship;
  const [waypoints, setWaypoints] = useState(data.waypoints);
  const dragIndex = useRef<number | null>(null);
  const waypointsRef = useRef(waypoints);
  const orderedEnds = [data.sourceEnd, data.targetEnd];
  const markers = relationshipMarkerPresentation(relationship.kind, orderedEnds);

  useEffect(() => {
    setWaypoints(data.waypoints);
    waypointsRef.current = data.waypoints;
  }, [data.waypoints]);

  const points = [
    { x: props.sourceX, y: props.sourceY },
    ...waypoints,
    { x: props.targetX, y: props.targetY },
  ];
  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ');
  const sourceLabelPosition = pointAlongSegment(points[0]!, points[1]!, 0.35);
  const targetLabelPosition = pointAlongSegment(points[points.length - 1]!, points[points.length - 2]!, 0.35);
  const middle = pointAlongSegment(points[0]!, points[points.length - 1]!, 0.5);

  function updateWaypoint(event: PointerEvent<HTMLButtonElement>, index: number): void {
    if (dragIndex.current !== index) {
      return;
    }
    const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
    const next = waypointsRef.current.map((point, pointIndex) => pointIndex === index
      ? { x: roundToGrid(position.x), y: roundToGrid(position.y) }
      : point);
    waypointsRef.current = next;
    setWaypoints(next);
    data.onWaypointsChange?.(relationship.id, next, false);
  }

  function settleWaypoints(): void {
    const next = waypointsRef.current;
    dragIndex.current = null;
    data.onWaypointsChange?.(relationship.id, next, true);
  }

  function addWaypoint(): void {
    const next = [...waypointsRef.current, { x: roundToGrid(middle.x), y: roundToGrid(middle.y) }];
    waypointsRef.current = next;
    setWaypoints(next);
    data.onWaypointsChange?.(relationship.id, next, true);
  }

  return (
    <>
      <defs>
        <marker id={markerIds['open-arrow']} viewBox="0 -6 10 12" refX="0" refY="0" markerWidth="10" markerHeight="12" orient="auto-start-reverse" markerUnits="strokeWidth">
          <path d="M 0 -5 L 9 0 L 0 5" fill="none" stroke="#526b62" strokeWidth="1.4" />
        </marker>
        <marker id={markerIds['hollow-diamond']} viewBox="0 -7 14 14" refX="0" refY="0" markerWidth="14" markerHeight="14" orient="auto-start-reverse" markerUnits="strokeWidth">
          <path d="M 0 0 L 7 -6 L 14 0 L 7 6 Z" fill="white" stroke="#526b62" strokeWidth="1.4" />
        </marker>
        <marker id={markerIds['filled-diamond']} viewBox="0 -7 14 14" refX="0" refY="0" markerWidth="14" markerHeight="14" orient="auto-start-reverse" markerUnits="strokeWidth">
          <path d="M 0 0 L 7 -6 L 14 0 L 7 6 Z" fill="#526b62" stroke="#526b62" strokeWidth="1.4" />
        </marker>
        <marker id={markerIds['hollow-triangle']} viewBox="0 -8 14 16" refX="0" refY="0" markerWidth="14" markerHeight="16" orient="auto" markerUnits="strokeWidth">
          <path d="M 0 0 L 13 -7 L 13 7 Z" fill="white" stroke="#526b62" strokeWidth="1.4" />
        </marker>
      </defs>
      <BaseEdge
        id={props.id}
        path={path}
        {...(markers.sourceMarker ? { markerStart: markerUrl(markers.sourceMarker, markerIds)! } : {})}
        {...(markers.targetMarker ? { markerEnd: markerUrl(markers.targetMarker, markerIds)! } : {})}
        interactionWidth={20}
        style={{ stroke: '#526b62', strokeWidth: 1.7, ...(markers.lineStyle === 'dashed' ? { strokeDasharray: '6 4' } : {}) }}
      />
      <EdgeLabelRenderer>
        <div
          className="uml-edge-label-position"
          style={{ transform: `translate(-50%, -50%) translate(${sourceLabelPosition.x}px, ${sourceLabelPosition.y}px)` }}
        >
          <RelationshipEndLabel end={orderedEnds[0]!} side="source" />
        </div>
        <div
          className="uml-edge-label-position"
          style={{ transform: `translate(-50%, -50%) translate(${targetLabelPosition.x}px, ${targetLabelPosition.y}px)` }}
        >
          <RelationshipEndLabel end={orderedEnds[1]!} side="target" />
          {markers.realizationLabelAtTarget && <span className="uml-edge-stereotype">«realize»</span>}
        </div>
        {relationship.name && (
          <div
            className="uml-edge-name uml-edge-label-position"
            style={{ transform: `translate(-50%, -50%) translate(${middle.x}px, ${middle.y}px)` }}
          >{relationship.name}</div>
        )}
        {props.selected && (
          <>
            <button
              aria-label="Add connector bend"
              className="uml-waypoint-add nodrag nopan"
              onClick={addWaypoint}
              style={{ transform: `translate(-50%, -50%) translate(${middle.x}px, ${middle.y}px)` }}
              type="button"
            >+</button>
            {waypoints.map((waypoint, index) => (
              <button
                aria-label="Move connector bend"
                className="uml-waypoint-handle nodrag nopan"
                key={index}
                onPointerCancel={settleWaypoints}
                onPointerDown={(event) => {
                  event.preventDefault();
                  event.stopPropagation();
                  dragIndex.current = index;
                  event.currentTarget.setPointerCapture(event.pointerId);
                }}
                onPointerMove={(event) => updateWaypoint(event, index)}
                onPointerUp={settleWaypoints}
                style={{ transform: `translate(-50%, -50%) translate(${waypoint.x}px, ${waypoint.y}px)` }}
                type="button"
              />
            ))}
          </>
        )}
      </EdgeLabelRenderer>
    </>
  );
}
