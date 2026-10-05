import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import FloatingAssistantButton from './FloatingAssistantButton';

describe('FloatingAssistantButton', () => {
  it('renders a circular button with the exact accessible name and dialog semantics', () => {
    const markup = renderToStaticMarkup(
      <FloatingAssistantButton open={false} onOpen={() => undefined} />,
    );
    expect(markup).toContain('class="floating-assistant"');
    expect(markup).toContain('type="button"');
    expect(markup).toContain('aria-haspopup="dialog"');
    expect(markup).toContain('aria-label="Asistente de modelo (IA)"');
    expect(markup).toContain('>IA</button>');
  });

  it('reports aria-expanded="false" when the assistant is closed', () => {
    const markup = renderToStaticMarkup(
      <FloatingAssistantButton open={false} onOpen={() => undefined} />,
    );
    expect(markup).toContain('aria-expanded="false"');
  });

  it('reports aria-expanded="true" when the assistant is open', () => {
    const markup = renderToStaticMarkup(
      <FloatingAssistantButton open={true} onOpen={() => undefined} />,
    );
    expect(markup).toContain('aria-expanded="true"');
  });

  it('carries no inline position before a stored position is known', () => {
    const markup = renderToStaticMarkup(
      <FloatingAssistantButton open={false} onOpen={() => undefined} />,
    );
    expect(markup).not.toContain('left:');
    expect(markup).not.toContain('top:');
  });
});
