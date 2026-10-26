import type { CSSProperties } from 'react';
import type { RemoteCursorMarker } from './remoteCursors';

interface RemoteCursorsProps {
  markers: readonly RemoteCursorMarker[];
}

export default function RemoteCursors({ markers }: RemoteCursorsProps) {
  if (markers.length === 0) {
    return null;
  }

  return (
    <div className="remote-cursors" aria-label="Cursores de otras personas colaboradoras">
      {markers.map((marker) => (
        <div
          className="remote-cursor"
          key={marker.userId}
          style={{
            left: `${marker.x}px`,
            top: `${marker.y}px`,
            '--remote-cursor-color': marker.color,
          } as CSSProperties}
        >
          <svg aria-hidden="true" className="remote-cursor-pointer" viewBox="0 0 18 24">
            <path d="M1 1v18l5-5 4 8 3-1.5-4-8 7 0z" fill="currentColor" stroke="white" strokeWidth="1" />
          </svg>
          <span className="remote-cursor-name">{marker.displayName}</span>
        </div>
      ))}
    </div>
  );
}
