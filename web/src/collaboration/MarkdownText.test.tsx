import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import MarkdownText from './MarkdownText';

function render(text: string): string {
  return renderToStaticMarkup(createElement(MarkdownText, { text }));
}

describe('MarkdownText', () => {
  it('renders the supported markdown subset as semantic elements', () => {
    const markup = render([
      '**bold** and *italic* with `code`',
      '',
      '### Heading',
      '',
      '- first item',
      '- second item',
      '',
      '> quoted text',
      '',
      '| Name | Type |',
      '|---|---|',
      '| id | UUID |',
    ].join('\n'));

    expect(markup).toContain('<strong>bold</strong>');
    expect(markup).toContain('<em>italic</em>');
    expect(markup).toContain('<code>code</code>');
    expect(markup).toContain('<h3>Heading</h3>');
    expect(markup).toContain('<li>first item</li>');
    expect(markup).toContain('<blockquote><p>quoted text</p></blockquote>');
    expect(markup).toContain('<table>');
    expect(markup).toContain('<th scope="col">Name</th>');
    expect(markup).toContain('<td>UUID</td>');
    expect(markup).not.toContain('**');
    expect(markup).not.toContain('`');
    expect(markup).not.toContain('|');
  });

  it('renders indented list markers as nested items without literal markers', () => {
    const markup = render('* first\n    * nested\n* last');

    expect(markup).toContain('<li>first<ul><li>nested</li></ul></li>');
    expect(markup).toContain('<li>last</li>');
    expect(markup).not.toContain('*');
    expect(markup.replace(/<[^>]*>/g, '')).not.toMatch(/(?:^|\s)(?:[-+]|\d+\.)\s/);
  });

  it('renders recursive ordered and unordered sub-lists with formatted, escaped content', () => {
    const markup = render([
      '- **parent**',
      '    - child `code`',
      '        1. **grandchild** <script>alert(1)</script>',
      '- sibling',
    ].join('\n'));

    expect(markup).toContain(
      '<li><strong>parent</strong><ul><li>child <code>code</code><ol><li><strong>grandchild</strong> &lt;script&gt;alert(1)&lt;/script&gt;</li></ol></li></ul></li>',
    );
    expect(markup).toContain('<li>sibling</li>');
    expect(markup).not.toContain('<script');
    expect(markup).not.toContain('*');
    expect(markup.replace(/<[^>]*>/g, '')).not.toMatch(/(?:^|\s)(?:[-+]|\d+\.)\s/);
  });

  it('escapes script and event-handler HTML as text without creating elements', () => {
    const markup = render('<script>alert(1)</script><img src=x onerror=alert(1)>');

    expect(markup).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(markup).toContain('&lt;img src=x onerror=alert(1)&gt;');
    expect(markup).not.toContain('<script');
    expect(markup).not.toMatch(/<img\b/i);
  });

  it('does not turn javascript URLs into links', () => {
    const markup = render('[run](javascript:alert(1))');

    expect(markup).not.toContain('<a');
    expect(markup).toContain('[run](javascript:alert(1))');
  });
});
