/**
 * Real-browser verification of the Review section.
 *
 * Seeds a model with a generalization cycle A -> B -> C -> A, opens the editor,
 * runs Review, and checks:
 *   - UML validation reports GENERALIZATION_CYCLE with human names, no UUIDs;
 *   - backend readiness is Not ready with a blocking diagnostic;
 *   - "Explain with AI" opens the existing assistant with a prefilled prompt
 *     that contains the findings and no UUIDs (it never auto-submits).
 *
 * Usage:
 *   node scripts/verify-review-browser.mjs [--app <url>] [--backend <url>] [--out <dir>]
 *
 * Requires the web dev server, the backend/DB, and the assistant enabled for
 * the optional AI answer step. The deterministic Review checks do not need the model.
 */

import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const APP = readOption('--app') ?? process.env.APP_URL ?? 'http://localhost:5173';
const BACKEND = readOption('--backend') ?? process.env.BACKEND_URL ?? 'http://localhost:18092';
const OUT = path.resolve(
  readOption('--out') ?? fileURLToPath(new URL('../../docs/capturas/review', import.meta.url)),
);
const SESSION_KEY = 'class-diagrammer.session.v1';
const AI_TIMEOUT = Number(process.env.AI_TIMEOUT_MS ?? 240000);
const UUID_PATTERN = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i;

function readOption(name) {
  const index = process.argv.indexOf(name);
  if (index === -1) return undefined;
  const value = process.argv[index + 1];
  if (!value || value.startsWith('--')) throw new Error(`Missing value for ${name}`);
  return value;
}

const results = [];
const findings = [];
const screenshots = [];

function check(step, ok, expected, observed) {
  results.push({ step, ok, expected, observed });
  console.log(`[${ok ? 'OK' : 'FAIL'}] ${step}`);
  console.log(`  expected: ${JSON.stringify(expected)}`);
  console.log(`  observed: ${JSON.stringify(observed)}`);
  return ok;
}

