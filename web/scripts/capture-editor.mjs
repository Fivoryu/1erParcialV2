/**
 * Captures the editor through real browser interaction.
 *
 * A plain `chrome --screenshot` cannot click, so it can only show the canvas at rest. This drives
 * the installed Chrome through CDP and verifies each requested state before writing its capture.
 * The script never mutates the model: it selects existing elements and cancels inline editing.
 * It fails loudly when the supplied diagram lacks a state needed for an honestly labelled capture.
 *
 * Usage:
 *   node scripts/capture-editor.mjs --editor-url <url> --out <dir> [--chrome <path>]
 *
 * The caller is responsible for having a backend and a served editor build behind `--editor-url`.
 */

import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import puppeteer from 'puppeteer-core';

const DEFAULT_CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

function parseArguments(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) {
      continue;
    }
    const key = token.slice(2);
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) {
      throw new Error(`Missing value for --${key}`);
    }
    parsed[key] = value;
    index += 1;
  }
  if (!parsed['editor-url'] || !parsed.out) {
    throw new Error('Usage: node scripts/capture-editor.mjs --editor-url <url> --out <dir> [--chrome <path>]');
  }
  return parsed;
}

const options = parseArguments(process.argv.slice(2));
const chromePath = options.chrome ?? process.env.CHROME_PATH ?? DEFAULT_CHROME;
const outputDirectory = path.resolve(options.out);
await mkdir(outputDirectory, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: chromePath,
  headless: true,
  args: ['--hide-scrollbars', '--no-first-run', '--no-default-browser-check'],
  defaultViewport: { width: 1600, height: 1000, deviceScaleFactor: 2 },
});

const page = await browser.newPage();
const steps = [];

async function capture(name, description) {
  const file = path.join(outputDirectory, name);
  await page.screenshot({ path: file });
  steps.push(`${name}  ${description}`);
}

async function waitForCanvas() {
  await page.waitForSelector('.react-flow__node', { timeout: 30000 });
  await page.waitForSelector('.uml-class-card', { timeout: 30000 });
  await page.waitForFunction(() => !document.querySelector('[role="alert"]'), { timeout: 30000 });
  // Let the fit-to-view animation settle so captures are not taken mid transform.
  await new Promise((resolve) => setTimeout(resolve, 900));
}

async function clickOnEdgePath(index) {
  const point = await page.evaluate((edgeIndex) => {
    const paths = [...document.querySelectorAll('.react-flow__edge-path')];
    const target = paths[edgeIndex];
    if (!target) {
      return null;
    }
    const length = target.getTotalLength();
    const local = target.getPointAtLength(length / 2);
    const matrix = target.getScreenCTM();
    if (!matrix) {
      return null;
    }
    return { x: local.x * matrix.a + local.y * matrix.c + matrix.e,
             y: local.x * matrix.b + local.y * matrix.d + matrix.f };
  }, index);
  if (!point) {
    throw new Error(`No edge path at index ${index}`);
  }
  await page.mouse.click(point.x, point.y);
  await new Promise((resolve) => setTimeout(resolve, 400));
}

async function diagramSnapshot() {
  return page.evaluate(() => ({
    nodes: [...document.querySelectorAll('.react-flow__node')].map((node) => ({
      label: node.textContent?.replace(/\s+/g, ' ').trim() ?? '',
      position: node.style.transform,
    })).sort((left, right) => left.label.localeCompare(right.label)),
    edgeCount: document.querySelectorAll('.react-flow__edge-path').length,
  }));
}

