import { createElement, isValidElement } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { GenerationReadinessResource, MemberRole, ModelResource, ModelValidationResource } from '../api/types';
import { ReviewPanel, type ReviewPanelProps, type ReviewPanelState } from './ReviewPanel';

const CLASS_ID = '11111111-1111-4111-8111-111111111111';
const UNKNOWN_ID = '99999999-9999-4999-8999-999999999999';
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

const model: ModelResource = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  revision: 4,
  versionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  views: [],
  classifiers: [{
    id: CLASS_ID,
    name: 'Factura',
    kind: 'CLASS',
    isAbstract: false,
    namespaceId: null,
    attributes: [],
    operations: [],
    constraints: [],
  }],
  relationships: [],
};

const cleanValidation: ModelValidationResource = { diagnostics: [] };
const readyReadiness: GenerationReadinessResource = {
  versionId: model.versionId,
  profile: 'Spring Boot PostgreSQL V1',
  ready: true,
  diagnostics: [],
};
const findingsValidation: ModelValidationResource = {
  diagnostics: [
    { code: 'GENERALIZATION_CYCLE', severity: 'ERROR', elementId: CLASS_ID, feature: null, message: 'The class participates in a generalization cycle.' },
    { code: 'UNRESOLVED_TYPE', severity: 'WARNING', elementId: UNKNOWN_ID, feature: 'type', message: 'A type could not be resolved.' },
  ],
};
const blockedReadiness: GenerationReadinessResource = {
  versionId: model.versionId,
  profile: 'Spring Boot PostgreSQL V1',
  ready: false,
  diagnostics: [{
    code: 'GENERATION_BLOCKED', severity: 'ERROR', elementId: CLASS_ID, feature: null,
    message: 'The model cannot be generated.', blocking: true,
  }],
};

function props(state: ReviewPanelState, role: MemberRole | null = 'EDITOR'): ReviewPanelProps {
  return {
    model,
    role,
    state,
    onRunReview: () => undefined,
    onExplain: () => undefined,
    onProposeFix: () => undefined,
  };
}

function render(state: ReviewPanelState, role: MemberRole | null = 'EDITOR'): string {
  return renderToStaticMarkup(createElement(ReviewPanel, props(state, role)));
}

interface ButtonProps {
  children?: ReactNode;
  disabled?: boolean;
  onClick?: () => void;
}

function textContent(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') {
    return String(node);
  }
  if (Array.isArray(node)) {
    return node.map(textContent).join('');
  }
  if (!isValidElement(node)) {
    return '';
  }
  return textContent((node.props as { children?: ReactNode }).children);
}

function findButton(node: ReactNode, label: string): ReactElement<ButtonProps> | null {
  if (Array.isArray(node)) {
    for (const child of node) {
      const match = findButton(child, label);
      if (match) return match;
    }
    return null;
  }
  if (!isValidElement(node)) {
    return null;
  }
  const element = node as ReactElement<ButtonProps>;
  if (element.type === 'button' && textContent(element.props.children) === label) {
    return element;
  }
  return findButton(element.props.children, label);
}

