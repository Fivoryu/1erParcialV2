/**
 * Captures the editor with real interaction.
 *
 * A plain `chrome --screenshot` cannot click, so it can only show the canvas at rest. The states
 * that actually need proving — a selected class with a populated properties panel, inline editing
 * of a name, a selected relationship — only exist after interaction. This drives the installed
 * Chrome through CDP instead of downloading a browser of its own.
 *
 * Usage:
 *   node scripts/capture-editor.mjs --editor-url <url> --out <dir> [--chrome <path>]
 *
 * The caller is responsible for having a backend and a served build behind `--editor-url`.
 * This script never mutates the model: it opens the inline editor and cancels out of it.
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
  // Let the fit-to-view animation settle so captures are not taken mid transform.
  await new Promise((resolve) => setTimeout(resolve, 900));
}

/** Clicks a point that actually lies on an edge, since the path's bounding box centre does not. */
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

try {
  // 1. Projects list
  await page.goto(`${options['editor-url']}/`, { waitUntil: 'networkidle2' });
  await page.waitForSelector('a, button, [role="button"]', { timeout: 20000 });
  await new Promise((resolve) => setTimeout(resolve, 700));
  await capture('07-proyectos.png', 'project list rendered from the live backend');

  // 2. Canvas at rest
  await page.goto(options['editor-url'], { waitUntil: 'networkidle2' });
  await waitForCanvas();
  await capture('08-lienzo.png', 'class diagram at rest');

  // 3. Class selected, properties panel populated.
  // Select this specific class by name: clicking "the first node" and then double-clicking "the
  // first editable label" can address two different nodes, and the first click of the double click
  // then changes the selection, re-renders, and swallows the second click.
  await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('.react-flow__node')];
    const target = nodes.find((node) => node.querySelector('.uml-class-name')?.textContent?.includes('Customer'));
    (target ?? nodes[0])?.querySelector('.uml-class-card')?.dispatchEvent(
      new MouseEvent('click', { bubbles: true }),
    );
  });
  await new Promise((resolve) => setTimeout(resolve, 600));
  await capture('09-clase-seleccionada.png', 'selected class with its properties panel');

  // 4. Inline editing of the class name, then cancel without mutating the model.
  // A physical double click is attempted first. It is not reliable here: the first click selects
  // the node, React re-renders, and the second click can land on a re-created element so no
  // dblclick is ever synthesised. The fallback dispatches a real bubbling dblclick on the real
  // element, which exercises the same React handler; only the input source differs.
  const editableName = await page.$('.react-flow__node.selected .uml-editable-label');
  if (editableName) {
    const box = await editableName.boundingBox();
    if (box) {
      await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { clickCount: 2, delay: 40 });
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
    let editorOpen = await page.$('.uml-inline-editor');
    let inputSource = 'physical double click';
    if (!editorOpen) {
      await page.evaluate(() => {
        const element = document.querySelector('.react-flow__node.selected .uml-editable-label');
        element?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, detail: 2 }));
      });
      await new Promise((resolve) => setTimeout(resolve, 400));
      editorOpen = await page.$('.uml-inline-editor');
      inputSource = 'dispatched dblclick';
    }
    await capture(
      '10-edicion-en-linea.png',
      editorOpen ? `inline editor opened on the class name (${inputSource})` : 'INLINE EDITOR DID NOT OPEN',
    );
    if (!editorOpen) {
      throw new Error('Inline editor did not open; refusing to publish a capture that mislabels itself.');
    }
    await page.keyboard.press('Escape');
    await new Promise((resolve) => setTimeout(resolve, 300));
  }

  // 5. Relationship selected
  await page.reload({ waitUntil: 'networkidle2' });
  await waitForCanvas();
  const edgeCount = await page.$$eval('.react-flow__edge-path', (paths) => paths.length);
  await clickOnEdgePath(edgeCount - 1);
  await capture('11-conexion-seleccionada.png', 'selected relationship with its properties panel');

  // 6. Reload proves the layout survives
  await page.reload({ waitUntil: 'networkidle2' });
  await waitForCanvas();
  await capture('12-tras-recarga.png', 'same diagram and layout after a reload');

  console.log('Captures written to', outputDirectory);
  for (const step of steps) {
    console.log(' -', step);
  }
  console.log(`Total: ${steps.length}`);
} finally {
  await browser.close();
}
