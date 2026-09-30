import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type {
  GenerationProfileResource,
  GenerationRunResource,
  GenerationTargetResource,
} from '../api/types';
import { GenerationPanelView, type GenerationPanelViewProps } from './GenerationPanel';

const firstTarget: GenerationTargetResource = {
  targetId: '11111111-1111-4111-8111-111111111111',
  modelId: '22222222-2222-4222-8222-222222222222',
  targetKey: 'reporting',
  name: 'Reporting service',
  databaseEngine: 'POSTGRESQL',
  databaseName: 'reporting_db',
  databaseSchema: 'public',
  connectionConfigRef: null,
  configurationRevision: 1,
  createdAt: '2026-09-30T12:00:00Z',
  updatedAt: '2026-09-30T12:00:00Z',
};

const secondTarget: GenerationTargetResource = {
  ...firstTarget,
  targetId: '33333333-3333-4333-8333-333333333333',
  targetKey: 'billing',
  name: 'Billing service',
  databaseName: 'billing_db',
};

const profile: GenerationProfileResource = {
  profileId: '44444444-4444-4444-8444-444444444444',
  profileKey: 'spring-boot-postgresql',
  name: 'Spring Boot PostgreSQL',
  profileRevision: 1,
  templateId: '55555555-5555-4555-8555-555555555555',
  templateRevision: 1,
};

const blockedRun: GenerationRunResource = {
  runId: '66666666-6666-4666-8666-666666666666',
  modelId: '22222222-2222-4222-8222-222222222222',
  versionId: '77777777-7777-4777-8777-777777777777',
  modelRevision: 9,
  targetId: firstTarget.targetId,
  targetKey: firstTarget.targetKey,
  targetConfigRevision: 1,
  profileId: profile.profileId,
  profileKey: profile.profileKey,
  profileRevision: 1,
  templateId: profile.templateId,
  templateRevision: 1,
  state: 'FAILED',
  inputSnapshotHash: 'a'.repeat(64),
  artifactSetHash: null,
  artifactZipHash: null,
  artifactZipUri: null,
  startedAt: '2026-09-30T12:00:00Z',
  finishedAt: '2026-09-30T12:00:01Z',
  diagnostics: [{
    code: 'UNSUPPORTED_UML_FEATURE',
    severity: 'ERROR',
    elementId: '88888888-8888-4888-8888-888888888888',
    feature: 'GENERALIZATION',
    message: 'The Increment 2 subset does not map relationship feature \'GENERALIZATION\'.',
    blocking: true,
  }],
  verification: null,
};

function props(overrides: Partial<GenerationPanelViewProps> = {}): GenerationPanelViewProps {
  return {
    targets: [],
    profiles: [profile],
    selectedTargetId: null,
    selectedProfileId: profile.profileId,
    run: null,
    busy: false,
    error: null,
    notice: null,
    onSelectTarget: vi.fn(),
    onSelectProfile: vi.fn(),
    onCreateTarget: vi.fn(),
    onGenerate: vi.fn(),
    onDownload: vi.fn(),
    onDismiss: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<GenerationPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(GenerationPanelView, props(overrides)));
}

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const TECHNICAL_IDENTIFIERS = [
  'run_id',
  'runId',
  'target_id',
  'targetId',
  'profile_id',
  'profileId',
  'template_id',
  'templateId',
  'model_version',
  'generation_run',
  'artifact_set_hash',
  'input_snapshot_hash',
  'spring-boot-postgresql',
];

describe('generation panel, presentational surface', () => {
  it('lists every destination the model has, not just the first one', () => {
    const markup = render({
      targets: [firstTarget, secondTarget],
      selectedTargetId: secondTarget.targetId,
      // No profile selected, so the only pressed option in the markup is a destination.
      selectedProfileId: null,
    });

    expect(markup).toContain('Reporting service · reporting_db');
    expect(markup).toContain('Billing service · billing_db');
    expect(markup.match(/aria-pressed="true"/g)).toHaveLength(1);
    expect(markup).toContain('aria-pressed="false"');
  });

  it('says which destination is missing when the model has none', () => {
    expect(render()).toContain('This model has no destination yet');
  });

  it('shows a blocking diagnostic with its named feature, never as a generic error', () => {
    const markup = render({ targets: [firstTarget], selectedTargetId: firstTarget.targetId, run: blockedRun });

    expect(markup).toContain('Blocked by the model');
    expect(markup).toContain('Generalization (inheritance)');
    expect(markup).toContain('Remove or replace it, then generate again.');
    expect(markup).not.toContain('Something went wrong');
  });

  it('never puts an identifier, a key or a table name on the screen', () => {
    const markup = render({
      targets: [firstTarget, secondTarget],
      selectedTargetId: firstTarget.targetId,
      run: blockedRun,
      notice: 'Reporting service is ready as a destination.',
    });

    expect(markup).not.toMatch(UUID_PATTERN);
    for (const identifier of TECHNICAL_IDENTIFIERS) {
      expect(markup).not.toContain(identifier);
    }
    // The raw backend message names an internal element shape; the panel rebuilds it instead.
    expect(markup).not.toContain("does not map relationship feature");
  });

  it('offers the download only once the run is complete and unblocked', () => {
    const ready = render({
      targets: [firstTarget],
      selectedTargetId: firstTarget.targetId,
      run: {
        ...blockedRun,
        state: 'SUCCEEDED',
        artifactSetHash: 'b'.repeat(64),
        artifactZipHash: 'c'.repeat(64),
        diagnostics: [],
      },
    });
    expect(ready).toContain('Ready to download');
    expect(ready).toContain('Download ZIP');

    const blocked = render({ targets: [firstTarget], selectedTargetId: firstTarget.targetId, run: blockedRun });
    expect(blocked).not.toContain('Download ZIP');
  });

  it('disables generation until a destination and a profile are both chosen', () => {
    expect(render({ profiles: [profile], selectedTargetId: null })).toContain('disabled');
    const enabled = render({ profiles: [profile], targets: [firstTarget], selectedTargetId: firstTarget.targetId });
    expect(enabled).toContain('Generate backend');
  });
});
