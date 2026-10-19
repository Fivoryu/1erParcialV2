export type MarkdownInline =
  | { type: 'text'; text: string }
  | { type: 'lineBreak' }
  | { type: 'inlineCode'; text: string }
  | { type: 'strong' | 'emphasis'; children: MarkdownInline[] };

export interface MarkdownListItem {
  children: MarkdownInline[];
  sublists?: MarkdownListBlock[];
}

export interface MarkdownListBlock {
  ordered: boolean;
  items: MarkdownListItem[];
}

export type MarkdownBlock =
  | { type: 'paragraph'; children: MarkdownInline[] }
  | { type: 'heading'; level: 1 | 2 | 3 | 4 | 5 | 6; children: MarkdownInline[] }
  | { type: 'codeBlock'; text: string }
  | ({ type: 'list' } & MarkdownListBlock)
  | { type: 'blockquote'; children: MarkdownBlock[] }
  | { type: 'horizontalRule' }
  | { type: 'table'; headers: MarkdownInline[][]; rows: MarkdownInline[][][] };

function appendText(nodes: MarkdownInline[], text: string): void {
  if (!text) return;
  const previous = nodes[nodes.length - 1];
  if (previous?.type === 'text') {
    previous.text += text;
  } else {
    nodes.push({ type: 'text', text });
  }
}

function isWordCharacter(character: string | undefined): boolean {
  return character !== undefined && /[\p{L}\p{N}]/u.test(character);
}

function isExactDelimiter(source: string, index: number, marker: string): boolean {
  if (!source.startsWith(marker, index)) return false;
  const markerCharacter = marker[0];
  return source[index - 1] !== markerCharacter && source[index + marker.length] !== markerCharacter;
}

function isValidOpening(source: string, index: number, marker: string): boolean {
  if (!isExactDelimiter(source, index, marker)) return false;
  const firstContentCharacter = source[index + marker.length];
  if (!firstContentCharacter || /\s/.test(firstContentCharacter)) return false;
  return marker[0] !== '_' || !isWordCharacter(source[index - 1]);
}

function isValidClosing(source: string, index: number, marker: string): boolean {
  if (!isExactDelimiter(source, index, marker)) return false;
  const lastContentCharacter = source[index - 1];
  if (!lastContentCharacter || /\s/.test(lastContentCharacter)) return false;
  return marker[0] !== '_' || !isWordCharacter(source[index + marker.length]);
}

function findClosingDelimiter(source: string, marker: string, contentStart: number): number {
  let candidate = source.indexOf(marker, contentStart);
  while (candidate !== -1) {
    if (isValidClosing(source, candidate, marker)) return candidate;
    candidate = source.indexOf(marker, candidate + marker.length);
  }
  return -1;
}

function parseInline(source: string): MarkdownInline[] {
  const nodes: MarkdownInline[] = [];
  let index = 0;

  while (index < source.length) {
    if (source[index] === '\n') {
      nodes.push({ type: 'lineBreak' });
      index += 1;
      continue;
    }

    if (source[index] === '`') {
      const closing = source.indexOf('`', index + 1);
      const lineBreak = source.indexOf('\n', index + 1);
      if (closing > index + 1 && (lineBreak === -1 || lineBreak > closing)) {
        nodes.push({ type: 'inlineCode', text: source.slice(index + 1, closing) });
        index = closing + 1;
        continue;
      }
    }

    const marker = source.startsWith('**', index) ? '**'
      : source.startsWith('__', index) ? '__'
        : source[index] === '*' ? '*'
          : source[index] === '_' ? '_'
            : null;
    if (marker && isValidOpening(source, index, marker)) {
      const contentStart = index + marker.length;
      const closing = findClosingDelimiter(source, marker, contentStart);
      if (closing !== -1) {
        const content = parseInline(source.slice(contentStart, closing));
        nodes.push({ type: marker.length === 2 ? 'strong' : 'emphasis', children: content });
        index = closing + marker.length;
        continue;
      }
    }

    appendText(nodes, source[index] ?? '');
    index += 1;
  }

  return nodes;
}

function fenceOpening(line: string): boolean {
  return /^```(?:[A-Za-z0-9_+-]+)?[ \t]*$/.test(line);
}

function fenceClosing(lines: string[], start: number): number {
  if (!fenceOpening(lines[start] ?? '')) return -1;
  for (let index = start + 1; index < lines.length; index += 1) {
    if (/^```[ \t]*$/.test(lines[index] ?? '')) return index;
  }
  return -1;
}