try {
  // 1. Projects list.
  await page.goto(`${options['editor-url']}/`, { waitUntil: 'networkidle2' });
  await page.waitForSelector('#projects-title', { timeout: 20000 });
  await page.waitForSelector('#project-list-title', { timeout: 20000 });
  await page.waitForFunction(() => !document.querySelector('[role="status"]'), { timeout: 20000 });
  await page.waitForFunction(() => document.querySelector('.project-row, .empty-list'), { timeout: 20000 });
  if (await page.$('[role="alert"]')) {
    throw new Error('Projects failed to load; refusing to capture the project list.');
  }
  await capture('07-proyectos.png', 'loaded project list from the live backend');

  // 2. Canvas at rest.
  await page.goto(options['editor-url'], { waitUntil: 'networkidle2' });
  await waitForCanvas();
  if (await page.$('.react-flow__node.selected')) {
    throw new Error('The canvas is not at rest; refusing to label it as unselected.');
  }
  await capture('08-lienzo.png', 'class diagram at rest with no selected node');

  // 3. Select the named class, never an arbitrary fallback node.
  const selectedCustomer = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('.react-flow__node')];
    const target = nodes.find((node) => node.querySelector('.uml-class-name')?.textContent?.trim() === 'Customer');
    target?.querySelector('.uml-class-card')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return Boolean(target);
  });
  if (!selectedCustomer) {
    throw new Error('Class Customer is absent; refusing to capture an unrelated node as the selected class.');
  }
  await page.waitForFunction(() => {
    const selected = document.querySelector('.react-flow__node.selected');
    return selected?.querySelector('.uml-class-name')?.textContent?.trim() === 'Customer'
      && document.querySelector('#classifier-properties-title')?.textContent?.trim() === 'Class';
  }, { timeout: 10000 });
  await capture('09-clase-seleccionada.png', 'Customer selected with its class properties panel');

  // 4. Inline editing, then cancel without mutating the model.
  const editableName = await page.$('.react-flow__node.selected .uml-class-name .uml-editable-label');
  if (!editableName) {
    throw new Error('The selected class name is not editable; refusing to skip the inline-edit capture.');
  }
  const box = await editableName.boundingBox();
  if (box) {
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { clickCount: 2, delay: 40 });
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  let editorOpen = await page.$('.uml-inline-editor[aria-label="Edit class name"]');
  let inputSource = 'physical double click';
  if (!editorOpen) {
    await page.evaluate(() => {
      const element = document.querySelector('.react-flow__node.selected .uml-class-name .uml-editable-label');
      element?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, detail: 2 }));
    });
    await new Promise((resolve) => setTimeout(resolve, 400));
    editorOpen = await page.$('.uml-inline-editor[aria-label="Edit class name"]');
    inputSource = 'dispatched dblclick';
  }
  if (!editorOpen) {
    throw new Error('Inline editor did not open; refusing to publish a capture that mislabels itself.');
  }
  await capture('10-edicion-en-linea.png', `inline editor open on Customer (${inputSource})`);
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('.uml-inline-editor'), { timeout: 5000 });

  // 5. Relationship selected.
  await page.reload({ waitUntil: 'networkidle2' });
  await waitForCanvas();
  const edgeCount = await page.$$eval('.react-flow__edge-path', (paths) => paths.length);
  if (edgeCount < 1) {
    throw new Error('The diagram has no relationship; refusing to label an empty canvas as a selected connection.');
  }
  await clickOnEdgePath(edgeCount - 1);
  await page.waitForFunction(() => document.querySelector('.react-flow__edge.selected')
    && document.querySelector('.properties-panel h3')?.textContent?.trim() === 'Relationship', { timeout: 10000 });
  await capture('11-conexion-seleccionada.png', 'selected relationship with its properties panel');

  // 6. Select an operation so the parameter controls are visible.
  const operationName = await page.evaluate(() => {
    const operation = document.querySelector('.react-flow__node .uml-editable-label[aria-label^="Edit operation "]');
    operation?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return operation?.textContent?.trim() ?? null;
  });
  if (!operationName) {
    throw new Error('The diagram has no selectable operation; refusing to claim parameter controls are visible.');
  }
  await page.waitForFunction(() => document.querySelector('#operation-parameters-title')?.textContent?.trim() === 'Parameters'
    && [...document.querySelectorAll('.parameter-properties button')]
      .some((button) => button.textContent?.trim() === 'Add parameter'), { timeout: 10000 });
  const operationTitle = await page.$eval('.properties-panel h3', (heading) => heading.textContent?.trim());
  if (operationTitle !== 'Operation') {
    throw new Error('Selecting the operation did not populate its properties panel.');
  }
  await capture('13-parametros-de-operacion.png', `selected operation ${operationName} with parameter controls visible`);

  // 7. Capture an existing package without creating or changing model data.
  const packageNode = await page.$('.react-flow__node .uml-package-card');
  if (!packageNode) {
    throw new Error('The diagram has no package; refusing to fabricate a package capture.');
  }
  const packageName = await page.$eval('.react-flow__node .uml-package-card .uml-package-name',
    (name) => name.textContent?.trim() ?? '');
  if (!packageName || !(await page.$('.uml-package-tab'))
      || await page.$('.react-flow__node .uml-package-card .uml-compartment')) {
    throw new Error('The rendered package is missing its folder tab/name or has a compartment.');
  }
  await page.evaluate((name) => {
    const node = [...document.querySelectorAll('.react-flow__node')]
      .find((candidate) => candidate.querySelector('.uml-package-name')?.textContent?.trim() === name);
    node?.querySelector('.uml-package-card')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  }, packageName);
  await page.waitForFunction((name) => {
    const selected = document.querySelector('.react-flow__node.selected');
    return selected?.querySelector('.uml-package-name')?.textContent?.trim() === name
      && document.querySelector('#classifier-properties-title')?.textContent?.trim() === 'Package';
  }, { timeout: 10000 }, packageName);
  await capture('14-paquete.png', `package ${packageName} rendered with its folder tab and no compartments`);

  // 8. Reload proves the package and the diagram layout survive.
  const beforeReload = await diagramSnapshot();
  await page.reload({ waitUntil: 'networkidle2' });
  await waitForCanvas();
  await page.waitForFunction((name) => [...document.querySelectorAll('.uml-package-name')]
    .some((element) => element.textContent?.trim() === name), { timeout: 10000 }, packageName);
  const afterReload = await diagramSnapshot();
  if (JSON.stringify(beforeReload) !== JSON.stringify(afterReload)) {
    throw new Error('The diagram nodes, positions, or relationships changed after reload; refusing the reload capture.');
  }
  await capture('12-tras-recarga.png', `same diagram and node positions after reload, including package ${packageName}`);

  console.log('Captures written to', outputDirectory);
  for (const step of steps) {
    console.log(' -', step);
  }
  console.log(`Total: ${steps.length}`);
} finally {
  await browser.close();
}
