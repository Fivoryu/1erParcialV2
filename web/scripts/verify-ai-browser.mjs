/**
 * Real-browser end-to-end verification of the two AI surfaces of the diagrammer.
 *
 * What it proves, through Chrome and the real backend with a real local llama-server:
 *   A. Register a user and create a project through the UI.
 *   B. Ask the plan assistant to create UML components, preview, confirm, and see the
 *      classes and attribute appear on the canvas.
 *   C. Open the collaboration chat, publish a human message, use its Ask AI control, and
 *      prove that a real AI answer is delivered by SSE and rendered in the chat.
 *
 * Usage:
 *   node scripts/verify-ai-browser.mjs [--app <url>] [--backend <url>] [--out <dir>]
 *
 * Requires: web dev server on --app, backend+DB on --backend with the assistant enabled,
 * and llama-server reachable by that backend.
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
  readOption('--out')
  ?? fileURLToPath(new URL('../../docs/capturas/ai-browser', import.meta.url)),
);
const INFERENCE_TIMEOUT = Number(process.env.AI_TIMEOUT_MS ?? 240000);
const UUID_PATTERN = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i;

function readOption(name) {
  const index = process.argv.indexOf(name);
  if (index === -1) {
    return undefined;
  }
  const value = process.argv[index + 1];
  if (!value || value.startsWith('--')) {
    throw new Error(`Missing value for ${name}`);
  }
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

function hasNoTechnicalAssistantLabel(values) {
  const text = values.join(' ');
  return !UUID_PATTERN.test(text) && !/elemento nuevo/i.test(text);
}

function appliedStepLabels(steps) {
  return steps.flatMap((step) => [step.text, step.status, ...step.diagnostics]);
}

async function shot(page, name) {
  const file = path.join(OUT, name);
  await page.screenshot({ path: file, fullPage: false });
  screenshots.push(file);
  return file;
}

async function typeInto(page, selector, value) {
  await page.waitForSelector(selector, { timeout: 30000 });
  await page.click(selector, { clickCount: 3 });
  await page.type(selector, value, { delay: 8 });
}

async function dismissTour(page) {
  const overlay = await page.$('.editor-tour-overlay');
  if (!overlay) {
    return false;
  }
  const clicked = await page.evaluate(() => {
    const tour = document.querySelector('.editor-tour-overlay');
    const button = Array.from(tour?.querySelectorAll('button') ?? [])
      .find((candidate) => (candidate.textContent ?? '').includes('Finish tour'));
    if (!button) {
      return false;
    }
    button.click();
    return true;
  });
  if (clicked) {
    await page.waitForFunction(() => !document.querySelector('.editor-tour-overlay'),
      { timeout: 10000, polling: 250 });
  }
  return clicked;
}

async function openAssistant(page) {
  const tapEvidence = await page.evaluate(() => {
    const button = document.querySelector('.floating-assistant');
    const tour = document.querySelector('.editor-tour-overlay');
    if (!button) {
      return null;
    }
    const buttonRect = button.getBoundingClientRect();
    const centerX = buttonRect.left + buttonRect.width / 2;
    const centerY = buttonRect.top + buttonRect.height / 2;
    const interceptor = document.elementFromPoint(centerX, centerY);
    const interceptorRect = interceptor?.getBoundingClientRect() ?? null;
    const interceptorStyle = interceptor ? getComputedStyle(interceptor) : null;
    const buttonStyle = getComputedStyle(button);
    const domPath = (node) => {
      if (!node) return null;
      const parts = [];
      for (let current = node; current && current.nodeType === Node.ELEMENT_NODE; current = current.parentElement) {
        let part = current.tagName.toLowerCase();
        if (current.id) part += `#${current.id}`;
        if (typeof current.className === 'string' && current.className.trim()) {
          part += `.${current.className.trim().split(/\s+/).join('.')}`;
        }
        if (current.parentElement) {
          const siblings = Array.from(current.parentElement.children)
            .filter((sibling) => sibling.tagName === current.tagName);
          if (siblings.length > 1) part += `:nth-of-type(${siblings.indexOf(current) + 1})`;
        }
        parts.unshift(part);
      }
      return parts.join(' > ');
    };
    const visible = (element, rect, style) => Boolean(element && rect && style
      && rect.width > 0 && rect.height > 0
      && style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity) > 0);
    const buttonVisible = visible(button, buttonRect, buttonStyle);
    const interceptorVisible = visible(interceptor, interceptorRect, interceptorStyle);
    const overlapsVisibleButton = Boolean(buttonVisible && interceptorVisible && interceptor !== button
      && !button.contains(interceptor)
      && buttonRect.left < interceptorRect.right && buttonRect.right > interceptorRect.left
      && buttonRect.top < interceptorRect.bottom && buttonRect.bottom > interceptorRect.top);
    return {
      buttonRect: {
        left: buttonRect.left, top: buttonRect.top, right: buttonRect.right, bottom: buttonRect.bottom,
        width: buttonRect.width, height: buttonRect.height,
      },
      buttonVisible,
      interceptor: interceptor ? {
        tag: interceptor.tagName,
        className: typeof interceptor.className === 'string' ? interceptor.className : interceptor.className?.baseVal ?? '',
        textContent: interceptor.textContent,
        rect: {
          left: interceptorRect.left, top: interceptorRect.top, right: interceptorRect.right,
          bottom: interceptorRect.bottom, width: interceptorRect.width, height: interceptorRect.height,
        },
        position: interceptorStyle.position,
        zIndex: interceptorStyle.zIndex,
        domPath: domPath(interceptor),
        visible: interceptorVisible,
        pointerEvents: interceptorStyle.pointerEvents,
      } : null,
      hitIsButton: Boolean(interceptor && (interceptor === button || button.contains(interceptor))),
      interceptedByTour: Boolean(tour && interceptor && (tour === interceptor || tour.contains(interceptor))),
      overlapsVisibleButton,
    };
  });
  if (!tapEvidence) {
    throw new Error('floating assistant button not found');
  }
  const centre = {
    x: tapEvidence.buttonRect.left + tapEvidence.buttonRect.width / 2,
    y: tapEvidence.buttonRect.top + tapEvidence.buttonRect.height / 2,
  };
  await page.mouse.move(centre.x, centre.y);
  await page.mouse.down();
  await new Promise((resolve) => setTimeout(resolve, 80));
  await page.mouse.up();
  let opened = false;
  try {
    await page.waitForFunction(() =>
      document.querySelectorAll('[role="dialog"][aria-modal="true"]').length === 1,
    { timeout: 5000, polling: 250 });
    opened = true;
  } catch {
    opened = false;
  }
  const openedOnFirstTap = opened;
  let tourDismissed = false;
  if (!opened) {
    tourDismissed = await dismissTour(page);
    if (tapEvidence.interceptedByTour && tapEvidence.overlapsVisibleButton) {
      findings.push({
        code: 'ASSISTANT_BUTTON_COVERED_BY_TOUR',
        detail: 'The first real tap hit the onboarding tour at the floating assistant button center. '
          + 'The run dismissed the tour through its visible "Finish tour" control and retried.',
        interceptor: tapEvidence.interceptor,
        assistantButtonRect: tapEvidence.buttonRect,
        overlapsVisibleButton: tapEvidence.overlapsVisibleButton,
      });
    }

    const retryCenter = await page.$eval('.floating-assistant', (button) => {
      const rect = button.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    });
    await page.mouse.move(retryCenter.x, retryCenter.y);
    await page.mouse.down();
    await new Promise((resolve) => setTimeout(resolve, 80));
    await page.mouse.up();
    try {
      await page.waitForFunction(() =>
        document.querySelectorAll('[role="dialog"][aria-modal="true"]').length === 1,
      { timeout: 5000, polling: 250 });
      opened = true;
    } catch {
      opened = false;
    }
  }
  if (!opened) {
    const genuineInterception = !tapEvidence.hitIsButton && tapEvidence.overlapsVisibleButton;
    if (!genuineInterception) {
      throw new Error('The real assistant-button tap did not open the panel and no visible interception was found.');
    }
    if (!tapEvidence.interceptedByTour) {
      findings.push({
        code: 'ASSISTANT_TAP_INTERCEPTED',
        detail: 'A real mouse tap at the floating assistant button center hit another visible element; '
          + 'the run fell back to a DOM click so the AI flow could still be exercised.',
        interceptor: tapEvidence.interceptor,
        assistantButtonRect: tapEvidence.buttonRect,
        overlapsVisibleButton: tapEvidence.overlapsVisibleButton,
      });
    }
    const clicked = await page.evaluate(() => {
      const button = document.querySelector('.floating-assistant');
      if (!button) return false;
      button.click();
      return true;
    });
    if (!clicked) {
      throw new Error('floating assistant button not found');
    }
  }
  await page.waitForFunction(() =>
    document.querySelectorAll('[role="dialog"][aria-modal="true"]').length === 1,
  { timeout: 15000, polling: 250 });
  await page.waitForSelector('#assistant-utterance', { timeout: 15000 });
  return { tourDismissed, openedWithTap: opened, openedOnFirstTap };
}

async function waitForEdges(page, minimum, timeout = 60000) {
  try {
    await page.waitForFunction((expected) =>
      document.querySelectorAll('.react-flow__edge-path').length >= expected,
    { timeout, polling: 250 }, minimum);
  } catch {
    // Fall through and report the observed count instead of assuming success.
  }
  return page.$$eval('.react-flow__edge-path', (edges) => edges.length);
}

async function registerAndCreateProject(page, suffix) {
  const username = `ai-browser-${suffix}`;
  const password = `AiBrowser-${suffix}-Pass!`;
  const projectName = `AI browser ${suffix}`;

  await page.goto(`${APP}/`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForSelector('#auth-username', { timeout: 60000 });
  await page.click('.auth-mode-switch .text-button');
  await page.waitForSelector('#auth-display-name', { timeout: 10000 });
  await typeInto(page, '#auth-display-name', 'AI Browser Verifier');
  await typeInto(page, '#auth-username', username);
  await typeInto(page, '#auth-password', password);
  await page.click('.auth-form button[type="submit"]');
  await page.waitForSelector('#project-name', { timeout: 60000 });
  const registered = check('UI registers a fresh account and lands on the projects page', true,
    'projects page with #project-name', page.url());

  await typeInto(page, '#project-name', projectName);
  await typeInto(page, '#project-description', 'Created by the browser AI verification run.');
  await page.click('.create-panel button[type="submit"]');
  await page.waitForSelector('.floating-assistant', { timeout: 60000 });
  await page.waitForSelector('.editor-options-bar', { timeout: 60000 });
  const editorUrl = page.url();

  return { registered, username, projectName, editorUrl };
}

async function askPlan(page, utterance) {
  await typeInto(page, '#assistant-utterance', utterance);
  await page.click('.assistant-panel form.chat-compose-form button[type="submit"]');
  await page.waitForFunction(() => {
    const panel = document.querySelector('.assistant-panel');
    if (!panel) {
      return false;
    }
    return Boolean(
      panel.querySelector('.generation-actions button.button-primary')
      || panel.querySelector('#assistant-clarification')
      || panel.querySelector('.notice-error'),
    );
  }, { timeout: INFERENCE_TIMEOUT, polling: 500 });

  return page.evaluate(() => {
    const panel = document.querySelector('.assistant-panel');
    const error = panel?.querySelector('.notice-error')?.textContent?.trim() ?? null;
    const clarify = panel?.querySelector('#assistant-clarification') ? true : false;
    const question = clarify
      ? panel?.querySelector('.property-hint')?.textContent?.trim() ?? null
      : null;
    const preview = Array.from(panel?.querySelectorAll('.generation-run .generation-diagnostics li strong') ?? [])
      .map((item) => item.textContent?.trim() ?? '');
    const confirmButton = panel?.querySelector('.generation-actions button.button-primary') ?? null;
    const confirmDisabled = confirmButton ? confirmButton.disabled : null;
    return { error, clarify, question, preview, confirmDisabled };
  });
}

async function applyPreview(page) {
  const clicked = await page.evaluate(() => {
    const button = Array.from(document.querySelectorAll('.assistant-panel .generation-actions button'))
      .find((candidate) => candidate.textContent?.includes('Aplicar cambios'));
    if (!button || button.disabled) {
      return false;
    }
    button.click();
    return true;
  });
  if (!clicked) {
    return { applied: false, reason: 'confirm button missing or disabled' };
  }
  await page.waitForFunction(() => {
    const text = document.querySelector('.assistant-panel .generation-run-state')?.textContent ?? '';
    return text.includes('Cambios aplicados') || Boolean(document.querySelector('.assistant-panel .notice-error'));
  }, { timeout: 120000, polling: 250 });
  return page.evaluate(() => {
    const panel = document.querySelector('.assistant-panel');
    const steps = Array.from(panel?.querySelectorAll('.generation-diagnostics li') ?? []).map((item) => {
      const spans = Array.from(item.querySelectorAll('span')).map((span) => span.textContent?.trim() ?? '');
      return {
        text: item.querySelector('strong')?.textContent?.trim() ?? '',
        status: spans[0] ?? '',
        diagnostics: spans.slice(1),
      };
    });
    return {
      applied: (panel?.querySelector('.generation-run-state')?.textContent ?? '').includes('Cambios aplicados'),
      finalRevision: panel?.querySelector('.generation-run-state')?.textContent?.trim() ?? null,
      error: panel?.querySelector('.notice-error')?.textContent?.trim() ?? null,
      steps,
    };
  });
}

async function waitForNode(page, name) {
  await page.waitForFunction((className) => {
    return Array.from(document.querySelectorAll('.uml-class-name'))
      .some((node) => (node.textContent ?? '').includes(className));
  }, { timeout: 60000, polling: 250 }, name);
  return page.evaluate(() => ({
    nodes: Array.from(document.querySelectorAll('.uml-class-name')).map((node) => node.textContent?.trim() ?? ''),
    attributes: Array.from(document.querySelectorAll('.uml-compartment--attributes .uml-member-text'))
      .map((node) => node.textContent?.trim() ?? ''),
  }));
}

async function waitForAttribute(page, text) {
  await page.waitForFunction((expected) =>
    Array.from(document.querySelectorAll('.uml-compartment--attributes .uml-member-text'))
      .some((node) => (node.textContent ?? '').includes(expected)),
  { timeout: 60000, polling: 250 }, text);
  return page.evaluate(() => Array.from(document.querySelectorAll(
    '.uml-compartment--attributes .uml-member-text',
  )).map((node) => node.textContent?.trim() ?? ''));
}

async function openChatAndSend(page, body) {
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => document.querySelectorAll('[role="dialog"]').length === 0, { timeout: 10000 });
  await page.click('[data-section="collaboration"]');
  await page.waitForSelector('#chat-session-selector', { timeout: 30000 });
  await page.waitForFunction(() => {
    const select = document.querySelector('#chat-session-selector');
    return Boolean(select && select.value);
  }, { timeout: 60000, polling: 250 });
  await typeInto(page, '#chat-message-draft', body);
  await page.click('.chat-compose-form button[type="submit"]');
  await page.waitForFunction((expected) => {
    return Array.from(document.querySelectorAll('.chat-message-body'))
      .some((node) => (node.textContent ?? '').includes(expected));
  }, { timeout: 60000, polling: 250 }, body);

  return page.evaluate(() => {
    const list = Array.from(document.querySelectorAll('.chat-message'));
    return {
      messages: list.length,
      rows: list.map((row) => ({
        author: row.querySelector('.chat-message-heading h3')?.textContent?.trim() ?? '',
        body: row.querySelector('.chat-message-body')?.textContent?.trim() ?? '',
        assistant: row.classList.contains('chat-message--assistant'),
        own: row.getAttribute('data-current-reader'),
      })),
    };
  });
}

async function waitForAiAnswer(page, timeout) {
  await page.waitForFunction(() => {
    const renderedAnswer = Array.from(document.querySelectorAll('.chat-message--assistant .chat-message-body'))
      .some((node) => (node.textContent ?? '').trim().length > 0);
    const capturedAnswer = (window.__aiBrowserRawAssistantBodies ?? [])
      .some((body) => typeof body === 'string' && body.trim().length > 0);
    return renderedAnswer && capturedAnswer;
  }, { timeout, polling: 500 });
  return page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('.chat-message--assistant'));
    const last = rows[rows.length - 1];
    const bodyNode = last?.querySelector('.chat-message-body') ?? null;
    const rawBodies = window.__aiBrowserRawAssistantBodies ?? [];
    const rawAnswer = rawBodies[rawBodies.length - 1] ?? null;
    return {
      count: rows.length,
      body: bodyNode?.textContent?.trim() ?? null,
      hasMarkdownElements: Boolean(bodyNode?.querySelector('strong, li')),
      rawAnswer,
      rawHasBoldOrListSyntax: typeof rawAnswer === 'string'
        && (/\*\*[^*]+\*\*|__[^_]+__/.test(rawAnswer)
          || /(?:^|\n)[ \t]*(?:[-*+][ \t]+|\d+\.[ \t]+)/.test(rawAnswer)),
      rawHasIndentedListSyntax: typeof rawAnswer === 'string'
        && /(?:^|\n)[ \t]{2,}(?:[-*+][ \t]+|\d+\.[ \t]+)/.test(rawAnswer),
      hasNestedListMarkup: Boolean(bodyNode?.querySelector('li > ul, li > ol')),
      author: last?.querySelector('.chat-message-heading h3')?.textContent?.trim() ?? null,
      status: document.querySelector('.chat-ai-status')?.textContent?.trim() ?? null,
      replyTo: last?.getAttribute('data-reply-to') ?? null,
    };
  });
}

async function captureRawAssistantBodies(page) {
  await page.evaluateOnNewDocument(() => {
    window.__aiBrowserRawAssistantBodies = [];
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const response = await originalFetch(...args);
      const input = args[0];
      const requestUrl = input instanceof Request ? input.url : String(input);
      if (!requestUrl.includes('/events') || !response.body) return response;

      let reader;
      try {
        reader = response.clone().body?.getReader();
      } catch {
        return response;
      }
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
                window.__aiBrowserRawAssistantBodies.push(message.body);
              }
            } catch {
              // Ignore malformed or unrelated SSE frames, matching the app's parser behavior.
            }
          }
          if (done) break;
        }
      })().catch(() => undefined);
      return response;
    };
  });
}

const suffix = crypto.randomUUID().slice(0, 8);
await mkdir(OUT, { recursive: true });
console.log(`[run] app=${APP} backend=${BACKEND} out=${OUT} suffix=${suffix}`);

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--window-size=1500,1000', '--disable-infobars', '--no-first-run'],
});
const page = await browser.newPage();
await captureRawAssistantBodies(page);
await page.setViewport({ width: 1440, height: 900 });

const report = { app: APP, backend: BACKEND, suffix, results, findings, screenshots, steps: {} };

try {
  const identity = await registerAndCreateProject(page, suffix);
  report.steps.identity = identity;
  await shot(page, '01-editor-new-project.png');

  // ---- B: the assistant creates real components ----
  report.steps.assistantOpen = await openAssistant(page);

  // Split into two explicit clauses: the 2B planner omitted attribute.add when the same
  // request was phrased as a single "clase con atributo" noun phrase (seen in the previous run).
  const firstUtterance = 'Creá la clase Producto. Agregale el atributo nombre: String.';
  const firstPlan = await askPlan(page, firstUtterance);
  report.steps.firstPlan = firstPlan;
  await shot(page, '02-assistant-preview-producto.png');
  check('assistant returns a preview for a component-creation request',
    firstPlan.error === null && firstPlan.clarify === false && firstPlan.preview.length > 0,
    'preview with at least one operation',
    firstPlan);
  check('preview describes Producto and nombre by name',
    firstPlan.preview.some((label) => label.includes('Producto'))
      && firstPlan.preview.some((label) => label.includes('nombre')),
    'preview labels containing Producto and nombre',
    firstPlan.preview);
  check('preview labels contain no UUID or generic placeholder',
    hasNoTechnicalAssistantLabel(firstPlan.preview),
    'no UUID or elemento nuevo in preview labels',
    firstPlan.preview);
  check('preview does not mutate the model before confirmation',
    (await page.$$('.uml-class-name')).length === 0,
    'zero UML classes on the canvas',
    await page.$$eval('.uml-class-name', (nodes) => nodes.map((node) => node.textContent?.trim())));

  const firstApply = await applyPreview(page);
  report.steps.firstApply = firstApply;
  const firstAppliedLabels = appliedStepLabels(firstApply.steps ?? []);
  check('applied steps describe Producto and nombre by name',
    firstAppliedLabels.some((label) => label.includes('Producto'))
      && firstAppliedLabels.some((label) => label.includes('nombre')),
    'applied step labels containing Producto and nombre',
    firstAppliedLabels);
  check('applied step labels contain no UUID or generic placeholder',
    hasNoTechnicalAssistantLabel(firstAppliedLabels),
    'no UUID or elemento nuevo in applied step labels',
    firstAppliedLabels);
  const productNode = firstApply.applied ? await waitForNode(page, 'Producto') : { nodes: [], attributes: [] };
  if (firstApply.applied) {
    // The class node and its attribute rows do not necessarily render in the same frame.
    productNode.attributes = await waitForAttribute(page, 'nombre');
  }
  report.steps.productNode = productNode;
  check('confirming the preview creates the class on the canvas',
    firstApply.applied && productNode.nodes.some((name) => name.includes('Producto')),
    'Producto on the canvas',
    productNode);
  check('the created class carries the requested attribute',
    productNode.attributes.some((attribute) => attribute.includes('nombre')),
    'attribute nombre',
    productNode.attributes);
  await shot(page, '03-assistant-applied-producto.png');

  // Second plan: create another class and connect it to the first one.
  await page.click('#assistant-utterance');
  const secondUtterance = 'Creá la clase Proveedor con el atributo cuit: String y asociala con Producto';
  const secondPlan = await askPlan(page, secondUtterance);
  report.steps.secondPlan = secondPlan;
  await shot(page, '04-assistant-preview-proveedor.png');
  check('assistant previews the second component and its relationship',
    secondPlan.error === null && secondPlan.clarify === false && secondPlan.preview.length > 0,
    'preview with operations',
    secondPlan);
  check('relationship preview names Producto and Proveedor',
    secondPlan.preview.some((label) => label.includes('Producto'))
      && secondPlan.preview.some((label) => label.includes('Proveedor')),
    'relationship preview labels containing Producto and Proveedor',
    secondPlan.preview);
  check('second preview labels contain no UUID or generic placeholder',
    hasNoTechnicalAssistantLabel(secondPlan.preview),
    'no UUID or elemento nuevo in preview labels',
    secondPlan.preview);

  const secondApply = await applyPreview(page);
  report.steps.secondApply = secondApply;
  const secondAppliedLabels = appliedStepLabels(secondApply.steps ?? []);
  check('applied relationship step names Producto and Proveedor',
    secondAppliedLabels.some((label) => label.includes('Producto'))
      && secondAppliedLabels.some((label) => label.includes('Proveedor')),
    'applied step labels containing Producto and Proveedor',
    secondAppliedLabels);
  check('second applied step labels contain no UUID or generic placeholder',
    hasNoTechnicalAssistantLabel(secondAppliedLabels),
    'no UUID or elemento nuevo in applied step labels',
    secondAppliedLabels);
  const supplierNode = secondApply.applied ? await waitForNode(page, 'Proveedor') : { nodes: [], attributes: [] };
  report.steps.supplierNode = supplierNode;
  const edgeCount = await waitForEdges(page, 1);
  report.steps.edgeCountAfterSecondApply = edgeCount;
  const skippedStep = (secondApply.steps ?? []).find((step) => step.status === 'Omitido');
  const nombreAttributes = supplierNode.attributes.filter((attribute) => attribute.includes('nombre'));
  check('the redundant attribute add is skipped instead of duplicated',
    Boolean(skippedStep) && skippedStep.diagnostics.some((diagnostic) => diagnostic.includes('ya existe')),
    'a visible Omitido step with the collision diagnostic',
    skippedStep ?? null);
  check('the AI plan leaves exactly one nombre attribute',
    nombreAttributes.length === 1,
    'exactly one nombre attribute',
    supplierNode.attributes);
  check('confirming the second preview creates Proveedor on the canvas',
    secondApply.applied && supplierNode.nodes.some((name) => name.includes('Proveedor')),
    'Proveedor on the canvas',
    supplierNode);
  check('the AI-created relationship is rendered on the canvas', edgeCount >= 1,
    'at least one relationship edge', edgeCount);
  await shot(page, '05-assistant-applied-proveedor.png');
  // Let the model refresh triggered by operation.applied reach the assistant before asking again.
  await new Promise((resolve) => setTimeout(resolve, 1500));

  // ---- C: the collaborative chat with a real AI answer ----
  const humanBody = `¿Qué modela el diagrama? ${suffix}`;
  const chatAfterSend = await openChatAndSend(page, humanBody);
  report.steps.chatAfterSend = chatAfterSend;
  check('a human message is published and rendered in the chat',
    chatAfterSend.rows.some((row) => row.body === humanBody && row.own === 'true'),
    'own human message visible',
    chatAfterSend.rows);
  const askAiRowSelector = '.chat-message[data-current-reader="true"]';
  await page.waitForFunction((expectedBody, rowSelector) => {
    const row = Array.from(document.querySelectorAll(rowSelector))
      .find((candidate) => candidate.querySelector('.chat-message-body')?.textContent?.trim() === expectedBody);
    const button = Array.from(row?.querySelectorAll('button') ?? [])
      .find((candidate) => candidate.textContent?.trim() === 'Ask AI');
    return Boolean(button && !button.disabled);
  }, { timeout: 30000, polling: 250 }, humanBody, askAiRowSelector);

  const askAiButton = await page.evaluateHandle(({ expectedBody, rowSelector }) => {
    const row = Array.from(document.querySelectorAll(rowSelector))
      .find((candidate) => candidate.querySelector('.chat-message-body')?.textContent?.trim() === expectedBody);
    return Array.from(row?.querySelectorAll('button') ?? [])
      .find((candidate) => candidate.textContent?.trim() === 'Ask AI') ?? null;
  }, { expectedBody: humanBody, rowSelector: askAiRowSelector });
  const askAiButtonElement = askAiButton.asElement();
  const askAiControl = askAiButtonElement
    ? await askAiButtonElement.evaluate((button) => ({
      label: button.textContent?.trim() ?? '',
      disabled: button.disabled,
      rowBody: button.closest('.chat-message')?.querySelector('.chat-message-body')?.textContent?.trim() ?? null,
    }))
    : null;
  check('the Ask AI button exists on the own human message row',
    askAiControl?.label === 'Ask AI' && askAiControl.rowBody === humanBody,
    { selector: `${askAiRowSelector} button`, label: 'Ask AI', rowBody: humanBody },
    askAiControl);
  await shot(page, '06-chat-ask-ai-button.png');

  if (!askAiButtonElement || askAiControl?.label !== 'Ask AI' || askAiControl.disabled) {
    throw new Error('Ask AI button was missing or disabled on the own human message row.');
  }
  await askAiButtonElement.click();
  await askAiButtonElement.dispose();
  check('the Ask AI button is clicked through the chat UI', true,
    { selector: `${askAiRowSelector} button`, label: 'Ask AI' },
    { clicked: true, rowBody: askAiControl.rowBody });

  const aiAnswer = await waitForAiAnswer(page, INFERENCE_TIMEOUT);
  report.steps.aiAnswer = aiAnswer;
  check('a real AI answer is delivered by SSE and rendered in the chat',
    aiAnswer.body !== null && aiAnswer.body.length > 0,
    'non-empty assistant message in the chat',
    aiAnswer);
  check('the raw assistant answer is captured from the project event stream',
    typeof aiAnswer.rawAnswer === 'string' && aiAnswer.rawAnswer.length > 0,
    'non-empty raw AI message body from the message.posted SSE event',
    aiAnswer.rawAnswer);
  check('the rendered assistant body has no literal bold markers',
    aiAnswer.body !== null && !aiAnswer.body.includes('**'),
    'no literal ** markers in assistant body text',
    aiAnswer.body);
  check('raw bold/list syntax is rendered as semantic markdown when present',
    !aiAnswer.rawHasBoldOrListSyntax || aiAnswer.hasMarkdownElements,
    'if the raw answer contains bold/list syntax, rendered body contains <strong> or <li>',
    {
      rawHasBoldOrListSyntax: aiAnswer.rawHasBoldOrListSyntax,
      hasMarkdownElements: aiAnswer.hasMarkdownElements,
    });
  check('raw indented list syntax is rendered as a nested list when present',
    !aiAnswer.rawHasIndentedListSyntax || aiAnswer.hasNestedListMarkup,
    'if the raw answer contains indented list syntax, rendered body contains li > ul or li > ol',
    {
      rawHasIndentedListSyntax: aiAnswer.rawHasIndentedListSyntax,
      hasNestedListMarkup: aiAnswer.hasNestedListMarkup,
    });
  await shot(page, '07-chat-ai-answer.png');
} catch (error) {
  check('browser E2E completed without an unexpected exception', false,
    'no exception', `${error.name}: ${error.message}`);
  report.exception = `${error.name}: ${error.message}`;
  try {
    await shot(page, '99-failure.png');
  } catch {
    // the page may already be closed
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
