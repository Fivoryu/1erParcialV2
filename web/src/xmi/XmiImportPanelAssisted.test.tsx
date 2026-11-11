import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../api/client';
import type { XmiImportIssueResource, XmiPreviewResource } from '../api/types';
import { confirmXmiImport, XmiImportPanelView, type XmiImportPanelViewProps } from './XmiImportPanel';

const blocker: XmiImportIssueResource = {
  code: 'NAME_COLLISION',
  severity: 'BLOCKER',
  subject: 'id-1',
  name: 'Customer',
  message: 'Ya existe Customer',
  options: ['RENAME', 'SKIP'],
};
const warning: XmiImportIssueResource = {
  code: 'UNRESOLVED_TYPE',
  severity: 'WARNING',
  subject: 'id-2',
  name: 'Order',
  message: 'Tipo sin resolver',
  options: [],
};

function previewWith(overrides: Partial<XmiPreviewResource>): XmiPreviewResource {
  return {
    counts: { classifiers: 1, relationships: 0, views: 1, figurePlacements: 0, connectorPlacements: 0 },
    losses: [],
    disagreements: [],
    checksum: 'a'.repeat(64),
    issues: [],
    repairs: [],
    clean: true,
    ...overrides,
  };
}

function render(overrides: Partial<XmiImportPanelViewProps>): string {
  return renderToStaticMarkup(createElement(XmiImportPanelView, {
    fileName: 'm.xmi',
    preview: null,
    mode: 'AGREGAR',
    importResult: null,
    busy: false,
    busyMessage: null,
    error: null,
    onSelectFile: vi.fn(),
    onSelectMode: vi.fn(),
    onConfirm: vi.fn(),
    onDismissError: vi.fn(),
    ...overrides,
  }));
}

describe('assisted XMI import panel', () => {
  it('lists blockers, warnings and repairs and flags pending problems and the collision', () => {
    const markup = render({
      preview: previewWith({
        issues: [blocker, warning],
        repairs: [{ code: 'DUP_ID', message: 'Se reparó un id', count: 2 }],
        clean: false,
      }),
    });
    expect(markup).toContain('Bloqueantes');
    expect(markup).toContain('Avisos');
    expect(markup).toContain('Ya existe Customer');
    expect(markup).toContain('Tipo sin resolver');
    expect(markup).toContain('Reparado automáticamente');
    expect(markup).toContain('Quedan problemas por resolver');
    expect(markup).toContain('conflictos de nombres');
    expect(markup).toContain('Resolver con IA');
    expect(markup).toContain('Renombrar');
    expect(markup).toContain('Omitir');
  });

  it('shows the clean state, keeps import enabled and blocks it while blockers remain', () => {
    const cleanMarkup = render({ preview: previewWith({}) });
    expect(cleanMarkup).toContain('El resultado quedaría limpio');
    expect(cleanMarkup).not.toMatch(/<button[^>]*disabled=""[^>]*>Confirmar importación/);

    const blockedMarkup = render({ preview: previewWith({ issues: [blocker], clean: false }) });
    expect(blockedMarkup).toMatch(/<button[^>]*disabled=""[^>]*>Confirmar importación/);
  });

  it('shows the AI or rules badge with the explanation and the chosen rename', () => {
    const ai = render({
      preview: previewWith({ issues: [blocker], clean: false }),
      choices: { 'id-1': { action: 'RENAME', newName: 'Customer (2)' } },
      suggestion: { resolutions: [], source: 'AI', explanation: 'Renombré por contexto' },
    });
    expect(ai).toContain('Sugerido por IA');
    expect(ai).toContain('Renombré por contexto');
    expect(ai).toContain('value="Customer (2)"');
    const rules = render({
      preview: previewWith({ issues: [blocker], clean: false }),
      suggestion: { resolutions: [], source: 'RULES', explanation: 'x' },
    });
    expect(rules).toContain('Sugerido por reglas');
  });

  it('disables the AI button while busy or without a mode', () => {
    const busy = render({ preview: previewWith({ issues: [blocker] }), busy: true, onSuggest: vi.fn() });
    expect(busy).toMatch(/<button[^>]*disabled=""[^>]*>Resolver con IA/);
    const idle = render({ preview: previewWith({ issues: [blocker] }), onSuggest: vi.fn() });
    expect(idle).not.toMatch(/<button[^>]*disabled=""[^>]*>Resolver con IA/);
  });

  it('sends the chosen resolutions in the import request', async () => {
    const importXmi = vi.fn<Pick<ApiClient, 'importXmi'>['importXmi']>().mockRejectedValue(new Error('stop'));
    const resolutions = [{ subject: 'id-1', action: 'SKIP' as const }];
    await confirmXmiImport({ importXmi }, 'p', 'b64', previewWith({}), 'AGREGAR', 'k', resolutions).catch(() => undefined);
    expect(importXmi).toHaveBeenCalledWith('p', expect.objectContaining({ resolutions }));
  });
});
