/**
 * Appends a dictation final transcript to the text the user already typed:
 * space-separated, trimmed on both sides, and a no-op on an empty transcript
 * so nothing the user wrote is ever discarded. Shared by the assistant panel
 * and the chat panel (T7/L47).
 */
export function appendTranscript(existing: string, final: string): string {
  const appended = final.trim();
  if (appended.length === 0) {
    return existing;
  }
  const current = existing.trim();
  return current.length === 0 ? appended : `${current} ${appended}`;
}
