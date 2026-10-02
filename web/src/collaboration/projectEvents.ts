import { getAuthorizationHeaderValue } from '../api/session';

export interface ProjectEventMessage {
  type: string;
  data: unknown;
}

export interface ProjectEventsOptions {
  projectId: string;
  onEvent: (event: ProjectEventMessage) => void;
  fetcher?: typeof fetch;
  schedule?: (callback: () => void, delayMs: number) => unknown;
  baseUrl?: string;
}

interface SseFrame {
  type: string;
  data: string;
}

const RETRY_BASE_DELAY_MS = 250;
const RETRY_MAX_DELAY_MS = 8_000;
const MAX_RETRY_STEP = 5;

function parseFrame(frame: string): SseFrame | null {
  let type = 'message';
  const data: string[] = [];

  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith(':')) {
      continue;
    }

    const separator = line.indexOf(':');
    const field = separator === -1 ? line : line.slice(0, separator);
    let value = separator === -1 ? '' : line.slice(separator + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }

    if (field === 'event') {
      type = value || 'message';
    } else if (field === 'data') {
      data.push(value);
    }
  }

  return data.length > 0 ? { type, data: data.join('\n') } : null;
}

export function parseSseFrames(buffer: string): { events: SseFrame[]; rest: string } {
  const events: SseFrame[] = [];
  const delimiter = /(?:\r?\n){2}/g;
  let frameStart = 0;
  let match: RegExpExecArray | null;

  while ((match = delimiter.exec(buffer)) !== null) {
    const event = parseFrame(buffer.slice(frameStart, match.index));
    if (event) {
      events.push(event);
    }
    frameStart = match.index + match[0].length;
  }

  return { events, rest: buffer.slice(frameStart) };
}

export function subscribeProjectEvents({
  projectId,
  onEvent,
  fetcher = fetch,
  schedule = (callback, delayMs) => setTimeout(callback, delayMs),
  baseUrl = import.meta.env.VITE_API_BASE_URL ?? '',
}: ProjectEventsOptions): () => void {
  const base = baseUrl.replace(/\/+$/, '');
  const path = `/projects/${encodeURIComponent(projectId)}/events`;
  let stopped = false;
  let shouldRetry = true;
  let retryStep = 0;
  let currentController: AbortController | null = null;
  let currentReader: ReadableStreamDefaultReader<Uint8Array> | null = null;

  function scheduleReconnect(): void {
    const delayMs = Math.min(RETRY_BASE_DELAY_MS * (2 ** retryStep), RETRY_MAX_DELAY_MS);
    retryStep = Math.min(retryStep + 1, MAX_RETRY_STEP);
    schedule(() => {
      // A queued retry must honor terminal authentication decisions made after it was scheduled.
      if (!stopped && shouldRetry) {
        void connect();
      }
    }, delayMs);
  }

  async function connect(): Promise<void> {
    if (stopped) {
      return;
    }

    const controller = new AbortController();
    currentController = controller;
    let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;

    try {
      const headers = new Headers({ Accept: 'text/event-stream' });
      const authorization = getAuthorizationHeaderValue();
      if (authorization) {
        headers.set('Authorization', authorization);
      }

      const response = await fetcher(`${base}${path}`, {
        method: 'GET',
        headers,
        signal: controller.signal,
      });
      if (stopped) {
        return;
      }
      if (!response.ok || !response.body) {
        if (response.status === 401 || response.status === 403) {
          // Authentication failures are terminal; transient network and server failures keep retrying.
          shouldRetry = false;
        }
        throw new Error(`Project event stream returned HTTP ${response.status}.`);
      }

      reader = response.body.getReader();
      currentReader = reader;
      const decoder = new TextDecoder();
      let buffer = '';

      while (!stopped) {
        const { done, value } = await reader.read();
        if (done) {
          buffer += decoder.decode();
          break;
        }

        buffer += decoder.decode(value, { stream: true });
        const parsed = parseSseFrames(buffer);
        buffer = parsed.rest;
        for (const frame of parsed.events) {
          let data: unknown;
          try {
            data = JSON.parse(frame.data) as unknown;
          } catch {
            continue;
          }

          if (!stopped) {
            try {
              onEvent({ type: frame.type, data });
            } catch {
              // A consumer callback must not terminate the shared project stream.
            }
          }
        }
      }
    } catch {
      // Network failures and unexpected stream responses use the same bounded retry path.
    } finally {
      if (reader) {
        if (currentReader === reader) {
          currentReader = null;
        }
        try {
          reader.releaseLock();
        } catch {
          // A canceled reader may already have released its lock.
        }
      }
      if (currentController === controller) {
        currentController = null;
      }
      if (!stopped && shouldRetry) {
        scheduleReconnect();
      }
    }
  }

  void connect();

  return () => {
    stopped = true;
    currentController?.abort();
    if (currentReader) {
      void currentReader.cancel().catch(() => undefined);
      currentReader = null;
    }
  };
}
