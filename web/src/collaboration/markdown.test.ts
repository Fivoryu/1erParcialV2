import { describe, expect, it } from 'vitest';
import {
  parseMarkdown,
  type MarkdownBlock,
  type MarkdownInline,
  type MarkdownListBlock,
} from './markdown';

function paragraphInline(source: string): MarkdownInline[] {
  const blocks = parseMarkdown(source);
  if (blocks.length !== 1 || blocks[0]?.type !== 'paragraph') {
    throw new Error('Expected one paragraph.');
  }
  return blocks[0].children;
}

function inlineText(nodes: MarkdownInline[]): string {
  return nodes.map((node) => {
    switch (node.type) {
      case 'text': return node.text;
      case 'lineBreak': return '\n';
      case 'inlineCode': return node.text;
      case 'strong':
      case 'emphasis': return inlineText(node.children);
    }
  }).join('');
}

function listText(block: MarkdownListBlock): string {
  return block.items.map((item) => [
    inlineText(item.children),
    ...(item.sublists ?? []).map(listText),
  ].filter(Boolean).join('\n')).join('\n');
}

function blockText(block: MarkdownBlock): string {
  switch (block.type) {
    case 'paragraph':
    case 'heading': return inlineText(block.children);
    case 'codeBlock': return block.text;
    case 'blockquote': return block.children.map(blockText).join('\n');
    case 'list': return listText(block);
    case 'table': return [...block.headers, ...block.rows.flat()].map(inlineText).join('|');
    case 'horizontalRule': return '';
  }
}

