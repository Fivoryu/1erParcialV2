// Browser-only integration check against a synthetic fixture and in-memory HTTP responses.
import assert from 'node:assert/strict';
import { createServer } from 'vite';
import react from '@vitejs/plugin-react';
import puppeteer from 'puppeteer-core';

const profile = process.env.T4_BROWSER_PROFILE;
assert.ok(profile, 'T4_BROWSER_PROFILE must name an isolated browser profile');
const server = await createServer({ configFile: false, envFile: false, plugins: [react()],
  server: { host: '127.0.0.1', port: 0 },
  appType: 'custom' });
server.middlewares.use('/fixture', async (_req, res) => {
  res.setHeader('Content-Type', 'text/html');
  res.end(await server.transformIndexHtml('/fixture', '<html><head></head><body><div id="root"></div><script type="module" src="/src/vcs/reviewer-browser-fixture.tsx"></script></body></html>'));
});
await server.listen();
let browser;
try {
  const address = server.httpServer.address();
  assert.ok(address && typeof address !== 'string');
  const origin = `http://127.0.0.1:${address.port}`;
  browser = await puppeteer.launch({ executablePath: '/usr/bin/chromium', headless: true,
    userDataDir: profile, args: ['--disable-background-networking'] });
  const page = await browser.newPage();
  await page.setRequestInterception(true);
  page.on('request', (request) => {
    if (request.url().startsWith(`${origin}/`)) void request.continue();
    else void request.abort();
  });
  await page.goto(`${origin}/fixture`);
  await page.waitForSelector('input[type="checkbox"]');
  await page.select('select[aria-label="Rama de origen"]', 'feature');
  await page.select('select[aria-label="Rama de destino"]', 'main');
  await page.type('input[placeholder="Describe esta revisión"]', 'Synthetic review');
  const checkboxes = await page.$$('input[type="checkbox"]');
  assert.equal(checkboxes.length, 2);
  await checkboxes[0].click();
  await checkboxes[1].click();
  await checkboxes[0].click();
  assert.deepEqual(await page.$$eval('input[type="checkbox"]', (items) => items.map((item) => item.checked)), [false, true]);
  await page.click('button[type="submit"]');
  await page.waitForFunction('window.submitted !== undefined');
  assert.deepEqual(await page.evaluate('window.submitted'), {
    sourceBranch: 'feature', targetBranch: 'main', title: 'Synthetic review', reviewerIds: ['member-2'],
  });
  console.log('PASS: real browser toggled two reviewers and submitted only the selected identity');
} finally {
  if (browser) await browser.close();
  await server.close();
}
