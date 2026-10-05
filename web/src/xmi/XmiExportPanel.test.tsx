import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { XmiExportPanelView, type XmiExportPanelViewProps } from './XmiExportPanel';
import type { XmiExportResource } from '../api/types';

const exportResult: XmiExportResource = {
  fileBase64: 'PD94bWwgdmVyc2lvbj0iMS4wIj8+',
  checksum: 'a'.repeat(64),
  exchangeId: '55555555-5555-4555-8555-555555555551',
  losses: [{ name: 'connector_path_not_exported', count: 2 }],
};

function props(overrides: Partial<XmiExportPanelViewProps> = {}): XmiExportPanelViewProps {
  return {
    busy: false,
    busyMessage: null,
    error: null,
    result: null,
    onExport: vi.fn(),
    onDismissError: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<XmiExportPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(XmiExportPanelView, props(overrides)));
}

describe('XMI export panel', () => {
  it('renders as a sibling section with the interchange eyebrow, the export heading, and the exact primary button label', () => {
    const markup = render();

    expect(markup).toContain('class="generation-panel"');
    expect(markup).toContain('XMI interchange');
    expect(markup).toContain('Export the model as XMI');
    expect(markup).toContain('Export XMI');
  });

  it('states that exporting does not modify the model', () => {
    const markup = render();

    expect(markup).toContain('does not modify');
  });

  it('wires the accessible name from the rendered heading id', () => {
    const markup = render();

    expect(markup).toContain('aria-labelledby="xmi-export-title"');
    expect(markup).toContain('<h2 id="xmi-export-title"');
  });

  it('renders no losses list and no checksum before an export has run', () => {
    const markup = render();

    expect(markup).not.toContain('Reported losses');
    expect(markup).not.toContain('Checksum');
  });

  it('reports the download, the checksum, and the losses after a successful export', () => {
    const markup = render({ result: exportResult });

    expect(markup).toContain('was downloaded');
    expect(markup).toContain(exportResult.checksum);
    expect(markup).toContain('Reported losses');
    expect(markup).toContain('connector_path_not_exported');
    expect(markup).toContain('2 affected');
  });

  it('shows an error notice with a dismiss action when the export fails', () => {
    const markup = render({ error: 'Another editor holds the editing lease for this project.' });

    expect(markup).toContain('notice notice-error');
    expect(markup).toContain('Another editor holds the editing lease for this project.');
    expect(markup).toContain('Dismiss');
  });

  it('shows a busy status while the export request runs', () => {
    const markup = render({ busy: true, busyMessage: 'Exporting…' });

    expect(markup).toContain('role="status"');
    expect(markup).toContain('Exporting…');
    // The primary button must not be clickable while busy.
    expect(markup).toContain('disabled');
  });
});
