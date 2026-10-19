import { describe, expect, it, vi } from 'vitest';

import { createApiClient } from '../api/client';
import type { HelpGuideRequest, HelpGuideResponse } from '../api/types';

describe('help guide API client', () => {
  it('posts the question, corpus, and editor state to the authenticated guide endpoint', async () => {
    const request: HelpGuideRequest = {
      question: 'Where can I export the model?',
      topics: [{ slug: 'export-and-import-xmi', title: 'XMI interchange', summary: 'Export or import a model.' }],
      editorState: { openSection: null, sections: ['xmi', 'collaboration', 'members', 'generation'] },
    };
    const responseBody: HelpGuideResponse = {
      schemaVersion: 1,
      answer: 'Open XMI interchange.',
      suggestedSlugs: ['export-and-import-xmi'],
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(responseBody), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    const client = createApiClient('https://diagram.test', fetcher);

    await expect(client.askHelpGuide(request)).resolves.toEqual(responseBody);

    expect(fetcher).toHaveBeenCalledOnce();
    const [url, init] = fetcher.mock.calls[0] ?? [];
    expect(String(url)).toBe('https://diagram.test/help/guide');
    expect(init?.method).toBe('POST');
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json');
    expect(JSON.parse(String(init?.body))).toEqual(request);
  });
});
