/**
 * Independent browser verification of feature `vista-compacta-editor` (VC-06).
 * Seeds a real user/project/class through the backend API, then measures real
 * geometry in Chrome. Writes screenshots to docs/capturas/vista-compacta and
 * prints a JSON report to stdout. Read-only against web/src.
 */
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import puppeteer from 'puppeteer-core';

const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:18090';
const APP = process.env.APP_URL ?? 'http://localhost:5173';
const OUT = path.resolve(new URL('../..', import.meta.url).pathname.replace(/^\/([A-Z]):/, '$1:'), 'docs', 'capturas', 'vista-compacta');
const SESSION_KEY = 'class-diagrammer.session.v1';

const report = { seed: {}, items: {}, screenshots: [], errors: [] };

async function api(pathname, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${BACKEND}${pathname}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* no body */ }
  if (!res.ok && res.status !== 204) {
    throw new Error(`${method} ${pathname} -> ${res.status}: ${text.slice(0, 300)}`);
  }
  return json;
}

async function seed() {
  const suffix = crypto.randomUUID().slice(0, 8);
  const username = `vce-verify-${suffix}`;
  const password = `Vce-${suffix}-Pass!`;
  const user = await api('/auth/register', { method: 'POST', body: { username, password, displayName: 'VCE Verifier' } });
  const login = await api('/auth/login', { method: 'POST', body: { username, password } });
  const token = login.token;
  const project = await api('/projects', { method: 'POST', token, body: { name: `VCE verify ${suffix}`, description: null } });
  const model = await api(`/projects/${project.id}/model`, { token });
  const envelope = {
    operationId: crypto.randomUUID(),
    type: 'classifier.create',
    author: { userId: user.id, externalSubject: 'vce-verifier' },
    projectId: project.id,
    modelId: model.id,
    baseRevision: model.revision,
    targets: [],
    preconditions: [],
    payload: { id: crypto.randomUUID(), name: 'Customer', kind: 'CLASS', namespaceId: null },
    origin: 'MANUAL',
    causalParentOperationId: null,
    idempotencyKey: crypto.randomUUID(),
  };
  await api(`/projects/${project.id}/commands`, { method: 'POST', token, body: envelope });
  report.seed = { username, projectId: project.id, projectName: project.name, backend: BACKEND };
  return { token, userId: user.id, username: user.username, displayName: user.displayName, expiresAt: login.expiresAt, projectId: project.id };
}

const pageRectHelper = `
  window.__rectOf = (el) => {
    const r = el.getBoundingClientRect();
    return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height };
  };
`;

async function measureDialog(page) {
  return page.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"][aria-modal="true"]');
    const backdrop = document.querySelector('.editor-modal-backdrop');
    const r = dialog.getBoundingClientRect();
    const active = document.activeElement;
    const panels = Array.from(document.querySelectorAll('.editor-modal-body .surface, .editor-modal-body .generation-panel'));
    const activeId = active && active.id ? `#${active.id}` : null;
    return {
      dialogCount: document.querySelectorAll('[role="dialog"]').length,
      modalDialogCount: document.querySelectorAll('[role="dialog"][aria-modal="true"]').length,
      rect: { top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height },
      viewport: { w: window.innerWidth, h: window.innerHeight, scrollY: window.scrollY },
      scrollHeight: dialog.scrollHeight,
      clientHeight: dialog.clientHeight,
      bodyOverflow: document.body.style.overflow,
      activeElement: {
        tag: active ? active.tagName : null,
        ariaLabel: active ? active.getAttribute('aria-label') : null,
        id: activeId,
        inDialog: dialog.contains(active),
        datasetSection: active && active.dataset ? (active.dataset.section ?? null) : null,
      },
      ariaLabel: dialog.getAttribute('aria-label'),
      ariaLabelledby: dialog.getAttribute('aria-labelledby'),
      backdropZ: backdrop ? getComputedStyle(backdrop).zIndex : null,
      panels: panels.map((p) => ({
        cls: p.className,
        borderTopWidth: getComputedStyle(p).borderTopWidth,
        boxShadow: getComputedStyle(p).boxShadow,
      })),
      titles: {
        xmi: !!document.querySelector('#xmi-import-title'),
        presence: !!document.querySelector('#presence-title'),
        chat: !!document.querySelector('#chat-title'),
        members: !!document.querySelector('#members-title'),
        generation: !!document.querySelector('#generation-title'),
        assistant: document.querySelector('#assistant-title')?.textContent ?? null,
      },
    };
  });
}

