/**
 * T4 real-browser verification of feature `rutas-ortogonales-ajustables` (ROR-08).
 * Seeds a throwaway user/project (two classifiers, one association, staggered
 * rectangles, routing ORTHOGONAL) through the HTTP API only, then drives real
 * Chrome: selects the orthogonal relationship, asserts the corridor/bend handles,
 * drags the corridor handle with MULTIPLE pointer steps (sampling the edge path
 * mid-drag to catch a gesture frozen after the first movement), adds a bend with
 * the `+` handle, and reloads to prove the persisted shape survives.
 *
 * Screenshots go to docs/capturas/rutas-ortogonales; a JSON report with the
 * measured `d` strings, persisted routeAdjustments and handle counts goes to stdout.
 * Read-only against web/src.
 */
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import puppeteer from 'puppeteer-core';

const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:18090';
const APP = process.env.APP_URL ?? 'http://localhost:5173';
const OUT = path.resolve(new URL('../..', import.meta.url).pathname.replace(/^\/([A-Z]):/, '$1:'), 'docs', 'capturas', 'rutas-ortogonales');
const SESSION_KEY = 'class-diagrammer.session.v1';

const SOURCE_RECT = { x: 36, y: 12, w: 120, h: 120 }; // center 96,72
const TARGET_RECT = { x: 396, y: 160, w: 120, h: 120 }; // center 456,220

const report = {
  seed: {}, zoom: null, flowToScreenCheck: null,
  handles: {}, drag: {}, persisted: {}, bend: {}, reload: {},
  screenshots: [], steps: {}, errors: [],
};

function fail(step, message) {
  throw new Error(`STEP ${step}: ${message}`);
}

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
  // STEP 1: seed through the HTTP API only, into a throwaway project.
  const suffix = crypto.randomUUID().slice(0, 8);
  const username = `ror-verify-${suffix}`;
  const password = `Ror-${suffix}-Pass!`;
  const user = await api('/auth/register', { method: 'POST', body: { username, password, displayName: 'ROR Verifier' } });
  const login = await api('/auth/login', { method: 'POST', body: { username, password } });
  const token = login.token;
  const project = await api('/projects', { method: 'POST', token, body: { name: `ROR verify ${suffix}`, description: null } });

  const command = async (type, payload, targets = [], preconditions = []) => {
    const model = await api(`/projects/${project.id}/model`, { token });
    return api(`/projects/${project.id}/commands`, {
      method: 'POST',
      token,
      body: {
        operationId: crypto.randomUUID(),
        type,
        author: { userId: user.id, externalSubject: 'ror-verifier' },
        projectId: project.id,
        modelId: model.id,
        baseRevision: model.revision,
        targets,
        preconditions,
        payload,
        origin: 'MANUAL',
        causalParentOperationId: null,
        idempotencyKey: crypto.randomUUID(),
      },
    });
  };

  const sourceId = crypto.randomUUID();
  const targetId = crypto.randomUUID();
  const relationshipId = crypto.randomUUID();
  await command('classifier.create', { id: sourceId, name: 'Customer', kind: 'CLASS', namespaceId: null });
  await command('classifier.create', { id: targetId, name: 'Invoice', kind: 'CLASS', namespaceId: null });
  await command('relationship.create', {
    id: relationshipId,
    kind: 'ASSOCIATION',
    name: null,
    source: { classifierId: sourceId },
    target: { classifierId: targetId },
  }, [
    { scope: 'classifier', id: sourceId },
    { scope: 'classifier', id: targetId },
  ]);
  const model = await api(`/projects/${project.id}/model`, { token });
  const viewId = model.views.find((view) => view.is_default)?.id;
  if (!viewId) fail(1, 'seeded project has no default view');
  await command('geometry.set', { targetType: 'CLASSIFIER', viewId, targetId: sourceId, x: SOURCE_RECT.x, y: SOURCE_RECT.y, w: SOURCE_RECT.w, h: SOURCE_RECT.h, zOrder: 1 });
  await command('geometry.set', { targetType: 'CLASSIFIER', viewId, targetId: targetId, x: TARGET_RECT.x, y: TARGET_RECT.y, w: TARGET_RECT.w, h: TARGET_RECT.h, zOrder: 1 });
  await command('style.set', { viewId, targetId: relationshipId, routing: 'ORTHOGONAL', cornerRadius: 0, strokeWidth: 2, dashed: false });

  const finalModel = await api(`/projects/${project.id}/model`, { token });
  const styleRow = (finalModel.view_style ?? []).find((row) => row.targetId === relationshipId);
  if (!styleRow || styleRow.routing !== 'ORTHOGONAL') {
    fail(1, `style.set round trip failed: view_style row for the relationship is ${JSON.stringify(styleRow)}`);
  }
  if ((finalModel.classifiers ?? []).length !== 2 || (finalModel.relationships ?? []).length !== 1) {
    fail(1, `seeded model has ${finalModel.classifiers?.length} classifiers and ${finalModel.relationships?.length} relationships`);
  }

  // Horizontal frame expectations derived from the seeded rectangles (orthogonal-routing.ts):
  // uS = source right edge = 156, uT = target left edge = 396, autoCorridor = 276,
  // vS = 72, vT = 220, levelMid = 146, dominant axis H (|dx|=360 >= |dy|=148).
  report.seed = {
    username, projectId: project.id, projectName: project.name,
    relationshipId, viewId, backend: BACKEND, app: APP, suffix,
    sourceRect: SOURCE_RECT, targetRect: TARGET_RECT,
    expected: { axis: 'H', uS: 156, uT: 396, autoCorridor: 276, vS: 72, vT: 220, levelMid: 146 },
    seededStyleRow: styleRow,
  };
  return {
    token, userId: user.id, username: user.username, displayName: user.displayName,
    expiresAt: login.expiresAt, projectId: project.id,
  };
}

