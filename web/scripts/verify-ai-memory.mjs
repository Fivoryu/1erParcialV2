/**
 * One bounded, real-browser acceptance pass for diagram memory in shared AI chat.
 *
 * Usage:
 *   node scripts/verify-ai-memory.mjs [--app <url>] [--backend <url>] [--out <dir>] [--timeout-ms <ms>]
 *   node scripts/verify-ai-memory.mjs --help
 *
 * Requires the existing local web/backend/DB and enabled local llama-server. It creates one
 * synthetic account, a target project through the UI, a second source project and one public-API
 * fixture memory. Every AI request is a natural-language chat message; no tool JSON is prompted.
 * Screenshots are restricted to docs/capturas/ai-memory. The public project API has no deletion
 * route, so cleanup clears only memories and chat messages created in these projects;
 * the synthetic account and project shells (including the UML fixture) remain for manual removal.
 */

import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';
import { runMemoryPreflight } from './memory-preflight.mjs';

const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const APP = readOption('--app') ?? process.env.APP_URL ?? 'http://localhost:5173';
const BACKEND = (readOption('--backend') ?? process.env.BACKEND_URL ?? 'http://localhost:18092').replace(/\/+$/, '');
const OUT_ROOT = path.resolve(fileURLToPath(new URL('../../docs/capturas/ai-memory/', import.meta.url)));
const requestedOut = path.resolve(readOption('--out') ?? OUT_ROOT);
const TIMEOUT_MS = Number(readOption('--timeout-ms') ?? process.env.AI_TIMEOUT_MS ?? 180000);
const SESSION_STORAGE_KEY = 'class-diagrammer.session.v1';

function readOption(name) {
  const index = process.argv.indexOf(name);
  if (index === -1) return undefined;
  const value = process.argv[index + 1];
  if (!value || value.startsWith('--')) throw new Error(`Missing value for ${name}`);
  return value;
}

if (process.argv.includes('--help')) {
  console.log(`Usage: node scripts/verify-ai-memory.mjs [--app <url>] [--backend <url>] [--out <dir>] [--timeout-ms <ms>]

Defaults: --app http://localhost:5173, --backend http://localhost:18092,
--out docs/capturas/ai-memory, --timeout-ms 180000 per AI request (not preflight).
Each app/backend preflight request has its own fixed 5000 ms deadline and reports its
URL, timing, HTTP status, readiness, and error independently. A failed request does not
hide the other result. Requires a running local backend/DB, enabled local assistant and
Chrome. Creates one synthetic account and two projects; it clears only their memory and
chat-message data. No request retries or external/cloud inference are performed.`);
  process.exit(0);
}

if (requestedOut !== OUT_ROOT && !requestedOut.startsWith(`${OUT_ROOT}${path.sep}`)) {
  throw new Error(`--out must stay under ${OUT_ROOT}`);
}
if (!Number.isInteger(TIMEOUT_MS) || TIMEOUT_MS < 1000 || TIMEOUT_MS > 300000) {
  throw new Error('--timeout-ms must be an integer from 1000 through 300000.');
}

const results = [];
const screenshots = [];
const createdProjects = [];
let auth = null;
let browser = null;
let page = null;
let report = {
  app: APP,
  backend: BACKEND,
  timeoutMs: TIMEOUT_MS,
  results,
  screenshots,
  created: { account: null, projects: [] },
  cleanup: [],
};

function check(step, ok, expected, observed) {
  results.push({ step, ok, expected, observed });
  console.log(`[${ok ? 'OK' : 'FAIL'}] ${step}`);
  console.log(`  expected: ${JSON.stringify(expected)}`);
  console.log(`  observed: ${JSON.stringify(observed)}`);
  return ok;
}