async function api(pathname, { method = 'GET', token, body } = {}) {
  const response = await fetch(`${BACKEND}${pathname}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* empty body */ }
  if (!response.ok) {
    throw new Error(`${method} ${pathname} -> ${response.status}: ${text.slice(0, 400)}`);
  }
  return json;
}

function envelope(project, model, type, payload, targets = []) {
  return {
    operationId: crypto.randomUUID(),
    type,
    author: { userId: project.userId, externalSubject: project.username },
    projectId: project.id,
    modelId: model.id,
    baseRevision: model.revision,
    targets,
    preconditions: [],
    payload,
    origin: 'MANUAL',
    causalParentOperationId: null,
    idempotencyKey: crypto.randomUUID(),
  };
}

async function command(project, type, payload, targets = []) {
  const current = await api(`/projects/${project.id}/model`, { token: project.token });
  await api(`/projects/${project.id}/commands`, {
    method: 'POST',
    token: project.token,
    body: envelope(project, current, type, payload, targets),
  });
  return api(`/projects/${project.id}/model`, { token: project.token });
}

async function seedCycle() {
  const suffix = crypto.randomUUID().slice(0, 8);
  const username = `review-browser-${suffix}`;
  const password = `Review-${suffix}-Pass!`;
  const user = await api('/auth/register', {
    method: 'POST',
    body: { username, password, displayName: 'Review Verifier' },
  });
  const login = await api('/auth/login', { method: 'POST', body: { username, password } });
  const project = await api('/projects', {
    method: 'POST',
    token: login.token,
    body: { name: `Review cycle ${suffix}`, description: 'Generalization cycle A -> B -> C -> A' },
  });
  const context = { ...project, token: login.token, userId: user.id, username };
  let model = await api(`/projects/${project.id}/model`, { token: login.token });

  const classes = ['A', 'B', 'C'];
  const ids = [];
  for (const name of classes) {
    const id = crypto.randomUUID();
    ids.push(id);
    model = await command(context, 'classifier.create', { id, name, kind: 'CLASS' });
  }

  for (let index = 0; index < 3; index += 1) {
    const sourceId = ids[index];
    const targetId = ids[(index + 1) % 3];
    const relationshipId = crypto.randomUUID();
    model = await command(context, 'relationship.create', {
      id: relationshipId,
      kind: 'GENERALIZATION',
      source: { classifierId: sourceId },
      target: { classifierId: targetId },
    }, [
      { scope: 'classifier', id: sourceId },
      { scope: 'classifier', id: targetId },
    ]);
  }

  return {
    session: {
      token: login.token,
      userId: user.id,
      username: user.username,
      displayName: user.displayName,
      expiresAt: login.expiresAt,
    },
    projectId: project.id,
    projectName: project.name,
    versionId: model.versionId,
    revision: model.revision,
    cycles: ['A -> B', 'B -> C', 'C -> A'],
  };
}

async function waitForStableModel(projectId, token) {
  // Authoritative settle check: poll the persisted revision so the geometry commands
  // issued by the editor after load are included, then require 2 s without changes.
  const deadline = Date.now() + 60000;
  let previous = null;
  let stableSince = Date.now();
  while (Date.now() < deadline) {
    const model = await api(`/projects/${projectId}/model`, { token });
    if (model.revision === previous) {
      if (Date.now() - stableSince >= 2000) {
        return model.revision;
      }
    } else {
      previous = model.revision;
      stableSince = Date.now();
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('the persisted model revision did not stabilize');
}

async function waitForStableEditor(page) {
  // The editor persists default geometry when it opens (classes/relationships), which
  // advances the model revision. Wait until the chip is Saved and the revision is stable
  // for 1.5 s, so the assistant does not send a stale baseRevision.
  const deadline = Date.now() + 60000;
  let previous = null;
  let stableSince = Date.now();
  while (Date.now() < deadline) {
    const snapshot = await page.evaluate(() => ({
      status: document.querySelector('.revision-status')?.textContent?.trim() ?? null,
      chip: document.querySelector('.revision-chip')?.textContent?.trim() ?? null,
    }));
    if (snapshot.status === 'Saved' && snapshot.chip === previous) {
      if (Date.now() - stableSince >= 1500) {
        return snapshot;
      }
    } else {
      previous = snapshot.chip;
      stableSince = Date.now();
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('the editor did not reach a stable saved revision');
}

async function waitForAssistantOutcome(page) {
  await page.waitForFunction(() => {
    const panel = document.querySelector('.assistant-panel');
    return Boolean(panel?.querySelector('.generation-run-state')
      || panel?.querySelector('.generation-actions')
      || panel?.querySelector('.notice-error')
      || panel?.querySelector('#assistant-clarification'));
  }, { timeout: AI_TIMEOUT, polling: 500 });
  return page.evaluate(() => {
    const panel = document.querySelector('.assistant-panel');
    return {
      runState: panel?.querySelector('.generation-run-state')?.textContent?.trim() ?? null,
      hasPreview: Boolean(panel?.querySelector('.generation-actions')),
      error: panel?.querySelector('.notice-error')?.textContent?.trim() ?? null,
      clarify: panel?.querySelector('#assistant-clarification') ? 'clarification requested' : null,
    };
  });
}

async function clickRetry(page) {
  return page.evaluate(() => {
    const button = Array.from(document.querySelectorAll('.assistant-panel .notice-error .text-button'))
      .find((candidate) => (candidate.textContent ?? '').trim() === 'Reintentar');
    if (!button) return false;
    button.click();
    return true;
  });
}

async function textOf(page, selector) {
  return page.evaluate((sel) => document.querySelector(sel)?.textContent?.trim() ?? null, selector);
}

async function clickByText(page, selector, text) {
  const clicked = await page.evaluate(({ sel, expected }) => {
    const element = Array.from(document.querySelectorAll(sel))
      .find((candidate) => (candidate.textContent ?? '').trim() === expected);
    if (!element || element.disabled) return false;
    element.click();
    return true;
  }, { sel: selector, expected: text });
  return clicked;
}

await mkdir(OUT, { recursive: true });
console.log(`[run] app=${APP} backend=${BACKEND} out=${OUT}`);

const seeded = await seedCycle();
console.log(`[seed] project=${seeded.projectId} version=${seeded.versionId} revision=${seeded.revision}`);

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--window-size=1500,1000', '--disable-infobars', '--no-first-run'],
});
const page = await browser.newPage();
await page.setViewport({ width: 1440, height: 900 });

const report = { app: APP, backend: BACKEND, seed: seeded, results, findings, screenshots, steps: {} };

try {
  await page.goto(`${APP}/`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.evaluate((key, value) => localStorage.setItem(key, JSON.stringify(value)), SESSION_KEY, seeded.session);
  await page.goto(`${APP}/`, { waitUntil: 'networkidle2', timeout: 60000 });
  await page.waitForSelector('a[href^="/projects/"]', { timeout: 30000 });
  await page.click('a[href^="/projects/"]');
  await page.waitForSelector('.floating-assistant', { timeout: 60000 });
  await page.waitForSelector('.editor-options-bar', { timeout: 60000 });
  await waitForStableEditor(page);
  const settledRevision = await waitForStableModel(seeded.projectId, seeded.session.token);
  report.steps.settledRevision = settledRevision;

  const tourAndFab = await page.evaluate(() => {
    const tour = document.querySelector('.editor-tour-overlay');
    const fab = document.querySelector('.floating-assistant');
    if (!fab) {
      return { tourExists: Boolean(tour), fabExists: false, hitFabCenter: false, hitTarget: null };
    }
    const rect = fab.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
    return {
      tourExists: Boolean(tour),
      fabExists: true,
      hitFabCenter: hit === fab,
      hitTarget: hit ? `${hit.tagName.toLowerCase()}${typeof hit.className === 'string' && hit.className
        ? `.${hit.className.trim().split(/\s+/).join('.')}` : ''}` : null,
    };
  });
  check('the onboarding tour is visible after the editor stabilizes', tourAndFab.tourExists,
    '.editor-tour-overlay exists', tourAndFab.tourExists);
  check('the floating assistant button is present beside the tour', tourAndFab.fabExists,
    '.floating-assistant exists', tourAndFab.fabExists);
  check('the tour does not intercept the floating assistant center', tourAndFab.hitFabCenter,
    'document.elementFromPoint(FAB center) is .floating-assistant, not the tour', tourAndFab);

  await page.click('[data-section="review"]');
  await page.waitForSelector('.review-panel', { timeout: 30000 });
  const reviewTourState = await page.evaluate(() => ({
    reviewOpen: Boolean(document.querySelector('.review-panel')),
    tourVisible: Boolean(document.querySelector('.editor-tour-overlay')),
  }));
  check('the tour is hidden while the Review panel is open',
    reviewTourState.reviewOpen && !reviewTourState.tourVisible,
    '.review-panel open and .editor-tour-overlay hidden', reviewTourState);
  await page.waitForFunction(() => {
    const panel = document.querySelector('.review-panel');
    return Boolean(panel?.querySelector('.review-summary') || panel?.querySelector('.review-error'));
  }, { timeout: 60000, polling: 250 });

  const review = await page.evaluate(() => {
    const panel = document.querySelector('.review-panel');
    const text = panel?.textContent ?? '';
    return {
      error: panel?.querySelector('.review-error')?.textContent?.trim() ?? null,
      summary: panel?.querySelector('.review-summary')?.textContent?.trim() ?? null,
      badge: panel?.querySelector('.review-readiness-badge')?.textContent?.trim() ?? null,
      profile: panel?.querySelector('.review-profile')?.textContent?.trim() ?? null,
      errors: Array.from(panel?.querySelectorAll('.review-findings--error li') ?? [])
        .map((item) => item.textContent?.trim() ?? ''),
      warnings: Array.from(panel?.querySelectorAll('.review-findings--warning li') ?? [])
        .map((item) => item.textContent?.trim() ?? ''),
      blockers: Array.from(panel?.querySelectorAll('.review-findings--blocker li') ?? [])
        .map((item) => item.textContent?.trim() ?? ''),
      hasUuid: /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i.test(text),
      clean: Boolean(panel?.querySelector('.review-clean')),
    };
  });
  report.steps.review = review;

  check('Review loads without a transport error', review.error === null, 'review summary without error', review);
  check('UML validation reports the generalization cycle',
    review.errors.some((item) => item.includes('GENERALIZATION_CYCLE')),
    'an ERROR containing GENERALIZATION_CYCLE',
    review.errors);
  check('the cycle finding names the classes instead of ids',
    review.errors.some((item) => /\b[ABC]\b/.test(item)) && !review.hasUuid,
    'class names and no UUID',
    { errors: review.errors, hasUuid: review.hasUuid });
  check('backend readiness reports the model as not ready',
    review.badge === 'Not ready',
    'Not ready',
    { badge: review.badge, profile: review.profile });
  check('backend readiness shows a blocking diagnostic',
    review.blockers.length > 0,
    'at least one blocking diagnostic',
    review.blockers);
  await page.screenshot({ path: path.join(OUT, 'review-01-findings.png') });
  screenshots.push(path.join(OUT, 'review-01-findings.png'));

  const explainClicked = await clickByText(page, '.review-ai-actions button', 'Explain with AI');
  check('Explain with AI is available to the owner', explainClicked, 'clickable Explain with AI', explainClicked);
  await page.waitForSelector('#assistant-utterance', { timeout: 30000 });
  const prefill = await page.$eval('#assistant-utterance', (input) => input.value);
  report.steps.prefill = prefill;
  check('the assistant draft is prefilled with the findings',
    prefill.includes('GENERALIZATION_CYCLE') && prefill.includes('A') && prefill.includes('B') && prefill.includes('C'),
    'prefill contains GENERALIZATION_CYCLE and the class names',
    prefill.slice(0, 500));
  check('the assistant draft contains no UUID', !UUID_PATTERN.test(prefill), 'no UUID in the prompt', prefill.slice(0, 500));
  check('the assistant draft does not auto-submit',
    await page.evaluate(() => document.querySelector('.assistant-panel .generation-run-state') === null
      && document.querySelector('.assistant-panel .generation-actions') === null),
    'assistant remains idle with the draft',
    await textOf(page, '.assistant-panel'));
  await page.screenshot({ path: path.join(OUT, 'review-02-ai-prefill.png') });
  screenshots.push(path.join(OUT, 'review-02-ai-prefill.png'));

  // Optional AI answer: the deterministic review already passed. The local 2B may
  // answer with an explanation or a plan; either is a real response, captured as is.
  const submit = await page.$('.assistant-panel form.chat-compose-form button[type="submit"]');
  if (submit) {
    await submit.click();
    let aiOutcome = null;
    try {
      aiOutcome = await waitForAssistantOutcome(page);
      if (aiOutcome?.error?.includes('El diagrama cambió')) {
        // One explicit retry after the editor settles: the geometry persistence finished
        // after the first click in the automated run.
        await waitForStableEditor(page);
        await waitForStableModel(seeded.projectId, seeded.session.token);
        const retried = await clickRetry(page);
        if (retried) {
          aiOutcome = await waitForAssistantOutcome(page);
        }
      }
    } catch (error) {
      aiOutcome = { timeout: `${error.name}: ${error.message}` };
    }
    report.steps.aiOutcome = aiOutcome;
    check('the AI returns a response or an explicit error',
      Boolean(aiOutcome?.runState || aiOutcome?.hasPreview || aiOutcome?.error || aiOutcome?.clarify || aiOutcome?.timeout),
      'an explanation, plan preview, clarification or error',
      aiOutcome);
    await page.screenshot({ path: path.join(OUT, 'review-03-ai-outcome.png') });
    screenshots.push(path.join(OUT, 'review-03-ai-outcome.png'));
  } else {
    findings.push({ code: 'AI_SUBMIT_CONTROL_MISSING', detail: 'The assistant submit button was not found.' });
  }
} catch (error) {
  check('review browser verification completed without an unexpected exception', false,
    'no exception', `${error.name}: ${error.message}`);
  report.exception = `${error.name}: ${error.message}`;
  try {
    await page.screenshot({ path: path.join(OUT, 'review-99-failure.png') });
    screenshots.push(path.join(OUT, 'review-99-failure.png'));
  } catch {
    // page may be closed
  }
} finally {
  await browser.close();
}

const failed = results.filter((result) => !result.ok);
report.failed = failed.length;
console.log(`\n[summary] ${results.length - failed.length}/${results.length} checks passed, ${failed.length} failed, ${findings.length} finding(s)`);
console.log(`[screenshots] ${screenshots.join(', ')}`);
console.log(JSON.stringify(report, null, 2));
process.exitCode = failed.length === 0 ? 0 : 1;
