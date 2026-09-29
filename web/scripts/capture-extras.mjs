/**
 * Captures the two states the main capture script cannot reach yet.
 *
 * The main script selects a class and a relationship. The parameter controls only
 * appear when an *operation* is selected, and a package only shows up as a package
 * once one exists in the diagram. Both are captured here, with the same honesty
 * guard: a state that cannot be reached fails loudly instead of writing a capture
 * whose label cannot be backed.
 *
 * Usage: node scripts/capture-extras.mjs --editor-url <url> --out <dir> [--chrome <path>]
 */

import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import puppeteer from 'puppeteer-core';

const DEFAULT_CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

function parseArguments(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    if (!argv[index].startsWith('--')) continue;
    parsed[argv[index].slice(2)] = argv[index + 1];
    index += 1;
  }
  if (!parsed['editor-url'] || !parsed.out) {
    throw new Error('Usage: node scripts/capture-extras.mjs --editor-url <url> --out <dir> [--chrome <path>]');
  }
  return parsed;
}

const options = parseArguments(process.argv.slice(2));
const outputDirectory = path.resolve(options.out);
await mkdir(outputDirectory, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: options.chrome ?? process.env.CHROME_PATH ?? DEFAULT_CHROME,
  headless: true,
  args: ['--hide-scrollbars', '--no-first-run', '--no-default-browser-check'],
  defaultViewport: { width: 1600, height: 1000, deviceScaleFactor: 2 },
});
const page = await browser.newPage();
const steps = [];

async function shot(name, description) {
  await page.screenshot({ path: path.join(outputDirectory, name) });
  steps.push(`${name}  ${description}`);
}

async function ready() {
  await page.waitForSelector('.uml-class-card', { timeout: 30000 });
  await new Promise((resolve) => setTimeout(resolve, 900));
}

/** Clicks a real element by dispatching the events React listens for. */
async function clickElement(selector, text) {
  return page.evaluate((sel, wanted) => {
    const candidates = [...document.querySelectorAll(sel)];
    const element = wanted
      ? candidates.find((node) => node.textContent?.includes(wanted))
      : candidates[0];
    if (!element) return false;
    for (const type of ['mousedown', 'mouseup', 'click']) {
      element.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, detail: 1 }));
    }
    return true;
  }, selector, text ?? null);
}

try {
  await page.goto(options['editor-url'], { waitUntil: 'networkidle2' });
  await ready();

  // 13. Package drawn as a package, not as a class box.
  const packageShown = await page.evaluate(() => document.querySelectorAll('.uml-package-card').length > 0);
  if (!packageShown) {
    throw new Error('No package node found in the diagram; refusing to label a capture as a package view.');
  }
  await shot('13-paquete.png', 'a package drawn with its folder notation');

  // 14. Parameter controls, which need an operation selected.
  const selectedClass = await clickElement('.react-flow__node .uml-class-card', 'Customer');
  if (!selectedClass) throw new Error('Could not select the Customer class.');
  await new Promise((resolve) => setTimeout(resolve, 500));

  const selectedOperation = await clickElement('.uml-compartment--operations .uml-member-text', 'verify');
  await new Promise((resolve) => setTimeout(resolve, 700));

  const parameterControls = await page.evaluate(() =>
    document.body.textContent?.includes('Add parameter') ?? false);
  if (!selectedOperation || !parameterControls) {
    throw new Error('The parameter controls are not on screen; refusing to write a capture that mislabels itself.');
  }
  await shot('14-panel-parametros.png', 'operation selected with its parameter controls');

  console.log('Captures written to', outputDirectory);
  for (const step of steps) console.log(' -', step);
} finally {
  await browser.close();
}
