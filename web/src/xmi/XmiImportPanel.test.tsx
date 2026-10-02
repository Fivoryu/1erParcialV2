import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../api/client';
import type {
  CommandResult,
  XmiImportResource,
  XmiPreviewResource,
} from '../api/types';
import {
  confirmXmiImport,
  previewXmiFile,
  XmiImportPanelView,
  type XmiImportPanelViewProps,
} from './XmiImportPanel';

const projectId = '22222222-2222-4222-8222-222222222222';
const preview: XmiPreviewResource = {
  counts: {
    classifiers: 3,
    relationships: 2,
    views: 1,
    figurePlacements: 3,
    connectorPlacements: 2,
  },
  losses: [{ name: 'connector_path_not_imported', count: 1 }],
  disagreements: [{
    kind: 'documentation',
    subjectId: '33333333-3333-4333-8333-333333333333',
    semanticValue: 'Semantic note',
    extensionValue: 'EA note',
  }],
  checksum: 'a'.repeat(64),
};

function rejectedCollision(): XmiImportResource {
  const command: CommandResult = {
    operationId: '44444444-4444-4444-8444-444444444444',
    status: 'REJECTED',
    resultRevision: null,
    diagnostics: [{
      code: 'NAME_COLLISION_BLOCKS',
      message: "Classifier 'Customer' already exists in this project.",
      scope: 'classifier',
      id: null,
      reason: 'name.collision',
    }],
    authorizationClassification: null,
  };
  return {
    outcome: 'REJECTED',
    command,
    exchangeId: null,
    losses: preview.losses,
    disagreements: preview.disagreements,
  };
}

function props(overrides: Partial<XmiImportPanelViewProps> = {}): XmiImportPanelViewProps {
  return {
    fileName: null,
    preview: null,
    mode: null,
    importResult: null,
    busy: false,
    busyMessage: null,
    error: null,
    onSelectFile: vi.fn(),
    onSelectMode: vi.fn(),
    onConfirm: vi.fn(),
    onDismissError: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<XmiImportPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(XmiImportPanelView, props(overrides)));
}

describe('XMI import panel', () => {
  it('reads a selected file as raw bytes, previews it, and renders the returned counts and losses', async () => {
    const rawBytes = Uint8Array.from([0x3c, 0x78, 0x6d, 0x69, 0x3e, 0xe9]);
    const arrayBuffer = new ArrayBuffer(rawBytes.length);
    new Uint8Array(arrayBuffer).set(rawBytes);
    const file = { arrayBuffer: vi.fn().mockResolvedValue(arrayBuffer) } as unknown as File;
    const previewXmi = vi.fn<Pick<ApiClient, 'previewXmi'>['previewXmi']>().mockResolvedValue(preview);

    const loaded = await previewXmiFile({ previewXmi }, projectId, file);
    const markup = render({ fileName: 'model.xmi', preview: loaded.preview });

    expect(file.arrayBuffer).toHaveBeenCalledOnce();
    expect(loaded.fileBase64).toBe('PHhtaT7p');
    expect(previewXmi).toHaveBeenCalledWith(projectId, { fileBase64: 'PHhtaT7p' });
    expect(markup).toContain('Preview only');
    expect(markup).toContain('<dt>Classifiers</dt><dd>3</dd>');
    expect(markup).toContain('<dt>Relationships</dt><dd>2</dd>');
    expect(markup).toContain('<dt>Views</dt><dd>1</dd>');
    expect(markup).toContain('connector_path_not_imported');
    expect(markup).toContain('1 affected');
    expect(markup).toContain('Semantic note');
    expect(markup).toContain('EA note');
  });

  it('confirms with the preview checksum, original base64, and user-selected mode', async () => {
    const importXmi = vi.fn<Pick<ApiClient, 'importXmi'>['importXmi']>()
      .mockResolvedValue(rejectedCollision());

    await confirmXmiImport(
      { importXmi },
      projectId,
      'PHhtaT7p',
      preview,
      'AGREGAR',
      'xmi-import-attempt-1',
    );

    expect(importXmi).toHaveBeenCalledWith(projectId, {
      fileBase64: 'PHhtaT7p',
      checksum: preview.checksum,
      mode: 'AGREGAR',
      idempotencyKey: 'xmi-import-attempt-1',
    });
  });

  it('surfaces the classifier name from an addition-collision diagnostic', () => {
    const markup = render({ fileName: 'model.xmi', preview, mode: 'AGREGAR', importResult: rejectedCollision() });

    expect(markup).toContain('Import outcome: REJECTED');
    expect(markup).toContain('name collision');
    // React escapes the apostrophes in the backend diagnostic when rendering static markup, so unescape
    // them before asserting: the intent is that the backend's own message reaches the user, not that the
    // renderer happens to choose a particular HTML entity for an apostrophe.
    const text = markup.replace(/&#x27;/g, "'");
    expect(text).toContain("Classifier 'Customer' already exists in this project.");
    expect(markup).not.toContain('The XMI import could not be completed');
  });

  it('warns before confirming that REEMPLAZAR deletes the existing model', () => {
    const markup = render({ fileName: 'model.xmi', preview, mode: 'REEMPLAZAR' });

    expect(markup).toContain('REEMPLAZAR will delete all existing model content');
    expect(markup).toContain('Confirm import and replace existing model');
  });
});
