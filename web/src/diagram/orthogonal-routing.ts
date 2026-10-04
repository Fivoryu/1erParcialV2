/**
 * IA4-07: deterministic orthogonal routing for relationship edges.
 *
 * Pure geometry only: no node-avoidance is promised. The route exits the source
 * rectangle on the axis of the greater center-to-center delta, crosses at the
 * midpoint of the remaining axis, and enters the target rectangle straight on.
 */
export interface OrthoPoint {
  x: number;
  y: number;
}

export interface OrthoRectangle {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Full polyline (endpoints included) between the two rectangle borders. */
export function orthogonalWaypoints(source: OrthoRectangle, target: OrthoRectangle): OrthoPoint[] {
  const sourceCenter = { x: source.x + source.width / 2, y: source.y + source.height / 2 };
  const targetCenter = { x: target.x + target.width / 2, y: target.y + target.height / 2 };
  const deltaX = targetCenter.x - sourceCenter.x;
  const deltaY = targetCenter.y - sourceCenter.y;

  if (Math.abs(deltaX) >= Math.abs(deltaY)) {
    const startX = deltaX >= 0 ? source.x + source.width : source.x;
    const endX = deltaX >= 0 ? target.x : target.x + target.width;
    if (sourceCenter.y === targetCenter.y) {
      return [{ x: startX, y: sourceCenter.y }, { x: endX, y: targetCenter.y }];
    }
    const midX = (startX + endX) / 2;
    return [
      { x: startX, y: sourceCenter.y },
      { x: midX, y: sourceCenter.y },
      { x: midX, y: targetCenter.y },
      { x: endX, y: targetCenter.y },
    ];
  }

  const startY = deltaY >= 0 ? source.y + source.height : source.y;
  const endY = deltaY >= 0 ? target.y : target.y + target.height;
  if (sourceCenter.x === targetCenter.x) {
    return [{ x: sourceCenter.x, y: startY }, { x: targetCenter.x, y: endY }];
  }
  const midY = (startY + endY) / 2;
  return [
    { x: sourceCenter.x, y: startY },
    { x: sourceCenter.x, y: midY },
    { x: targetCenter.x, y: midY },
    { x: targetCenter.x, y: endY },
  ];
}

/**
 * SVG path for an orthogonal polyline with quadratic-rounded corners. A radius of
 * zero (or degenerate polylines) yields the plain polyline. The radius is clamped
 * to half of each corner's shorter adjacent segment so corners never overlap.
 */
export function roundedOrthogonalPath(points: OrthoPoint[], cornerRadius: number): string {
  if (points.length === 0) {
    return '';
  }
  const radius = Math.max(0, cornerRadius);
  let path = `M ${points[0]!.x} ${points[0]!.y}`;
  for (let index = 1; index < points.length - 1; index += 1) {
    const previous = points[index - 1]!;
    const vertex = points[index]!;
    const next = points[index + 1]!;
    const incoming = Math.hypot(vertex.x - previous.x, vertex.y - previous.y);
    const outgoing = Math.hypot(next.x - vertex.x, next.y - vertex.y);
    const clamped = Math.min(radius, incoming / 2, outgoing / 2);
    const before = {
      x: vertex.x - ((vertex.x - previous.x) / incoming) * clamped,
      y: vertex.y - ((vertex.y - previous.y) / incoming) * clamped,
    };
    const after = {
      x: vertex.x + ((next.x - vertex.x) / outgoing) * clamped,
      y: vertex.y + ((next.y - vertex.y) / outgoing) * clamped,
    };
    if (clamped > 0) {
      path += ` L ${before.x} ${before.y} Q ${vertex.x} ${vertex.y} ${after.x} ${after.y}`;
    } else {
      path += ` L ${vertex.x} ${vertex.y}`;
    }
  }
  const last = points[points.length - 1]!;
  if (points.length > 1) {
    path += ` L ${last.x} ${last.y}`;
  }
  return path;
}