const pageFlow = `
  window.__flow = (relationshipId, sourceId) => {
    const edge = document.querySelector('.react-flow__edge[data-id="' + relationshipId + '"]');
    const path = edge ? edge.querySelector('path.react-flow__edge-path') : null;
    const node = document.querySelector('.react-flow__node[data-id="' + sourceId + '"]');
    const viewport = document.querySelector('.react-flow__viewport');
    const matrix = viewport ? new DOMMatrixReadOnly(getComputedStyle(viewport).transform) : null;
    return {
      d: path ? path.getAttribute('d') : null,
      pathCount: edge ? edge.querySelectorAll('path.react-flow__edge-path').length : 0,
      edgeSelected: edge ? edge.classList.contains('selected') : false,
      nodeRect: node ? node.getBoundingClientRect().toJSON() : null,
      matrix: matrix ? { a: matrix.a, e: matrix.e, f: matrix.f } : null,
      corridorHandles: document.querySelectorAll('[aria-label="Move orthogonal corridor"]').length,
      levelHandles: document.querySelectorAll('[aria-label="Move orthogonal level"]').length,
      addButtons: document.querySelectorAll('[aria-label="Add connector bend"]').length,
      removeButtons: document.querySelectorAll('[aria-label="Remove connector bend"]').length,
      handleRects: Array.from(document.querySelectorAll('[aria-label="Move orthogonal corridor"]'))
        .map((el) => el.getBoundingClientRect().toJSON()),
    };
  };
`;

