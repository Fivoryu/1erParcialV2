import { describe, expect, it } from 'vitest';
import { decodeBase64ToBytes, exportFileName } from './xmiExport';

function toBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

describe('exportFileName', () => {
  it('derives the download name from the project name and always ends in .xmi', () => {
    expect(exportFileName('Domain Model')).toBe('Domain Model.xmi');
  });

  it('never lets a name with separators or a traversal attempt escape the download name', () => {
    const name = exportFileName('../../etc/passwd');
    expect(name).not.toContain('/');
    expect(name).not.toContain('..');
    expect(name.endsWith('.xmi')).toBe(true);
    expect(name).not.toBe('../../etc/passwd');
  });

  it('keeps accents and unicode letters while dropping unsafe characters', () => {
    expect(exportFileName('Diseño «Modelo» Nº1')).toBe('Diseño Modelo Nº1.xmi');
  });

  it('falls back to a stable default for an empty or whitespace-only name', () => {
    expect(exportFileName('')).toBe('model.xmi');
    expect(exportFileName('   ')).toBe('model.xmi');
  });

  it('never doubles the .xmi extension', () => {
    expect(exportFileName('model.xmi')).toBe('model.xmi');
    expect(exportFileName('model.XMI')).toBe('model.xmi');
  });

  it('derives the name plainly from the project, not from any server-provided filename', () => {
    // The server returns no filename; only fileBase64, checksum, exchangeId and losses.
    expect(exportFileName('Bank System (v2)')).toBe('Bank System (v2).xmi');
  });
});

describe('decodeBase64ToBytes', () => {
  it('decodes a known Base64 string to the expected bytes', () => {
    expect(decodeBase64ToBytes('PHhtaT7p')).toEqual(Uint8Array.from([0x3c, 0x78, 0x6d, 0x69, 0x3e, 0xe9]));
  });

  it('yields an empty array for an empty string', () => {
    const bytes = decodeBase64ToBytes('');
    expect(bytes).toBeInstanceOf(Uint8Array);
    expect(bytes.length).toBe(0);
  });

  it('round-trips a UTF-8 payload back to the original text', () => {
    const text = '<model name="Diseño ✅">ñ</model>';
    const bytes = decodeBase64ToBytes(toBase64(text));
    expect(new TextDecoder().decode(bytes)).toBe(text);
  });
});