async function main() {
  await mkdir(OUT, { recursive: true });
  const session = await seed();
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--window-size=1500,1000', '--disable-infobars'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  await page.evaluateOnNewDocument(pageRectHelper);

  // NOTE: a hard navigation to /projects/<id> is intercepted by the Vite proxy
  // ('/projects' -> backend), so the editor must be reached via client-side
  // routing: land on the projects list and click the project's Open link.
  await page.goto(`${APP}/`, { waitUntil: 'domcontentloaded' });
  await page.evaluate((key, value) => localStorage.setItem(key, JSON.stringify(value)), SESSION_KEY, session);
  const openEditorViaSpa = async () => {
    await page.goto(`${APP}/`, { waitUntil: 'networkidle2', timeout: 60000 });
    await page.waitForFunction(() => document.querySelector('a[href^="/projects/"]'), { timeout: 30000 });
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
      page.click('a[href^="/projects/"]'),
    ]);
    await page.waitForFunction(() => document.querySelector('.react-flow__node') && document.querySelector('.uml-class-card'), { timeout: 30000 });
  };
  await openEditorViaSpa();
  await new Promise((r) => setTimeout(r, 2500)); // let presence/SSE settle

  const shot = async (name) => {
    const file = path.join(OUT, name);
    await page.screenshot({ path: file });
    report.screenshots.push(file);
  };

  // ---- A / B: at rest, 1440x900 ----
  const rest = await page.evaluate(() => {
    const direct = document.querySelectorAll('.editor-wrap > .generation-panel, .editor-wrap > .surface, .editor-wrap > .members-panel, .editor-wrap > .presence-panel, .editor-wrap > .chat-panel');
    const bar = document.querySelector('.editor-options-bar');
    const ws = document.querySelector('.editor-workspace');
    const wsRect = ws.getBoundingClientRect();
    const buttons = Array.from(bar.querySelectorAll('button'));
    const badges = Array.from(bar.querySelectorAll('.options-bar-badge'));
    return {
      directChildrenOfWrap: direct.length,
      bodyScrollHeight: document.body.scrollHeight,
      dialogsAtRest: document.querySelectorAll('[role="dialog"]').length,
      scrollY: window.scrollY,
      bar: { rect: window.__rectOf(bar), scrollWidth: bar.scrollWidth, clientWidth: bar.clientWidth },
      workspaceTop: wsRect.top,
      buttons: buttons.map((b) => ({
        section: b.dataset.section,
        label: b.querySelector('.options-bar-label')?.textContent ?? null,
        haspopup: b.getAttribute('aria-haspopup'),
        expanded: b.getAttribute('aria-expanded'),
        badge: b.querySelector('.options-bar-badge')?.textContent ?? null,
      })),
      badgeCount: badges.length,
    };
  });
  report.items.rest1440 = rest;
  await shot('editor-rest-1440x900.png');

  // ---- C: each section dialog ----
  const sections = ['xmi', 'collaboration', 'members', 'generation'];
  report.items.dialogs = {};
  for (const id of sections) {
    await page.click(`[data-section="${id}"]`);
    await page.waitForFunction(() => document.querySelectorAll('[role="dialog"][aria-modal="true"]').length === 1, { timeout: 10000 });
    await new Promise((r) => setTimeout(r, 600));
    report.items.dialogs[id] = await measureDialog(page);
    await shot(`dialog-${id}-1440x900.png`);
    const overflowBefore = report.items.dialogs[id].bodyOverflow;
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelectorAll('[role="dialog"]').length === 0, { timeout: 10000 });
    await new Promise((r) => setTimeout(r, 300));
    report.items.dialogs[id].afterEscape = await page.evaluate((sid) => ({
      dialogCount: document.querySelectorAll('[role="dialog"]').length,
      bodyOverflow: document.body.style.overflow,
      focusSection: document.activeElement && document.activeElement.dataset ? (document.activeElement.dataset.section ?? null) : null,
      focusIsTrigger: document.activeElement?.dataset?.section === sid,
      scrollY: window.scrollY,
    }), id);
    report.items.dialogs[id].overflowBeforeEscape = overflowBefore;
  }

  // ---- D: floating assistant button ----
  const fab1 = await page.evaluate(() => {
    const fab = document.querySelector('.floating-assistant');
    const r = fab.getBoundingClientRect();
    const cx = r.left + r.width / 2;
    const cy = r.top + r.height / 2;
    const hit = document.elementFromPoint(cx, cy);
    return { rect: window.__rectOf(fab), z: getComputedStyle(fab).zIndex, centre: { x: cx, y: cy }, hitIsFab: hit === fab, hitClass: hit ? hit.className : null, scrollY: window.scrollY };
  });
  report.items.fabAtRest = fab1;

  // 15: with a dialog open, the veil must cover the FAB
  await page.click('[data-section="collaboration"]');
  await page.waitForFunction(() => document.querySelectorAll('[role="dialog"][aria-modal="true"]').length === 1, { timeout: 10000 });
  await new Promise((r) => setTimeout(r, 400));
  report.items.fabBehindVeil = await page.evaluate(() => {
    const fab = document.querySelector('.floating-assistant');
    const r = fab.getBoundingClientRect();
    const cx = r.left + r.width / 2;
    const cy = r.top + r.height / 2;
    const hit = document.elementFromPoint(cx, cy);
    return { fabZ: getComputedStyle(fab).zIndex, backdropZ: getComputedStyle(document.querySelector('.editor-modal-backdrop')).zIndex, hitIsFab: hit === fab, hitClass: hit ? hit.className : null };
  });
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => document.querySelectorAll('[role="dialog"]').length === 0, { timeout: 10000 });
  await new Promise((r) => setTimeout(r, 300));

  // 16: drag by (-120, -80)
  const beforeDrag = await page.evaluate(() => window.__rectOf(document.querySelector('.floating-assistant')));
  const startX = beforeDrag.left + beforeDrag.width / 2;
  const startY = beforeDrag.top + beforeDrag.height / 2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  const steps = 6;
  for (let i = 1; i <= steps; i += 1) {
    await page.mouse.move(startX - (120 * i) / steps, startY - (80 * i) / steps);
  }
  await page.mouse.up();
  await new Promise((r) => setTimeout(r, 400));
  const afterDrag = await page.evaluate(() => window.__rectOf(document.querySelector('.floating-assistant')));
  report.items.drag = {
    before: beforeDrag,
    after: afterDrag,
    delta: { x: afterDrag.left - beforeDrag.left, y: afterDrag.top - beforeDrag.top },
    dialogsAfterDrag: await page.evaluate(() => document.querySelectorAll('[role="dialog"]').length),
  };

  // 18: a plain tap opens the assistant
  const fabNow = await page.evaluate(() => window.__rectOf(document.querySelector('.floating-assistant')));
  await page.mouse.move(fabNow.left + fabNow.width / 2, fabNow.top + fabNow.height / 2);
  await page.mouse.down();
  await new Promise((r) => setTimeout(r, 80));
  await page.mouse.up();
  await page.waitForFunction(() => document.querySelectorAll('[role="dialog"][aria-modal="true"]').length === 1, { timeout: 10000 });
  await new Promise((r) => setTimeout(r, 500));
  report.items.assistantDialog = await measureDialog(page);
  await shot('dialog-assistant-1440x900.png');

  // 19: Escape restores focus to the FAB
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => document.querySelectorAll('[role="dialog"]').length === 0, { timeout: 10000 });
  await new Promise((r) => setTimeout(r, 300));
  report.items.assistantFocusRestore = await page.evaluate(() => ({
    dialogCount: document.querySelectorAll('[role="dialog"]').length,
    bodyOverflow: document.body.style.overflow,
    activeIsFab: document.activeElement?.classList?.contains('floating-assistant') ?? false,
    activeTag: document.activeElement?.tagName ?? null,
    activeAriaLabel: document.activeElement?.getAttribute('aria-label') ?? null,
  }));

  // 20: position persistence across a full reload (via SPA re-entry, since a
  // hard navigation to /projects/<id> would be proxied to the backend).
  await openEditorViaSpa();
  await new Promise((r) => setTimeout(r, 1500));
  report.items.persistence = await page.evaluate((beforeLeft, beforeTop) => {
    const fab = document.querySelector('.floating-assistant');
    const r = fab.getBoundingClientRect();
    return {
      rectAfterReload: window.__rectOf(fab),
      deltaFromBeforeReload: { x: r.left - beforeLeft, y: r.top - beforeTop },
      stored: localStorage.getItem('uml-editor.floating-assistant.position'),
      scrollY: window.scrollY,
    };
  }, afterDrag.left, afterDrag.top);

  // ---- E: narrow viewport 720x800 ----
  await page.setViewport({ width: 720, height: 800 });
  await new Promise((r) => setTimeout(r, 800));
  report.items.rest720 = await page.evaluate(() => {
    const bar = document.querySelector('.editor-options-bar');
    return {
      scrollY: window.scrollY,
      bar: { scrollWidth: bar.scrollWidth, clientWidth: bar.clientWidth, rect: window.__rectOf(bar) },
      docScrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
      bodyScrollHeight: document.body.scrollHeight,
      directChildrenOfWrap: document.querySelectorAll('.editor-wrap > .generation-panel, .editor-wrap > .surface, .editor-wrap > .members-panel, .editor-wrap > .presence-panel, .editor-wrap > .chat-panel').length,
      fabRect: window.__rectOf(document.querySelector('.floating-assistant')),
    };
  });
  await shot('editor-rest-720x800.png');
  report.items.dialogs720 = {};
  for (const id of sections) {
    await page.click(`[data-section="${id}"]`);
    await page.waitForFunction(() => document.querySelectorAll('[role="dialog"][aria-modal="true"]').length === 1, { timeout: 10000 });
    await new Promise((r) => setTimeout(r, 500));
    report.items.dialogs720[id] = await measureDialog(page);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelectorAll('[role="dialog"]').length === 0, { timeout: 10000 });
    await new Promise((r) => setTimeout(r, 250));
  }

  await browser.close();
  console.log(JSON.stringify(report, null, 2));
}

main().catch((error) => {
  report.errors.push(String(error && error.stack ? error.stack : error));
  console.log(JSON.stringify(report, null, 2));
  process.exitCode = 1;
});