describe('ReviewPanel markup', () => {
  it('renders the loading state and a disabled Run review refresh action', () => {
    const markup = render({ status: 'loading' });

    expect(markup).toContain('class="review-loading"');
    expect(markup).toContain('Cargando la validación UML y la disponibilidad del backend…');
    expect(markup).toContain('Ejecutar revisión');
    expect(markup).toMatch(/review-run-button[^>]*disabled=""/);
  });

  it('renders a clean review with the ready profile and no-issues state', () => {
    const markup = render({ status: 'loaded', validation: cleanValidation, readiness: readyReadiness });

    expect(markup).toContain('No se encontraron problemas.');
    expect(markup).toContain('Errores: 0');
    expect(markup).toContain('Advertencias: 0');
    expect(markup).toContain('Spring Boot PostgreSQL V1');
    expect(markup).toContain('Listo');
    expect(markup).toContain('Las comprobaciones específicas del destino');
  });

  it('renders not-ready status, grouped errors and warnings, blockers, human names and no UUIDs', () => {
    const markup = render({ status: 'loaded', validation: findingsValidation, readiness: blockedReadiness });

    expect(markup).toContain('Errores (1)');
    expect(markup).toContain('Advertencias (1)');
    expect(markup).toContain('GENERALIZATION_CYCLE');
    expect(markup).toContain('Factura');
    expect(markup).toContain('Elemento del diagrama sin identificar');
    expect(markup).toContain('GENERATION_BLOCKED');
    expect(markup).toContain('Diagnósticos bloqueantes (1)');
    expect(markup).toContain('No está listo');
    expect(markup).not.toMatch(UUID_PATTERN);
  });

  it('renders an accessible error state with a refresh action', () => {
    const markup = render({ status: 'error', message: 'The validation service is unavailable.' });

    expect(markup).toContain('role="alert"');
    expect(markup).toContain('The validation service is unavailable.');
    expect(markup).toContain('Ejecutar revisión');
  });

  it('enables AI actions only for editors or owners when findings exist', () => {
    const state: ReviewPanelState = { status: 'loaded', validation: findingsValidation, readiness: blockedReadiness };
    const panelFor = (role: MemberRole) => ReviewPanel({
      ...props(state, role),
    });
    const editor = panelFor('EDITOR');
    const owner = panelFor('OWNER');
    const viewer = panelFor('VIEWER');

    expect(findButton(editor, 'Explicar con IA')?.props.disabled).toBe(false);
    expect(findButton(editor, 'Proponer una corrección')?.props.disabled).toBe(false);
    expect(findButton(owner, 'Explicar con IA')?.props.disabled).toBe(false);
    expect(findButton(owner, 'Proponer una corrección')?.props.disabled).toBe(false);
    expect(findButton(viewer, 'Explicar con IA')?.props.disabled).toBe(true);
    expect(findButton(viewer, 'Proponer una corrección')?.props.disabled).toBe(true);
    expect(render(state, 'VIEWER')).toContain('Las acciones de IA requieren permisos de edición.');
  });

  it('disables AI actions for an editor when there are no findings', () => {
    const panel = ReviewPanel(props({ status: 'loaded', validation: cleanValidation, readiness: readyReadiness }, 'EDITOR'));

    expect(render({ status: 'loaded', validation: cleanValidation, readiness: readyReadiness }, 'EDITOR'))
      .toContain('Las acciones de IA estarán disponibles si la revisión encuentra un problema.');
    expect(findButton(panel, 'Explicar con IA')?.props.disabled).toBe(true);
    expect(findButton(panel, 'Proponer una corrección')?.props.disabled).toBe(true);
  });

  it('passes explicit Spanish prompts to the existing assistant callbacks', () => {
    const onExplain = vi.fn<(prompt: string) => void>();
    const onProposeFix = vi.fn<(prompt: string) => void>();
    const panel = ReviewPanel({
      ...props({ status: 'loaded', validation: findingsValidation, readiness: blockedReadiness }),
      onExplain,
      onProposeFix,
    });
    const explainButton = findButton(panel, 'Explicar con IA');
    const proposeButton = findButton(panel, 'Proponer una corrección');

    expect(explainButton?.props.disabled).toBe(false);
    expect(proposeButton?.props.disabled).toBe(false);
    explainButton?.props.onClick?.();
    proposeButton?.props.onClick?.();

    expect(onExplain).toHaveBeenCalledOnce();
    expect(onProposeFix).toHaveBeenCalledOnce();
    expect(onExplain.mock.calls[0]?.[0]).toContain('Explicá en español');
    expect(onProposeFix.mock.calls[0]?.[0]).toContain('proponé un plan mínimo');
    expect(onExplain.mock.calls[0]?.[0]).toContain('Factura');
    expect(onProposeFix.mock.calls[0]?.[0]).not.toMatch(UUID_PATTERN);
  });
});
