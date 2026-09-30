import { describe, expect, it } from 'vitest';
import type { GenerationDiagnosticResource, GenerationRunResource } from '../api/types';
import {
  blockingDiagnostics,
  canDownloadArchive,
  describeDiagnostic,
  describeRunState,
  featureLabel,
  isTerminalState,
  suggestedTargetKey,
  targetOptionLabel,
  uniqueTargetKey,
  withoutIdentifiers,
} from './presentation';

const runBase: GenerationRunResource = {
  runId: '11111111-1111-4111-8111-111111111111',
  modelId: '22222222-2222-4222-8222-222222222222',
  versionId: '33333333-3333-4333-8333-333333333333',
  modelRevision: 9,
  targetId: '44444444-4444-4444-8444-444444444444',
  targetKey: 'reporting',
  targetConfigRevision: 1,
  profileId: '55555555-5555-4555-8555-555555555555',
  profileKey: 'spring-boot-postgresql',
  profileRevision: 1,
  templateId: '66666666-6666-4666-8666-666666666666',
  templateRevision: 1,
  state: 'SUCCEEDED',
  inputSnapshotHash: 'a'.repeat(64),
  artifactSetHash: 'b'.repeat(64),
  artifactZipHash: 'c'.repeat(64),
  artifactZipUri: 'artifact/set.zip',
  startedAt: '2026-09-30T12:00:00Z',
  finishedAt: '2026-09-30T12:00:01Z',
  diagnostics: [],
  verification: null,
};

describe('generation product language', () => {
  it('names the feature of a blocking diagnostic instead of showing a generic error', () => {
    const diagnostic = describeDiagnostic({
      code: 'UNSUPPORTED_UML_FEATURE',
      severity: 'ERROR',
      elementId: '77777777-7777-4777-8777-777777777777',
      feature: 'GENERALIZATION',
      message: "The Increment 2 subset does not map relationship feature 'GENERALIZATION'.",
      blocking: true,
    });

    expect(diagnostic.blocking).toBe(true);
    expect(diagnostic.feature).toBe('Generalization (inheritance)');
    expect(diagnostic.detail).toContain('Generalization (inheritance)');
    expect(diagnostic.detail).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/i);
  });

  it('never carries an identifier through, even from a message that is full of them', () => {
    const raw = 'Type reference 88888888-8888-4888-8888-888888888888 cannot be resolved in the input snapshot.';
    expect(withoutIdentifiers(raw)).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/i);

    const unknownCode = describeDiagnostic({
      code: 'A_CODE_THIS_PANEL_DOES_NOT_KNOW',
      severity: 'ERROR',
      elementId: '88888888-8888-4888-8888-888888888888',
      feature: 'primitiveTypeMappings',
      message: raw,
      blocking: true,
    });
    expect(unknownCode.feature).toBe('Primitive type mapping');
    expect(unknownCode.detail).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/i);
  });

  it('humanizes an unmapped feature code rather than hiding it', () => {
    expect(featureLabel('someNewKind')).toBe('Some New Kind');
    expect(featureLabel('')).toBe('Unsupported model feature');
  });

  it('offers the archive only for a complete run with no blocking diagnostic', () => {
    expect(canDownloadArchive(null)).toBe(false);
    expect(canDownloadArchive(runBase)).toBe(true);
    expect(canDownloadArchive({ ...runBase, state: 'FAILED' })).toBe(false);
    expect(canDownloadArchive({ ...runBase, artifactZipHash: null })).toBe(false);
    expect(canDownloadArchive({
      ...runBase,
      diagnostics: [{
        code: 'UNSUPPORTED_UML_FEATURE',
        severity: 'ERROR',
        elementId: null,
        feature: 'DEPENDENCY',
        message: 'unsupported',
        blocking: true,
      }],
    })).toBe(false);
  });

  it('reports blocking diagnostics only, in product language', () => {
    const diagnostics: GenerationDiagnosticResource[] = [
      {
        code: 'UNSUPPORTED_UML_FEATURE',
        severity: 'ERROR',
        elementId: null,
        feature: 'DEPENDENCY',
        message: 'unsupported',
        blocking: true,
      },
      {
        code: 'SOMETHING_INFORMATIVE',
        severity: 'INFO',
        elementId: null,
        feature: 'namespace',
        message: 'informative',
        blocking: false,
      },
    ];
    const withBoth: GenerationRunResource = { ...runBase, diagnostics };
    expect(blockingDiagnostics(withBoth)).toHaveLength(1);
    expect(blockingDiagnostics(withBoth)[0]?.feature).toBe('Dependency');
    expect(blockingDiagnostics(null)).toEqual([]);
  });

  it('describes every run state and knows which ones are terminal', () => {
    expect(describeRunState('PENDING')).toBe('Queued');
    expect(describeRunState('RUNNING')).toBe('Generating…');
    expect(describeRunState('SUCCEEDED')).toBe('Ready to download');
    expect(describeRunState('FAILED')).toBe('Blocked by the model');
    expect(isTerminalState('SUCCEEDED')).toBe(true);
    expect(isTerminalState('FAILED')).toBe(true);
    expect(isTerminalState('RUNNING')).toBe(false);
  });

  it('derives a stable destination key and keeps it unique inside one model', () => {
    expect(suggestedTargetKey('Reporting service')).toBe('reporting_service');
    expect(suggestedTargetKey('  ')).toBe('target');
    expect(uniqueTargetKey('reporting', [])).toBe('reporting');
    expect(uniqueTargetKey('reporting', ['reporting'])).toBe('reporting_2');
    expect(uniqueTargetKey('reporting', ['reporting', 'reporting_2'])).toBe('reporting_3');
  });

  it('labels a destination with its name and database, not with an identifier', () => {
    expect(targetOptionLabel({
      targetId: '99999999-9999-4999-8999-999999999999',
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
    })).toBe('Reporting service · reporting_db');
  });
});
