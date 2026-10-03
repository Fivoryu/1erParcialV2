import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { RemoteCursorMarker } from './remoteCursors';
import RemoteCursors from './RemoteCursors.tsx';

const markers: RemoteCursorMarker[] = [
  { userId: 'user-a-remote', displayName: 'Alex Rivera', color: '#458d64', x: 12, y: 24 },
  { userId: 'user-z-remote', displayName: 'Zoe Martin', color: '#b45566', x: 48, y: 96 },
];

function render(cursors: readonly RemoteCursorMarker[]): string {
  return renderToStaticMarkup(createElement(RemoteCursors, { markers: cursors }));
}

function markerMarkup(markup: string, displayName: string): string | undefined {
  return (markup.match(/<div class="remote-cursor"[^>]*>[\s\S]*?<\/div>/g) ?? [])
    .find((row) => row.includes(`<span class="remote-cursor-name">${displayName}</span>`));
}

describe('RemoteCursors', () => {
  it("renders each person's name together with their own color", () => {
    const markup = render(markers);

    // These paired row assertions fail if the two people's colors are swapped.
    for (const marker of markers) {
      const row = markerMarkup(markup, marker.displayName);
      expect(row).toBeDefined();
      expect(row).toContain(`--remote-cursor-color:${marker.color}`);
    }
  });

  it('renders both markers in their deterministic user-id order', () => {
    const markup = render(markers);

    // These order and presence assertions fail if either marker is omitted or their markup order changes.
    expect(markup).toContain('Alex Rivera');
    expect(markup).toContain('Zoe Martin');
    expect(markup.indexOf('Alex Rivera')).toBeLessThan(markup.indexOf('Zoe Martin'));
  });

  it('does not expose raw user ids in markup', () => {
    const markup = render(markers);

    // These fixture-derived assertions fail if either raw user id reaches the UI.
    for (const marker of markers) {
      expect(markup).not.toContain(marker.userId);
    }
  });

  it('renders nothing when there are no markers', () => {
    // This assertion fails if an empty overlay wrapper or placeholder is emitted.
    expect(render([])).toBe('');
  });
});
