/**
 * Pure helpers for the XMI export flow. The server returns no filename, so the
 * download name is derived from the project name here; that is stated plainly
 * rather than implying the name came from the server.
 */

/** Characters allowed to survive into a download name: letters (any script), digits, spaces, and a few safe punctuation marks. */
const unsafeCharacters = /[^\p{L}\p{N} .()_-]/gu;
const fallbackBaseName = 'model';
const extension = '.xmi';

export function exportFileName(projectName: string): string {
  const sanitized = projectName
    .replace(unsafeCharacters, '')
    // Collapse dot runs so traversal attempts like `../../etc/passwd` cannot
    // leave `..` sequences behind, then trim dots from the edges.
    .replace(/\.{2,}/g, '.')
    .replace(/^\.+|\.+$/g, '')
    .trim();
  const base = sanitized.length > 0 ? sanitized : fallbackBaseName;
  const withoutExtension = base.toLowerCase().endsWith(extension)
    ? base.slice(0, base.length - extension.length)
    : base;
  return `${withoutExtension}${extension}`;
}

/** Decodes the Base64 document the server returns into bytes for a Blob. */
export function decodeBase64ToBytes(base64: string): Uint8Array<ArrayBuffer> {
  if (base64.length === 0) {
    return new Uint8Array(new ArrayBuffer(0));
  }
  const binary = atob(base64);
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}
