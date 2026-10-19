import type { ReactNode } from 'react';
import {
  parseMarkdown,
  type MarkdownBlock,
  type MarkdownInline,
  type MarkdownListBlock,
} from './markdown';

function renderInline(nodes: MarkdownInline[]): ReactNode[] {
  return nodes.map((node, index) => {
    switch (node.type) {
      case 'text':
        return node.text;
      case 'lineBreak':
        return <br key={index} />;
      case 'inlineCode':
        return <code key={index}>{node.text}</code>;
      case 'strong':
        return <strong key={index}>{renderInline(node.children)}</strong>;
      case 'emphasis':
        return <em key={index}>{renderInline(node.children)}</em>;
    }
  });
}

function renderList(block: MarkdownListBlock, key: number | string): ReactNode {
  const items = block.items.map((item, itemIndex) => (
    <li key={itemIndex}>
      {renderInline(item.children)}
      {item.sublists?.map((sublist, sublistIndex) => renderList(sublist, sublistIndex))}
    </li>
  ));
  return block.ordered ? <ol key={key}>{items}</ol> : <ul key={key}>{items}</ul>;
}

function renderHeading(
  block: Extract<MarkdownBlock, { type: 'heading' }>,
  index: number,
): ReactNode {
  switch (block.level) {
    case 1: return <h1 key={index}>{renderInline(block.children)}</h1>;
    case 2: return <h2 key={index}>{renderInline(block.children)}</h2>;
    case 3: return <h3 key={index}>{renderInline(block.children)}</h3>;
    case 4: return <h4 key={index}>{renderInline(block.children)}</h4>;
    case 5: return <h5 key={index}>{renderInline(block.children)}</h5>;
    case 6: return <h6 key={index}>{renderInline(block.children)}</h6>;
  }
}

function renderBlock(block: MarkdownBlock, index: number): ReactNode {
  switch (block.type) {
    case 'paragraph':
      return <p key={index}>{renderInline(block.children)}</p>;
    case 'heading':
      return renderHeading(block, index);
    case 'codeBlock':
      return <pre key={index}><code>{block.text}</code></pre>;
    case 'list':
      return renderList(block, index);
    case 'blockquote':
      return <blockquote key={index}>{block.children.map(renderBlock)}</blockquote>;
    case 'horizontalRule':
      return <hr key={index} />;
    case 'table':
      return (
        <div className="markdown-table-wrap" key={index}>
          <table>
            <thead>
              <tr>
                {block.headers.map((cell, cellIndex) => (
                  <th key={cellIndex} scope="col">{renderInline(cell)}</th>
                ))}
              </tr>
            </thead>
            {block.rows.length > 0 && (
              <tbody>
                {block.rows.map((row, rowIndex) => (
                  <tr key={rowIndex}>
                    {row.map((cell, cellIndex) => <td key={cellIndex}>{renderInline(cell)}</td>)}
                  </tr>
                ))}
              </tbody>
            )}
          </table>
        </div>
      );
  }
}

export interface MarkdownTextProps {
  text: string;
}

/** Renders model text only as React text nodes and allowlisted semantic elements. */
export default function MarkdownText({ text }: MarkdownTextProps) {
  return <div className="markdown-text">{parseMarkdown(text).map(renderBlock)}</div>;
}