function headingContent(line: string): { level: 1 | 2 | 3 | 4 | 5 | 6; content: string } | null {
  const match = line.match(/^ {0,3}(#{1,6})[ \t]+(.+?)\s*$/);
  if (!match) return null;
  const level = match[1]?.length;
  const content = (match[2] ?? '').replace(/[ \t]+#+[ \t]*$/, '').trimEnd();
  if (!level || level > 6 || !content.trim()) return null;
  return { level: level as 1 | 2 | 3 | 4 | 5 | 6, content };
}

function quoteContent(line: string): string | null {
  const match = line.match(/^\s*>[ \t]?(.*)$/);
  return match ? match[1] ?? '' : null;
}

function listItem(line: string): { indent: number; ordered: boolean; content: string } | null {
  const unordered = line.match(/^([ \t]*)[-*+][ \t]+(.+?)\s*$/);
  if (unordered) {
    const indent = (unordered[1] ?? '').replace(/\t/g, '    ').length;
    return { indent, ordered: false, content: unordered[2] ?? '' };
  }
  const ordered = line.match(/^([ \t]*)\d+\.[ \t]+(.+?)\s*$/);
  if (ordered) {
    const indent = (ordered[1] ?? '').replace(/\t/g, '    ').length;
    return { indent, ordered: true, content: ordered[2] ?? '' };
  }
  return null;
}

function tableCells(line: string): string[] | null {
  if (!line.includes('|')) return null;
  let row = line.trim();
  if (row.startsWith('|')) row = row.slice(1);
  if (row.endsWith('|')) row = row.slice(0, -1);
  return row.split('|').map((cell) => cell.trim());
}

function isTableStart(lines: string[], index: number): boolean {
  const header = tableCells(lines[index] ?? '');
  const separator = tableCells(lines[index + 1] ?? '');
  return header !== null
    && separator !== null
    && header.length > 0
    && header.length === separator.length
    && separator.every((cell) => /^:?-{3,}:?$/.test(cell));
}

function startsBlock(lines: string[], index: number): boolean {
  const line = lines[index] ?? '';
  return fenceClosing(lines, index) !== -1
    || headingContent(line) !== null
    || line.trim() === '---'
    || quoteContent(line) !== null
    || listItem(line) !== null
    || isTableStart(lines, index);
}

function parseList(
  lines: string[],
  start: number,
): { block: MarkdownListBlock; nextIndex: number } {
  const firstItem = listItem(lines[start] ?? '');
  if (!firstItem) return { block: { ordered: false, items: [] }, nextIndex: start };

  const block: MarkdownListBlock = { ordered: firstItem.ordered, items: [] };
  let index = start;

  while (index < lines.length) {
    const item = listItem(lines[index] ?? '');
    if (!item || item.indent < firstItem.indent) break;

    if (item.indent === firstItem.indent) {
      if (item.ordered !== firstItem.ordered) break;
      block.items.push({ children: parseInline(item.content) });
      index += 1;
      continue;
    }

    const parent = block.items[block.items.length - 1];
    if (!parent) break;
    const nested = parseList(lines, index);
    parent.sublists ??= [];
    parent.sublists.push(nested.block);
    index = nested.nextIndex;
  }

  return { block, nextIndex: index };
}

function parseBlocks(lines: string[]): MarkdownBlock[] {
  const blocks: MarkdownBlock[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index] ?? '';
    if (!line.trim()) {
      index += 1;
      continue;
    }

    const closingFence = fenceClosing(lines, index);
    if (closingFence !== -1) {
      blocks.push({ type: 'codeBlock', text: lines.slice(index + 1, closingFence).join('\n') });
      index = closingFence + 1;
      continue;
    }

    const heading = headingContent(line);
    if (heading) {
      blocks.push({ type: 'heading', level: heading.level, children: parseInline(heading.content) });
      index += 1;
      continue;
    }

    if (line.trim() === '---') {
      blocks.push({ type: 'horizontalRule' });
      index += 1;
      continue;
    }

    const quote = quoteContent(line);
    if (quote !== null) {
      const quoteLines: string[] = [];
      while (index < lines.length) {
        const quoted = quoteContent(lines[index] ?? '');
        if (quoted === null) break;
        quoteLines.push(quoted);
        index += 1;
      }
      blocks.push({
        type: 'blockquote',
        children: [{ type: 'paragraph', children: parseInline(quoteLines.join('\n')) }],
      });
      continue;
    }

    if (isTableStart(lines, index)) {
      const headers = (tableCells(line) ?? []).map(parseInline);
      index += 2;
      const rows: MarkdownInline[][][] = [];
      while (index < lines.length && (lines[index] ?? '').trim()) {
        const cells = tableCells(lines[index] ?? '');
        if (!cells || cells.length !== headers.length) break;
        rows.push(cells.map(parseInline));
        index += 1;
      }
      blocks.push({ type: 'table', headers, rows });
      continue;
    }

    if (listItem(line)) {
      const parsedList = parseList(lines, index);
      blocks.push({ type: 'list', ...parsedList.block });
      index = parsedList.nextIndex;
      continue;
    }

    const paragraphLines = [line];
    index += 1;
    while (index < lines.length && (lines[index] ?? '').trim() && !startsBlock(lines, index)) {
      paragraphLines.push(lines[index] ?? '');
      index += 1;
    }
    blocks.push({ type: 'paragraph', children: parseInline(paragraphLines.join('\n')) });
  }

  return blocks;
}

/** Parses a deliberately small, deterministic Markdown subset; unsupported syntax remains text. */
export function parseMarkdown(source: string): MarkdownBlock[] {
  return parseBlocks(source.replace(/\r\n?/g, '\n').split('\n'));
}
