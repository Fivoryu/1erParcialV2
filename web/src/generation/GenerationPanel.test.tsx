import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type {
  DeploymentResource,
  EditingLeaseConflict,
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

const readyRun: GenerationRunResource = {
  ...blockedRun,
  state: 'SUCCEEDED',
  artifactSetHash: 'b'.repeat(64),
  artifactZipHash: 'c'.repeat(64),
  diagnostics: [],
};

const upDeployment: DeploymentResource = {
  deploymentId: '99999999-9999-4999-8999-999999999999',
  state: 'UP',
  targetId: firstTarget.targetId,
  targetKey: firstTarget.targetKey,
  targetName: firstTarget.name,
  runId: readyRun.runId,
  httpHostPort: 19001,
  postgresHostPort: 25433,
  composeProject: 'generated-reporting-service-t0abcde',
  operatorId: 'host-a:1',
  failureCode: null,
  failureMessage: null,
  requestedAt: '2026-09-30T12:00:00Z',
  startedAt: '2026-09-30T12:00:01Z',
  finishedAt: '2026-09-30T12:00:30Z',
};

// The operator's own words can name a working directory, and that must not reach the screen.
const failedDeployment: DeploymentResource = {
  ...upDeployment,
  state: 'FAILED',
  composeProject: null,
  failureCode: 'COMPOSE_UP_FAILED',
  failureMessage: String.raw`docker compose up failed with exit 1: no such file C:\Users\HP\work\abc`,
};

function props(overrides: Partial<GenerationPanelViewProps> = {}): GenerationPanelViewProps {
  return {
    targets: [],
    profiles: [profile],
    selectedTargetId: null,
    selectedProfileId: profile.profileId,
    run: null,
    deployment: null,
    phoneUrl: null,
    editorOnLocalhost: false,
    busy: false,
    error: null,
    notice: null,
    onSelectTarget: vi.fn(),
    onSelectProfile: vi.fn(),
    onCreateTarget: vi.fn(),
    onGenerate: vi.fn(),
    onDownload: vi.fn(),
    onDeploy: vi.fn(),
    onStopDeployment: vi.fn(),
    onDismiss: vi.fn(),
    ...overrides,
  };
}

function render(overrides: Partial<GenerationPanelViewProps> = {}): string {
  return renderToStaticMarkup(createElement(GenerationPanelView, props(overrides)));
}

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const TECHNICAL_IDENTIFIERS = [
  'deployment_id',
  'deploymentId',
  'composeProject',
  'compose_project',
  'operatorId',
  'operator_id',
  'httpHostPort',
  'postgresHostPort',
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
  it('shows the editing lease notice only when a conflict exists', () => {
    const conflict: EditingLeaseConflict = {
      editorDisplayName: 'Alex Rivera',
      targetType: 'CLASSIFIER',
      targetId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      reclaimable: true,
      retryAfterSeconds: 0,
    };
    const withConflict = render({ editingLeaseConflict: conflict });

    expect(withConflict).toContain('Alex Rivera');
    expect(withConflict).toContain('Reclaim control');

    const withoutConflict = render({ editingLeaseConflict: null });
    expect(withoutConflict).not.toContain('editing-lease-notice');
  });

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

  it('offers to deploy a finished run and warns that the backend has no authentication', () => {
    const markup = render({ targets: [firstTarget], selectedTargetId: firstTarget.targetId, run: readyRun });

    expect(markup).toContain('Deploy backend');
    expect(markup).not.toContain('disabled');
    expect(markup).toContain('no authentication');
  });

  it('does not offer to deploy a run that has not finished', () => {
    const markup = render({ targets: [firstTarget], selectedTargetId: firstTarget.targetId, run: blockedRun });

    expect(markup).toContain('Deploy backend');
    expect(markup).toContain('disabled');
  });

  it('shows the address another device uses, built from the host the browser already used', () => {
    const markup = render({
      targets: [firstTarget],
      selectedTargetId: firstTarget.targetId,
      run: readyRun,
      deployment: upDeployment,
      phoneUrl: 'http://192.168.0.11:19001',
    });

    expect(markup).toContain('Running');
    expect(markup).toContain('http://192.168.0.11:19001');
    expect(markup).toContain('From another device on the same network');
    expect(markup).not.toContain('only works here');
  });

  it('says the shown address only works here when the editor was opened through loopback', () => {
    const markup = render({
      targets: [firstTarget],
      selectedTargetId: firstTarget.targetId,
      run: readyRun,
      deployment: upDeployment,
      phoneUrl: 'http://localhost:19001',
      editorOnLocalhost: true,
    });

    expect(markup).toContain('http://localhost:19001');
    expect(markup).toContain('only works here');
  });

  it('states a failed deployment in product language and never shows the operator path', () => {
    const markup = render({
      targets: [firstTarget],
      selectedTargetId: firstTarget.targetId,
      run: readyRun,
      deployment: failedDeployment,
      phoneUrl: 'http://192.168.0.11:19001',
    });

    expect(markup).toContain('Could not start');
    expect(markup).toContain('Docker Compose could not start the stack');
    expect(markup).not.toContain(String.raw`C:\Users`);
    expect(markup).not.toContain('exit 1');
  });

  it('offers to stop a deployment that holds a stack, and not one that already stopped', () => {
    const running = render({
      targets: [firstTarget], selectedTargetId: firstTarget.targetId, run: readyRun,
      deployment: upDeployment, phoneUrl: 'http://192.168.0.11:19001',
    });
    expect(running).toContain('Stop backend');

    const stopped = render({
      targets: [firstTarget], selectedTargetId: firstTarget.targetId, run: readyRun,
      deployment: { ...upDeployment, state: 'STOPPED' },
    });
    expect(stopped).toContain('Stopped');
    expect(stopped).not.toContain('Stop backend');
  });

  it('waits for the operator while a deployment is on its way', () => {
    const markup = render({
      targets: [firstTarget], selectedTargetId: firstTarget.targetId, run: readyRun,
      deployment: { ...upDeployment, state: 'PENDING', composeProject: null },
    });

    expect(markup).toContain('Waiting for the operator');
    expect(markup).toContain('picks this up on its own');
  });

  it('never puts a deployment identifier, project name or host path on the screen', () => {
    // Both shapes matter: a running deployment is the one that carries a Compose project name, and a failed
    // one is the one that carries the operator's message. Checking only one of them hides the other.
    for (const deployment of [upDeployment, failedDeployment]) {
      const markup = render({
        targets: [firstTarget], selectedTargetId: firstTarget.targetId, run: readyRun,
        deployment, phoneUrl: 'http://192.168.0.11:19001',
      });

      expect(markup).not.toMatch(UUID_PATTERN);
      for (const identifier of TECHNICAL_IDENTIFIERS) {
        expect(markup, `${identifier} reached the screen as ${deployment.state}`).not.toContain(identifier);
      }
      expect(markup).not.toContain('generated-reporting-service-t0abcde');
      expect(markup).not.toContain('host-a:1');
      expect(markup).not.toContain(String.raw`C:\Users`);
    }
  });

  it('still shows a running deployment after a reload, when no run is on screen', () => {
    const markup = render({
      targets: [firstTarget],
      selectedTargetId: firstTarget.targetId,
      run: null,
      deployment: upDeployment,
      phoneUrl: 'http://192.168.0.11:19001',
    });

    // The run that was generated lives in memory and a reload loses it, but the backend keeps running and the
    // user has to be able to see it and stop it.
    expect(markup).toContain('Running');
    expect(markup).toContain('http://192.168.0.11:19001');
    expect(markup).toContain('Stop backend');
    expect(markup).not.toContain('Deploy backend');
  });
});
