/**
 * Captures the generation panel of the editor.
 *
 * The scene is created by the deployment run: a model with two destinations, both already generated.
 * The script refuses to write a capture whose label cannot be backed. It asserts that the panel lists
 * both destinations, that the profile is offered, and that no internal identifier reached the screen —
 * the same rule the Increment 1 captures follow: a screenshot of a state nobody verified is not evidence.
 *
 * Usage:
 *   node scripts/capture-generation-panel.mjs --editor-url <url> --out <dir> [--chrome <path>] [--destinations 2]
 *
 * The caller is responsible for serving the editor with its API proxy pointing at the diagrammer.
 */

import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import puppeteer from 'puppeteer-core';

const DEFAULT_CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

function parseArguments(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    if (!argv[index].startsWith('--')) continue;
    parsed[argv[index].slice(2)] = argv[index + 1];
    index += 1;
  }
  if (!parsed['editor-url'] || !parsed.out || !parsed.project) {
    throw new Error('Usage: node scripts/capture-generation-panel.mjs --editor-url <url> --project <projectId> '
      + '--out <dir> [--chrome <path>] [--destinations 2] [--expect-deployment <state label>]');
  }
  return parsed;
}

const options = parseArguments(process.argv.slice(2));
const expectedDestinations = Number(options.destinations ?? '2');
const outputDirectory = path.resolve(options.out);
await mkdir(outputDirectory, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: options.chrome ?? process.env.CHROME_PATH ?? DEFAULT_CHROME,
  headless: true,
  args: ['--hide-scrollbars', '--no-first-run', '--no-default-browser-check', '--no-proxy-server'],
  defaultViewport: { width: 1600, height: 1200, deviceScaleFactor: 2 },
});
const page = await browser.newPage();
const steps = [];

async function shot(name, description) {
  await page.screenshot({ path: path.join(outputDirectory, name) });
  steps.push(`${name}  ${description}`);
  console.log(`[SHOT] ${name}  ${description}`);
}

// The editor is reached through its own project list, not by typing the editor URL: the development and
// preview servers both proxy `/projects`, so a direct document request for `/projects/<id>` never reaches
// the single page application.
await page.goto(options['editor-url'], { waitUntil: 'domcontentloaded' });
const projectLink = `a[href="/projects/${options.project}"]`;
await page.waitForSelector(projectLink, { timeout: 30000 });
await Promise.all([
  page.waitForFunction(() => location.pathname.startsWith('/projects/'), { timeout: 30000 }),
  page.click(projectLink),
]);
await page.waitForSelector('.generation-panel h2', { timeout: 30000 });

// The panel loads its destinations and profile from the API, so wait for the list to settle.
await page.waitForFunction(
  (expected) => document.querySelectorAll('.generation-target-list button').length >= expected,
  { timeout: 20000 },
  expectedDestinations,
);

const panel = await page.evaluate(() => {
  const root = document.querySelector('.generation-panel');
  const sections = [...root.querySelectorAll('.generation-section')];
  const optionTexts = (section) => [...section.querySelectorAll('.generation-target-option')]
    .map((button) => button.innerText.trim());
  const destinationSection = sections[0];
  const profileSection = sections[1];
  return {
    text: root.innerText,
    destinations: optionTexts(destinationSection),
    pressedDestinations: [...destinationSection.querySelectorAll('.generation-target-option[aria-pressed="true"]')]
      .map((button) => button.innerText.trim()),
    profiles: optionTexts(profileSection),
    generateEnabled: !root.querySelector('.generation-actions .button-primary').disabled,
  };
});

console.log(`[PANEL] destinations=${JSON.stringify(panel.destinations)} profiles=${JSON.stringify(panel.profiles)} `
  + `pressed=${JSON.stringify(panel.pressedDestinations)} generateEnabled=${panel.generateEnabled}`);

if (panel.destinations.length < expectedDestinations) {
  throw new Error(`The panel listed ${panel.destinations.length} destinations, expected at least ${expectedDestinations}`);
}
if (panel.profiles.length === 0) {
  throw new Error('The panel did not offer a generation profile');
}
if (panel.pressedDestinations.length !== 1) {
  throw new Error(`Expected exactly one selected destination, saw ${JSON.stringify(panel.pressedDestinations)}`);
}
if (!panel.generateEnabled) {
  throw new Error('The generate action is disabled although a destination and a profile are selected');
}
if (UUID_PATTERN.test(panel.text)) {
  throw new Error(`The panel shows an internal identifier:\n${panel.text}`);
}
for (const identifier of ['run_id', 'target_id', 'profile_id', 'template_id', 'model_version', 'generation_run']) {
  if (panel.text.includes(identifier)) {
    throw new Error(`The panel shows the technical name ${identifier}`);
  }
}

await shot('15-panel-generacion.png', `Panel de generación con ${panel.destinations.length} destinos del modelo, `
  + `perfil ${panel.profiles[0] ?? 'sin perfil'} y la acción de generar disponible`);

const element = await page.$('.generation-panel');
await element.screenshot({ path: path.join(outputDirectory, '16-panel-generacion-detalle.png') });
steps.push('16-panel-generacion-detalle.png  El panel solo, a resolución de lectura');
console.log('[SHOT] 16-panel-generacion-detalle.png  El panel solo, a resolución de lectura');

// The deployment state is a second scene of the same panel, and it is only reachable when a deployment
// exists. Asking for it makes the script fail loudly instead of writing a capture whose label is a lie.
if (options['expect-deployment']) {
  const expected = options['expect-deployment'];
  const deployment = await page.evaluate(() => {
    const root = document.querySelector('.generation-deployment');
    if (root === null) {
      return null;
    }
    const address = root.querySelector('.generation-address code');
    return {
      text: root.innerText,
      state: root.querySelector('.generation-run-state')?.innerText.trim() ?? null,
      address: address === null ? null : address.innerText.trim(),
      localHint: root.innerText.includes('only works here'),
    };
  });
  console.log(`[DEPLOYMENT] ${JSON.stringify(deployment)}`);
  if (deployment === null) {
    throw new Error('The panel has no deployment block, so a deployment capture cannot be backed');
  }
  if (deployment.state !== expected) {
    throw new Error(`The deployment state reads '${deployment.state}' and not '${expected}'`);
  }
  if (deployment.address === null || !/^http:\/\/[^\s]+:\d+$/.test(deployment.address)) {
    throw new Error(`The panel shows no usable address: ${JSON.stringify(deployment.address)}`);
  }
  if (UUID_PATTERN.test(deployment.text)) {
    throw new Error(`The deployment block shows an internal identifier:
${deployment.text}`);
  }

  const name = deployment.localHint ? '02-panel-despliegue-localhost.png' : '02-panel-despliegue.png';
  await shot(name, `Panel de generación con el despliegue en estado ${expected} y la dirección `
    + `${deployment.address} para otro dispositivo de la red`);
  const deploymentElement = await page.$('.generation-deployment');
  await deploymentElement.screenshot({ path: path.join(outputDirectory, '03-panel-despliegue-detalle.png') });
  steps.push('03-panel-despliegue-detalle.png  El bloque de despliegue solo, a resolución de lectura');
  console.log('[SHOT] 03-panel-despliegue-detalle.png  El bloque de despliegue solo, a resolución de lectura');
}

console.log('\n--- captures ---');
for (const step of steps) console.log(step);

await browser.close();