describe('parseMarkdown', () => {
  it('parses bold markers with asterisks and underscores', () => {
    expect(paragraphInline('**bold** and __also bold__')).toEqual([
      { type: 'strong', children: [{ type: 'text', text: 'bold' }] },
      { type: 'text', text: ' and ' },
      { type: 'strong', children: [{ type: 'text', text: 'also bold' }] },
    ]);
  });

  it('parses italic markers with asterisks and underscores', () => {
    expect(paragraphInline('*italic* and _also italic_')).toEqual([
      { type: 'emphasis', children: [{ type: 'text', text: 'italic' }] },
      { type: 'text', text: ' and ' },
      { type: 'emphasis', children: [{ type: 'text', text: 'also italic' }] },
    ]);
  });

  it('parses inline code without interpreting its contents', () => {
    expect(paragraphInline('Use `a < b` here.')).toEqual([
      { type: 'text', text: 'Use ' },
      { type: 'inlineCode', text: 'a < b' },
      { type: 'text', text: ' here.' },
    ]);
  });

  it('parses fenced code blocks and preserves their contents', () => {
    expect(parseMarkdown('```ts\nconst total = 2;\n```')).toEqual([
      { type: 'codeBlock', text: 'const total = 2;' },
    ]);
  });

  it('parses ATX headings from level one through six', () => {
    const headings = parseMarkdown('# One\n## Two\n### Three\n#### Four\n##### Five\n###### Six');
    expect(headings.map((block) => block.type === 'heading' ? block.level : null)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(headings.map(blockText)).toEqual(['One', 'Two', 'Three', 'Four', 'Five', 'Six']);
  });

  it('parses unordered and ordered lists', () => {
    const blocks = parseMarkdown('- first\n* second\n+ third\n\n1. one\n2. two');
    expect(blocks).toEqual([
      {
        type: 'list',
        ordered: false,
        items: [
          { children: [{ type: 'text', text: 'first' }] },
          { children: [{ type: 'text', text: 'second' }] },
          { children: [{ type: 'text', text: 'third' }] },
        ],
      },
      {
        type: 'list',
        ordered: true,
        items: [
          { children: [{ type: 'text', text: 'one' }] },
          { children: [{ type: 'text', text: 'two' }] },
        ],
      },
    ]);
  });

  it('parses four-space unordered bullets as a nested list under their parent', () => {
    expect(parseMarkdown('* first\n    * nested\n* last')).toEqual([
      {
        type: 'list',
        ordered: false,
        items: [
          {
            children: [{ type: 'text', text: 'first' }],
            sublists: [{
              ordered: false,
              items: [{ children: [{ type: 'text', text: 'nested' }] }],
            }],
          },
          { children: [{ type: 'text', text: 'last' }] },
        ],
      },
    ]);
    expect(blockText(parseMarkdown('* first\n    * nested\n* last')[0]!)).toBe('first\nnested\nlast');
  });

  it('parses ordered children as a nested ordered list', () => {
    expect(parseMarkdown('- parent\n    1. first\n    2. second')).toEqual([{
      type: 'list',
      ordered: false,
      items: [{
        children: [{ type: 'text', text: 'parent' }],
        sublists: [{
          ordered: true,
          items: [
            { children: [{ type: 'text', text: 'first' }] },
            { children: [{ type: 'text', text: 'second' }] },
          ],
        }],
      }],
    }]);
  });

  it('parses list nesting recursively across two levels', () => {
    expect(parseMarkdown('- parent\n  - child\n    - grandchild')).toEqual([{
      type: 'list',
      ordered: false,
      items: [{
        children: [{ type: 'text', text: 'parent' }],
        sublists: [{
          ordered: false,
          items: [{
            children: [{ type: 'text', text: 'child' }],
            sublists: [{
              ordered: false,
              items: [{ children: [{ type: 'text', text: 'grandchild' }] }],
            }],
          }],
        }],
      }],
    }]);
  });

  it('starts a separate list when item types change at the same level', () => {
    expect(parseMarkdown('- unordered\n1. ordered\n+ unordered again')).toEqual([
      {
        type: 'list',
        ordered: false,
        items: [{ children: [{ type: 'text', text: 'unordered' }] }],
      },
      {
        type: 'list',
        ordered: true,
        items: [{ children: [{ type: 'text', text: 'ordered' }] }],
      },
      {
        type: 'list',
        ordered: false,
        items: [{ children: [{ type: 'text', text: 'unordered again' }] }],
      },
    ]);
  });

  it('parses blockquotes', () => {
    expect(parseMarkdown('> first line\n> second line')).toEqual([
      {
        type: 'blockquote',
        children: [{
          type: 'paragraph',
          children: [
            { type: 'text', text: 'first line' },
            { type: 'lineBreak' },
            { type: 'text', text: 'second line' },
          ],
        }],
      },
    ]);
  });

  it('parses horizontal rules', () => {
    expect(parseMarkdown('---')).toEqual([{ type: 'horizontalRule' }]);
  });

  it('parses a pipe table only when followed by a separator row', () => {
    expect(parseMarkdown('| Name | Type |\n|---|---|\n| id | UUID |')).toEqual([{
      type: 'table',
      headers: [
        [{ type: 'text', text: 'Name' }],
        [{ type: 'text', text: 'Type' }],
      ],
      rows: [[
        [{ type: 'text', text: 'id' }],
        [{ type: 'text', text: 'UUID' }],
      ]],
    }]);
  });

  it('preserves paragraph line breaks and separates paragraphs at blank lines', () => {
    const blocks = parseMarkdown('first line\nsecond line\n\nnext paragraph');
    expect(blocks).toEqual([
      {
        type: 'paragraph',
        children: [
          { type: 'text', text: 'first line' },
          { type: 'lineBreak' },
          { type: 'text', text: 'second line' },
        ],
      },
      { type: 'paragraph', children: [{ type: 'text', text: 'next paragraph' }] },
    ]);
  });

  it('keeps unmatched inline and fenced markers literal', () => {
    const inline = '**unfinished and _also unfinished';
    expect(inlineText(paragraphInline(inline))).toBe(inline);
    expect(blockText(parseMarkdown('```unclosed\nstill text')[0]!)).toBe('```unclosed\nstill text');
  });

  it('keeps raw HTML as plain text for React to escape', () => {
    const rawHtml = '<script>alert(1)</script><img src=x onerror=alert(1)>';
    expect(paragraphInline(rawHtml)).toEqual([{ type: 'text', text: rawHtml }]);
  });
});