function pause(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function typeInto(targetPage, selector, value) {
  await targetPage.waitForSelector(selector, { timeout: 30000 });
  await targetPage.click(selector, { clickCount: 3 });
  await targetPage.type(selector, value, { delay: 6 });
}

async function registerAndCreateTarget(targetPage, suffix) {
  const username = `ai-memory-${suffix}`;
  const password = `AiMemory-${suffix}-Safe!`;
  const projectName = `AI memory target ${suffix}`;

  await targetPage.goto(`${APP}/`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await targetPage.waitForSelector('#auth-username', { timeout: 30000 });
  await targetPage.click('.auth-mode-switch .text-button');
  await targetPage.waitForSelector('#auth-display-name', { timeout: 10000 });
  await typeInto(targetPage, '#auth-display-name', 'AI Memory Acceptance');
  await typeInto(targetPage, '#auth-username', username);
  await typeInto(targetPage, '#auth-password', password);
  await targetPage.click('.auth-form button[type="submit"]');
  await targetPage.waitForSelector('#project-name', { timeout: 30000 });
  await typeInto(targetPage, '#project-name', projectName);
  await typeInto(targetPage, '#project-description', 'Synthetic project created by the AI memory acceptance run.');
  await targetPage.click('.create-panel button[type="submit"]');
  await targetPage.waitForSelector('.floating-assistant', { timeout: 30000 });
  const editorUrl = targetPage.url();
  const projectId = editorUrl.match(/\/projects\/([0-9a-f-]{36})/i)?.[1];
  if (!projectId) throw new Error('The UI-created target project URL did not contain a project id.');
  const session = await targetPage.evaluate((key) => {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : null;
  }, SESSION_STORAGE_KEY);
  if (!session || typeof session.token !== 'string' || typeof session.userId !== 'string') {
    throw new Error('The synthetic account session was not available in browser storage.');
  }
  return {
    username,
    editorUrl,
    project: { id: projectId, name: projectName },
    auth: { token: session.token, userId: session.userId, username: session.username },
  };
}

async function apiRequest(route, { method = 'GET', body } = {}) {
  if (!auth) throw new Error('The synthetic account session is not available.');
  const headers = new Headers({ Authorization: `Bearer ${auth.token}` });
  if (body !== undefined) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BACKEND}${route}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(15000),
  });
  if (response.status === 204) return null;
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${method} ${route} returned HTTP ${response.status}.`);
  return payload;
}

function defaultView(model) {
  return model.views.find((view) => view.is_default) ?? model.views[0] ?? null;
}

async function addSyntheticClass(projectId, className) {
  const model = await apiRequest(`/projects/${encodeURIComponent(projectId)}/model`);
  const classifierId = crypto.randomUUID();
  const result = await apiRequest(`/projects/${encodeURIComponent(projectId)}/commands`, {
    method: 'POST',
    body: {
      operationId: crypto.randomUUID(),
      type: 'classifier.create',
      author: { userId: auth.userId, externalSubject: auth.username },
      projectId,
      modelId: model.id,
      baseRevision: model.revision,
      targets: [],
      preconditions: [],
      payload: { id: classifierId, name: className, kind: 'CLASS', namespaceId: null },
      origin: 'MANUAL',
      causalParentOperationId: null,
      idempotencyKey: crypto.randomUUID(),
    },
  });
  if (result.status !== 'ACCEPTED') {
    throw new Error(`The synthetic UML fixture was not accepted (${result.status}).`);
  }
  return { classifierId, revision: result.resultRevision };
}

async function shot(targetPage, name) {
  const file = path.join(requestedOut, name);
  await targetPage.screenshot({ path: file, fullPage: false });
  screenshots.push(file);
  return file;
}

async function captureRawAssistantBodies(targetPage) {
  await targetPage.evaluateOnNewDocument(() => {
    window.__aiMemoryRawAssistantBodies = [];
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const response = await originalFetch(...args);
      const input = args[0];
      const requestUrl = input instanceof Request ? input.url : String(input);
      if (!requestUrl.includes('/events') || !response.body) return response;
      const reader = response.clone().body?.getReader();
      if (!reader) return response;
      void (async () => {
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
          const { done, value } = await reader.read();
          buffer += decoder.decode(value, { stream: !done });
          const frames = buffer.split(/\r?\n\r?\n/);
          buffer = frames.pop() ?? '';
          for (const frame of frames) {
            let eventType = 'message';
            const data = [];
            for (const line of frame.split(/\r?\n/)) {
              if (line.startsWith(':')) continue;
              const separator = line.indexOf(':');
              const field = separator === -1 ? line : line.slice(0, separator);
              let fieldValue = separator === -1 ? '' : line.slice(separator + 1);
              if (fieldValue.startsWith(' ')) fieldValue = fieldValue.slice(1);
              if (field === 'event') eventType = fieldValue || 'message';
              if (field === 'data') data.push(fieldValue);
            }
            if (eventType !== 'message.posted' || data.length === 0) continue;
            try {
              const envelope = JSON.parse(data.join('\n'));
              const message = envelope?.payload?.message;
              if (message?.kind === 'AI' && typeof message.body === 'string') {
                window.__aiMemoryRawAssistantBodies.push(message.body);
              }
            } catch {
              // Ignore malformed or unrelated frames, as the app's event parser does.
            }
          }
          if (done) break;
        }
      })().catch(() => undefined);
      return response;
    };
  });
}

async function openChat(targetPage) {
  await targetPage.click('[data-section="collaboration"]');
  await targetPage.waitForSelector('#chat-session-selector', { timeout: 30000 });
  await targetPage.waitForFunction(() => {
    const selector = document.querySelector('#chat-session-selector');
    return Boolean(selector && selector.value);
  }, { timeout: 30000, polling: 250 });
}

async function askChat(targetPage, body, projectId) {
  const sessionId = await targetPage.$eval('#chat-session-selector', (select) => select.value);
  const beforeCount = await targetPage.$$eval('.chat-message--assistant', (rows) => rows.length);
  const rawBefore = await targetPage.evaluate(() => window.__aiMemoryRawAssistantBodies?.length ?? 0);
  await typeInto(targetPage, '#chat-message-draft', body);
  await targetPage.click('.chat-compose-form button[type="submit"]');
  await targetPage.waitForFunction((expected) => Array.from(document.querySelectorAll('.chat-message-body'))
    .some((node) => (node.textContent ?? '').trim() === expected), { timeout: 30000, polling: 250 }, body);

  const clicked = await targetPage.evaluate((expected) => {
    const row = Array.from(document.querySelectorAll('.chat-message'))
      .find((candidate) => candidate.querySelector('.chat-message-body')?.textContent?.trim() === expected);
    const button = Array.from(row?.querySelectorAll('button') ?? [])
      .find((candidate) => candidate.textContent?.trim() === 'Ask AI');
    if (!button || button.disabled) return false;
    button.click();
    return true;
  }, body);
  if (!clicked) throw new Error('The Ask AI control was missing or disabled for the synthetic prompt.');

  try {
    await targetPage.waitForFunction((previousCount) => {
      const answers = document.querySelectorAll('.chat-message--assistant .chat-message-body');
      const status = document.querySelector('.chat-ai-status');
      const terminal = status && /chat-ai-status--(completed|failed|cancelled)/.test(status.className);
      return answers.length > previousCount && terminal;
    }, { timeout: TIMEOUT_MS, polling: 500 }, beforeCount);
  } catch {
    const status = await targetPage.$eval('.chat-ai-status', (node) => node.textContent?.trim() ?? '')
      .catch(() => 'No terminal AI status was rendered.');
    throw new Error(`The bounded AI request did not produce a terminal answer within ${TIMEOUT_MS} ms (${status}).`);
  }

  const answer = await targetPage.evaluate((count) => {
    const rows = Array.from(document.querySelectorAll('.chat-message--assistant'));
    const row = rows[rows.length - 1];
    const bodies = window.__aiMemoryRawAssistantBodies ?? [];
    return {
      count: rows.length,
      visibleBody: row?.querySelector('.chat-message-body')?.textContent?.trim() ?? '',
      rawAnswer: bodies.length > count ? bodies[bodies.length - 1] : null,
      status: document.querySelector('.chat-ai-status')?.textContent?.trim() ?? '',
    };
  }, rawBefore);
  const messages = await apiRequest(`/projects/${encodeURIComponent(projectId)}/sessions/${encodeURIComponent(sessionId)}/messages`);
  const persisted = [...messages].reverse().find((message) => message.kind === 'AI') ?? null;
  return { sessionId, ...answer, persistedBody: persisted?.body ?? null };
}

async function waitForMemoryInApiAndUi(targetPage, projectId, viewId, marker, timeoutMs = 20000) {
  const started = Date.now();
  let memories = [];
  let visible = false;
  while (Date.now() - started < timeoutMs) {
    memories = await apiRequest(`/projects/${encodeURIComponent(projectId)}/views/${encodeURIComponent(viewId)}/memories`);
    visible = await targetPage.$eval('.diagram-memory-panel', (panel, expected) =>
      (panel.textContent ?? '').includes(expected), marker).catch(() => false);
    if (memories.some((memory) => memory.content.includes(marker)) && visible) break;
    await pause(500);
  }
  return {
    apiMemories: memories,
    visible,
    matching: memories.filter((memory) => memory.content.includes(marker)),
  };
}

async function verifyPersistedAnswer(stage, answer, expectedVisible) {
  check(`${stage}: AI answer is rendered in the browser`, answer.visibleBody.length > 0,
    'non-empty assistant message visible in chat', answer.visibleBody);
  check(`${stage}: public session history matches the raw SSE answer`,
    typeof answer.rawAnswer === 'string' && answer.rawAnswer.length > 0 && answer.persistedBody === answer.rawAnswer,
    'persisted message body exactly equals the captured message.posted SSE body',
    { persisted: answer.persistedBody, rawSse: answer.rawAnswer });
  check(`${stage}: browser answer contains the requested evidence`,
    expectedVisible.every((value) => answer.visibleBody.includes(value)), expectedVisible, answer.visibleBody);
}

async function createExternalSource(suffix) {
  const projectName = `AI memory source ${suffix}`;
  const project = await apiRequest('/projects', {
    method: 'POST',
    body: { name: projectName, description: 'Synthetic cross-diagram memory fixture.' },
  });
  const projectRecord = { id: project.id, name: project.name, viewId: null };
  createdProjects.push(projectRecord);
  report.created.projects.push(projectRecord);
  const model = await apiRequest(`/projects/${encodeURIComponent(project.id)}/model`);
  const view = defaultView(model);
  if (!view) throw new Error('The synthetic source project has no view.');
  projectRecord.viewId = view.id;

  const marker = `T3-EXTERNAL-${suffix.toUpperCase()}`;
  const fixtureContent = `The T3 sample ledger has settlement code ${marker}.`;
  const createdMemory = await apiRequest(`/projects/${encodeURIComponent(project.id)}/views/${encodeURIComponent(view.id)}/memories`, {
    method: 'POST',
    body: { kind: 'DECISION', content: fixtureContent },
  });
  const stored = await apiRequest(`/projects/${encodeURIComponent(project.id)}/views/${encodeURIComponent(view.id)}/memories`);
  check('public API fixture is persisted in a separate synthetic project',
    stored.some((memory) => memory.memoryId === createdMemory.memoryId && memory.content === fixtureContent),
    { projectName, fixtureContent }, stored);
  return { project, view, marker, fixtureContent };
}

async function cleanupCreatedData() {
  if (!auth) return;
  for (const project of createdProjects) {
    if (!project.viewId) {
      report.cleanup.push({ projectId: project.id, data: 'not cleared because its view was not resolved' });
      continue;
    }
    try {
      const sessions = await apiRequest(`/projects/${encodeURIComponent(project.id)}/views/${encodeURIComponent(project.viewId)}/sessions`);
      for (const session of sessions) {
        await apiRequest(`/projects/${encodeURIComponent(project.id)}/sessions/${encodeURIComponent(session.id)}`, { method: 'DELETE' });
      }
      report.cleanup.push({ projectId: project.id, messages: 'cleared for created sessions' });
    } catch (error) {
      report.cleanup.push({ projectId: project.id, messages: `not cleared: ${error.message}` });
    }
    try {
      await apiRequest(`/projects/${encodeURIComponent(project.id)}/views/${encodeURIComponent(project.viewId)}/memories`, { method: 'DELETE' });
      report.cleanup.push({ projectId: project.id, memories: 'cleared' });
    } catch (error) {
      report.cleanup.push({ projectId: project.id, memories: `not cleared: ${error.message}` });
    }
  }
  report.cleanup.push({ projects: 'synthetic project shells retained; no project deletion route is exposed by the public project API' });
}

async function main() {
  const preflightResult = await runMemoryPreflight(APP, BACKEND);
  report.preflight = preflightResult;
  if (!preflightResult.appReady || !preflightResult.backendReady) {
    report.unavailable = `Local prerequisites unavailable (app HTTP ${preflightResult.appStatus}, backend auth HTTP ${preflightResult.backendAuthStatus}); no account, project, or inference was attempted.`;
    console.log(`[unavailable] ${report.unavailable}`);
    return false;
  }

  const suffix = crypto.randomUUID().slice(0, 8);
  report.suffix = suffix;
  await mkdir(requestedOut, { recursive: true });
  browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--window-size=1500,1000', '--disable-infobars', '--no-first-run'],
  });
  page = await browser.newPage();
  await captureRawAssistantBodies(page);
  await page.setViewport({ width: 1440, height: 900 });

  const identity = await registerAndCreateTarget(page, suffix);
  auth = identity.auth;
  const target = { ...identity.project, viewId: null };
  createdProjects.push(target);
  report.created.account = { username: identity.username, userId: auth.userId };
  report.created.projects.push({ id: target.id, name: target.name });
  report.targetUrl = identity.editorUrl;

  const targetModel = await apiRequest(`/projects/${encodeURIComponent(target.id)}/model`);
  const targetView = defaultView(targetModel);
  if (!targetView) throw new Error('The UI-created target project has no default view.');
  target.viewId = targetView.id;
  report.created.projects[0].viewId = targetView.id;

  const className = `T3Invoice${suffix}`;
  const classFixture = await addSyntheticClass(target.id, className);
  report.fixtures = { className, classifierId: classFixture.classifierId };
  await page.waitForFunction((name) => Array.from(document.querySelectorAll('.uml-class-name'))
    .some((node) => (node.textContent ?? '').includes(name)), { timeout: 30000, polling: 250 }, className);
  check('browser canvas renders the public-API canonical UML fixture', true, className,
    await page.$$eval('.uml-class-name', (nodes) => nodes.map((node) => node.textContent?.trim() ?? '')));

  const external = await createExternalSource(suffix);
  report.fixtures.externalMarker = external.marker;
  await openChat(page);

  const autoMarker = `T3-AUTO-${suffix.toUpperCase()}`;
  const autoPrompt = `En nuestro modelo de prueba acordamos que cada reintento de factura use el código de conciliación ${autoMarker}. Según el diagrama UML actual, ¿qué clases hay?`;
  const autoAnswer = await askChat(page, autoPrompt, target.id);
  report.automaticMemory = { prompt: autoPrompt, answer: autoAnswer };
  await verifyPersistedAnswer('automatic memory and current UML', autoAnswer, [className]);
  const automatic = await waitForMemoryInApiAndUi(page, target.id, targetView.id, autoMarker);
  check('normal AI use automatically makes extracted memory visible in both UI and public API',
    automatic.matching.some((memory) => memory.status === 'ACTIVE'),
    'active target-diagram memory containing the natural-language design decision', automatic);
  await shot(page, '01-automatic-memory-and-uml.png');

  const previousSession = await page.$eval('#chat-session-selector', (select) => select.value);
  await page.click('.chat-session-controls button');
  await page.waitForFunction((oldId) => {
    const selector = document.querySelector('#chat-session-selector');
    return Boolean(selector && selector.value && selector.value !== oldId);
  }, { timeout: 30000, polling: 250 }, previousSession);
  const recallPrompt = '¿Qué decisión de diseño está activa para los reintentos de factura T3?';
  const recallAnswer = await askChat(page, recallPrompt, target.id);
  report.newSessionRecall = { prompt: recallPrompt, answer: recallAnswer };
  await verifyPersistedAnswer('new-session memory recall', recallAnswer, [autoMarker]);
  await shot(page, '02-new-session-recall.png');

  const saveMarker = `T3-SAVE-${suffix.toUpperCase()}`;
  const savePrompt = `Guardá como hipótesis que la conciliación de la factura T3 genera el estado ${saveMarker} antes del cierre. Decime en una frase qué registraste.`;
  const saveAnswer = await askChat(page, savePrompt, target.id);
  report.naturalSave = { prompt: savePrompt, answer: saveAnswer };
  await verifyPersistedAnswer('natural-language save', saveAnswer, [saveMarker]);
  const saved = await waitForMemoryInApiAndUi(page, target.id, targetView.id, saveMarker);
  check('natural-language save persists an active memory visible in browser and API',
    saved.matching.some((memory) => memory.status === 'ACTIVE'),
    'active memory containing the requested hypothesis', saved);
  await shot(page, '03-natural-save.png');

  const revisedMarker = `T3-REVISED-${suffix.toUpperCase()}`;
  const revisePrompt = `Revisá la hipótesis activa sobre conciliación que contiene ${saveMarker}: debe indicar ${revisedMarker} después del cierre. ¿Cuál quedó como versión vigente?`;
  const reviseAnswer = await askChat(page, revisePrompt, target.id);
  report.naturalRevision = { prompt: revisePrompt, answer: reviseAnswer };
  await verifyPersistedAnswer('natural-language revision', reviseAnswer, [revisedMarker]);
  const [replacedMemories, activeMemories] = await Promise.all([
    apiRequest(`/projects/${encodeURIComponent(target.id)}/views/${encodeURIComponent(targetView.id)}/memories?status=REPLACED`),
    apiRequest(`/projects/${encodeURIComponent(target.id)}/views/${encodeURIComponent(targetView.id)}/memories?status=ACTIVE`),
  ]);
  const replaced = replacedMemories.find((memory) => memory.content.includes(saveMarker) && memory.status === 'REPLACED');
  const replacement = activeMemories.find((memory) => memory.content.includes(revisedMarker) && memory.status === 'ACTIVE');
  const revisionState = {
    predecessorStatus: replaced?.status ?? null,
    replacedBy: replaced?.replacedBy ?? null,
    replacementStatus: replacement?.status ?? null,
    replacementContent: replacement?.content ?? null,
  };
  check('revision preserves the predecessor and exposes an active replacement',
    Boolean(replaced && replacement && replaced.replacedBy === replacement.memoryId),
    'old memory REPLACED and linked to the new ACTIVE memory', revisionState);
  const visibleRevision = await page.$eval('.diagram-memory-panel', (panel, markers) => {
    const text = panel.textContent ?? '';
    return markers.every((marker) => text.includes(marker));
  }, [saveMarker, revisedMarker]).catch(() => false);
  check('browser memory list reflects the persisted revision', visibleRevision, [saveMarker, revisedMarker], visibleRevision);
  await shot(page, '04-natural-revision.png');

  const crossPrompt = 'Buscá en otros diagramas la regla del libro mayor T3. ¿Qué código de liquidación registra y en qué proyecto y diagrama aparece?';
  const crossAnswer = await askChat(page, crossPrompt, target.id);
  report.commonAccessSearch = { prompt: crossPrompt, sourceProject: external.project.name, sourceView: external.view.name, answer: crossAnswer };
  await verifyPersistedAnswer('explicit common-access cross-diagram search', crossAnswer,
    [external.marker, external.project.name, external.view.name]);
  check('shared answer identifies the external fixture origin and its fact',
    crossAnswer.visibleBody.includes(external.marker)
      && crossAnswer.visibleBody.includes(external.project.name)
      && crossAnswer.visibleBody.includes(external.view.name),
    { marker: external.marker, project: external.project.name, diagram: external.view.name },
    crossAnswer.visibleBody);
  await shot(page, '05-common-access-cross-diagram-search.png');
  return results.every((result) => result.ok);
}

let succeeded = false;
try {
  succeeded = await main();
} catch (error) {
  report.exception = `${error.name}: ${error.message}`;
  check('one bounded browser/model acceptance pass completed', false, 'all requested interactions completed', report.exception);
  if (page) {
    try {
      await shot(page, '99-failure.png');
    } catch {
      // Browser may already have closed.
    }
  }
} finally {
  await cleanupCreatedData();
  if (browser) await browser.close();
}

const failed = results.filter((result) => !result.ok);
report.failed = failed.length;
report.verdict = report.unavailable ?? (failed.length === 0 ? 'PASS' : 'FAIL');
console.log(`\n[summary] ${results.length - failed.length}/${results.length} checks passed, ${failed.length} failed`);
console.log(`[screenshots] ${screenshots.join(', ')}`);
console.log(JSON.stringify(report, null, 2));
process.exitCode = report.unavailable ? 2 : (succeeded && failed.length === 0 ? 0 : 1);