async function main() {
  await mkdir(OUT, { recursive: true });
  const session = await seed();
  const { relationshipId, viewId, suffix, sourceRect, expected } = report.seed;
  const sourceId = (await api(`/projects/${session.projectId}/model`, { token: session.token })).classifiers
    .find((classifier) => classifier.name === 'Customer').id;

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--window-size=1500,1000', '--disable-infobars'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  await page.evaluateOnNewDocument(pageFlow);

  // Instrumentation: record every command the editor sends (with its HTTP result),
  // so a persisted null can be traced to the exact payload and server verdict.
  report.commandsSent = [];
  report.failedRequests = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/commands')) {
      try { report.commandsSent.push(JSON.parse(request.postData() ?? '{}')); } catch { /* ignore */ }
    }
  });
  page.on('response', (response) => {
    const url = response.url();
    if (url.includes('/commands') || response.status() >= 400) {
      const entry = { url: url.replace(/^https?:\/\/[^/]+/, ''), status: response.status() };
      report.failedRequests.push(entry);
      if (url.includes('/commands')) {
        response.text().then((text) => { entry.body = text.slice(0, 500); }).catch(() => {});
      }
    }
  });
  report.pageErrors = [];
  page.on('pageerror', (error) => report.pageErrors.push(String(error)));
  page.on('console', (message) => {
    if (message.type() === 'error') report.pageErrors.push(`console.error: ${message.text()}`);
  });

  // NOTE (measured repo fact, same as verify-vista-compacta): reach the editor via
  // client-side routing; the data fetches carry the API Accept headers and the
  // document request is served by the SPA bypass in vite.config.ts.
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
    await new Promise((r) => setTimeout(r, 2500)); // fitView + presence/SSE settle
  };
  await openEditorViaSpa();

  const shot = async (name) => {
    const file = path.join(OUT, `${suffix}-${name}`);
    await page.screenshot({ path: file });
    report.screenshots.push(file);
  };
  const flow = () => page.evaluate((rel, src) => window.__flow(rel, src), relationshipId, sourceId);
  const edgeD = () => page.evaluate((rel) => {
    const edge = document.querySelector(`.react-flow__edge[data-id="${rel}"]`);
    return edge ? edge.querySelector('path.react-flow__edge-path')?.getAttribute('d') ?? null : null;
  }, relationshipId);

  // Validate the flow->screen mapping against a real measured node rect.
  const probe = await flow();
  if (!probe.matrix || !probe.nodeRect || !probe.d) {
    fail(2, `editor did not render the seeded model: ${JSON.stringify({ d: probe.d, matrix: probe.matrix, nodeRect: probe.nodeRect })}`);
  }
  const zoom = probe.matrix.a;
  // The node wrapper's measured rect must equal the seeded rectangle scaled by the
  // viewport zoom: that is the measured proof that screen = nodeTopLeft + (flow - rect.origin) * zoom.
  const sizeMatches = Math.abs(probe.nodeRect.width - sourceRect.w * zoom) < 2
    && Math.abs(probe.nodeRect.height - sourceRect.h * zoom) < 2;
  report.zoom = zoom;
  report.flowToScreenCheck = {
    nodeRect: probe.nodeRect,
    expectedNodeSize: { width: sourceRect.w * zoom, height: sourceRect.h * zoom },
    sizeMatches,
  };
  if (!sizeMatches) {
    fail(2, `flow->screen mapping check failed: ${JSON.stringify(report.flowToScreenCheck)}`);
  }
  const flowToScreen = (fx, fy) => ({
    x: probe.nodeRect.left + (fx - sourceRect.x) * zoom,
    y: probe.nodeRect.top + (fy - sourceRect.y) * zoom,
  });

  // STEP 2: handles absent BEFORE selection.
  report.handles.beforeSelection = {
    corridor: probe.corridorHandles, level: probe.levelHandles,
    add: probe.addButtons, remove: probe.removeButtons, edgeSelected: probe.edgeSelected,
  };
  if (probe.corridorHandles !== 0 || probe.addButtons !== 0 || probe.levelHandles !== 0) {
    fail(2, `handles are visible before the relationship is selected: ${JSON.stringify(report.handles.beforeSelection)}`);
  }

  // Select the relationship by clicking the edge path on its first horizontal
  // segment (midpoint of (156,72)->(276,72)).
  const clickPoint = flowToScreen((expected.uS + expected.autoCorridor) / 2, expected.vS);
  report.clickPoint = { flow: { x: (expected.uS + expected.autoCorridor) / 2, y: expected.vS }, screen: clickPoint, zoom };
  await page.mouse.click(clickPoint.x, clickPoint.y);
  await page.waitForFunction((rel) => {
    const edge = document.querySelector(`.react-flow__edge[data-id="${rel}"]`);
    return edge && edge.classList.contains('selected');
  }, { timeout: 10000 }, relationshipId).catch(() => {});
  const selected = await flow();
  if (!selected.edgeSelected) {
    fail(2, `clicking the edge path at ${JSON.stringify(clickPoint)} did not select the relationship`);
  }
  if (selected.corridorHandles !== 1 || selected.addButtons !== 1 || selected.levelHandles !== 0) {
    fail(2, `after selection expected 1 corridor handle + 1 add button + 0 level handles, got ${JSON.stringify({ corridor: selected.corridorHandles, level: selected.levelHandles, add: selected.addButtons })}`);
  }
  report.handles.afterSelection = {
    corridor: selected.corridorHandles, level: selected.levelHandles,
    add: selected.addButtons, remove: selected.removeButtons,
    corridorHandleRect: selected.handleRects[0] ?? null,
  };
  await shot('1-selected-before-drag.png');

  // STEP 3: multi-step drag of the corridor handle, sampling `d` mid-drag.
  const dBefore = selected.d;
  const handle = selected.handleRects[0];
  if (!handle) fail(3, 'corridor handle has no measured rect');
  const startX = handle.left + handle.width / 2;
  const startY = handle.top + handle.height / 2;
  const flowDxPerChunk = 24; // 96 flow px total (>= 72), snapped to the 24px ortho grid
  const screenDx = flowDxPerChunk * zoom;
  const CHUNKS = 4;
  const STEPS = 8;
  const midSamples = [];
  // Tag the exact handle element the gesture starts on, so a remount/re-created
  // handle is detectable mid-drag instead of being inferred from the freeze.
  await page.evaluate(() => {
    const handle = document.querySelector('[aria-label="Move orthogonal corridor"]');
    if (handle) handle.dataset.probeTag = 'drag-start';
  });
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  for (let chunk = 1; chunk <= CHUNKS; chunk += 1) {
    await page.mouse.move(startX + screenDx * chunk, startY, { steps: STEPS });
    await new Promise((r) => setTimeout(r, 60)); // let React commit the pointermove
    const probeChunk = await page.evaluate(() => {
      const tagged = document.querySelector('[data-probe-tag="drag-start"]');
      const current = document.querySelector('[aria-label="Move orthogonal corridor"]');
      return { taggedStillConnected: !!tagged, taggedIsCurrent: !!tagged && tagged === current };
    });
    const dMid = await edgeD();
    midSamples.push({ afterChunk: chunk, d: dMid, ...probeChunk });
    if (chunk === 2) {
      await shot('2-mid-drag.png');
    }
  }
  await page.mouse.up();
  await new Promise((r) => setTimeout(r, 300));
  const dAfterDrag = await edgeD();
  report.drag = {
    dBefore, dMid: midSamples, dAfter: dAfterDrag,
    flowDxPerChunk, totalFlowDx: flowDxPerChunk * CHUNKS, screenDxPerChunk: screenDx,
    stepsPerChunk: STEPS,
  };
  if (midSamples.length < 2) fail(3, 'fewer than two mid-drag samples were taken');
  if (midSamples[0].d === dBefore) {
    fail(3, `path did not change after the first drag chunk: dBefore=${dBefore} dMid1=${midSamples[0].d}`);
  }
  if (midSamples[1].d === midSamples[0].d) {
    fail(3, `path froze after the first movement (drag-guard defect): dMid1=${midSamples[0].d} dMid2=${midSamples[1].d}`);
  }
  if (dAfterDrag === dBefore) {
    fail(3, `path did not change across the whole drag: dBefore=${dBefore} dAfter=${dAfterDrag}`);
  }
  report.steps.step3_dragProgressive = true;
  await shot('3-after-drag.png');

  const pollModel = async (predicate, timeoutMs, label) => {
    const deadline = Date.now() + timeoutMs;
    let last = null;
    while (Date.now() < deadline) {
      const model = await api(`/projects/${session.projectId}/model`, { token: session.token });
      const row = (model.view_style ?? []).find((candidate) => candidate.targetId === relationshipId);
      last = row?.routeAdjustment ?? null;
      if (row && predicate(row)) return row;
      await new Promise((r) => setTimeout(r, 500));
    }
    fail(4, `timeout waiting for ${label}; last routeAdjustment: ${JSON.stringify(last)}`);
    return null;
  };

  // STEP 4: the drag must round-trip through style.set into view_style.
  // Final pointer flow-x = 276 + 96 = 372 -> grid round -> 384 -> offset 108.
  const expectedCorridorOffset = Math.round((expected.autoCorridor + flowDxPerChunk * CHUNKS) / 24) * 24 - expected.autoCorridor;
  const rowAfterDrag = await pollModel((row) => {
    const adjustment = row.routeAdjustment;
    return adjustment && adjustment.axis === 'H' && Array.isArray(adjustment.corridors) && adjustment.corridors.length === 1;
  }, 10000, 'routeAdjustment {axis H, one corridor} after the drag');
  report.persisted.afterDrag = rowAfterDrag.routeAdjustment;
  if (rowAfterDrag.routeAdjustment.corridors[0] !== expectedCorridorOffset) {
    fail(4, `persisted corridor offset ${rowAfterDrag.routeAdjustment.corridors[0]} != expected ${expectedCorridorOffset}`);
  }
  report.steps.step4_persistedAfterDrag = true;

  // STEP 5: the `+` handle adds a bend; corridors grow to 2, levels to 1.
  const addBend = await page.$('[aria-label="Add connector bend"]');
  if (!addBend) fail(5, 'the Add connector bend button is not rendered after the drag');
  await addBend.click();
  const rowAfterBend = await pollModel((row) => {
    const adjustment = row.routeAdjustment;
    return adjustment && adjustment.axis === 'H' && adjustment.corridors?.length === 2 && adjustment.levels?.length === 1;
  }, 10000, 'routeAdjustment {axis H, 2 corridors, 1 level} after Add connector bend');
  report.persisted.afterBend = rowAfterBend.routeAdjustment;
  if (rowAfterBend.routeAdjustment.levels[0] !== 0) {
    fail(5, `persisted level offset ${rowAfterBend.routeAdjustment.levels[0]} != expected 0 (vS != vT)`);
  }
  await new Promise((r) => setTimeout(r, 300));
  const afterBend = await flow();
  const bendCount = (d) => ((d ?? '').match(/[LQ]/g) ?? []).length;
  report.bend = {
    dAfterBend: afterBend.d,
    bendsBefore: bendCount(report.drag.dAfter),
    bendsAfter: bendCount(afterBend.d),
    handles: { corridor: afterBend.corridorHandles, level: afterBend.levelHandles, add: afterBend.addButtons, remove: afterBend.removeButtons },
  };
  if (report.bend.bendsAfter <= report.bend.bendsBefore) {
    fail(5, `rendered route did not gain bends: before=${report.bend.bendsBefore} after=${report.bend.bendsAfter}`);
  }
  if (afterBend.removeButtons !== 1) fail(5, 'the Remove connector bend button is not rendered with 2 corridors');
  report.steps.step5_bendAdded = true;
  await shot('4-after-bend.png');

  // STEP 6: the persisted shape survives a reload. A hard reload to /projects/<id>
  // is served by the SPA bypass (document requests send Accept: text/html); if the
  // reload ever lands on anything else, fall back to SPA re-entry and say so.
  let reloadMethod = 'page.reload()';
  await page.reload({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {});
  await page.waitForFunction(() => document.querySelector('.react-flow__node') && document.querySelector('.uml-class-card'), { timeout: 20000 })
    .catch(async () => {
      reloadMethod = 'SPA re-entry (openEditorViaSpa)';
      await openEditorViaSpa();
    });
  await new Promise((r) => setTimeout(r, 2000));
  const fresh = await flow();
  if (!fresh.nodeRect || !fresh.matrix) fail(6, 'editor did not re-render after reload');
  const freshToScreen = (fx, fy) => ({
    x: fresh.nodeRect.left + (fx - sourceRect.x) * fresh.matrix.a,
    y: fresh.nodeRect.top + (fy - sourceRect.y) * fresh.matrix.a,
  });
  if (!fresh.edgeSelected) {
    const point = freshToScreen((expected.uS + expected.autoCorridor) / 2, expected.vS);
    await page.mouse.click(point.x, point.y);
    await page.waitForFunction((rel) => {
      const edge = document.querySelector(`.react-flow__edge[data-id="${rel}"]`);
      return edge && edge.classList.contains('selected');
    }, { timeout: 10000 }, relationshipId).catch(() => {});
  }
  const afterReload = await flow();
  report.reload = {
    method: reloadMethod,
    dAfterReload: afterReload.d,
    dAfterBend: report.bend.dAfterBend,
    matches: afterReload.d === report.bend.dAfterBend,
    handles: { corridor: afterReload.corridorHandles, level: afterReload.levelHandles, add: afterReload.addButtons, remove: afterReload.removeButtons },
  };
  if (!report.reload.matches) {
    fail(6, `rendered path after reload differs: dAfterBend=${report.bend.dAfterBend} dAfterReload=${afterReload.d}`);
  }
  if (afterReload.addButtons !== 1 || afterReload.removeButtons !== 1) {
    fail(6, `after reload expected both + and - buttons, got ${JSON.stringify(report.reload.handles)}`);
  }
  report.steps.step6_survivesReload = true;
  await shot('5-after-reload.png');

  await browser.close();
  report.steps.step1_seed = true;
  report.steps.step2_handles = true;
  report.steps.step7_reportAndScreenshots = true;
  console.log(JSON.stringify(report, null, 2));
}

main().catch((error) => {
  report.errors.push(String(error && error.stack ? error.stack : error));
  console.log(JSON.stringify(report, null, 2));
  try { process.exitCode = 1; } catch { /* already exiting */ }
});
