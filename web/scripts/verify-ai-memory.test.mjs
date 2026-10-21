import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script = fileURLToPath(new URL('./verify-ai-memory.mjs', import.meta.url));
const webDirectory = path.resolve(path.dirname(script), '..');

function listen(handler) {
  const server = createServer(handler);
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.removeListener('error', reject);
      const address = server.address();
      resolve({ server, url: `http://127.0.0.1:${address.port}` });
    });
  });
}

function close(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
    server.closeAllConnections?.();
  });
}

async function runPreflight(appUrl, backendUrl) {
  const child = spawn(process.execPath, [
    script,
    '--app', appUrl,
    '--backend', backendUrl,
    '--timeout-ms', '120000',
  ], { cwd: webDirectory, windowsHide: true });
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
  child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
  const { code, signal } = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', (exitCode, exitSignal) => resolve({ code: exitCode, signal: exitSignal }));
  });
  const jsonStart = stdout.lastIndexOf('\n{');
  assert.notEqual(jsonStart, -1, `acceptance report missing from stdout; stderr=${stderr}`);
  return { code, signal, stdout, stderr, report: JSON.parse(stdout.slice(jsonStart + 1)) };
}

function assertTiming(stage) {
  assert.equal(typeof stage.startedAt, 'string');
  assert.equal(typeof stage.completedAt, 'string');
  assert.ok(Number.isFinite(Date.parse(stage.startedAt)));
  assert.ok(Number.isFinite(Date.parse(stage.completedAt)));
  assert.ok(Number.isFinite(stage.elapsedMs) && stage.elapsedMs >= 0);
}

test('an app HTTP failure retains the backend auth result and both request timings', async () => {
  let backendRequests = 0;
  const app = await listen((_request, response) => {
    response.writeHead(503, 'Unavailable').end('app unavailable');
  });
  const backend = await listen((request, response) => {
    if (request.url === '/auth/me') backendRequests += 1;
    response.writeHead(401, 'Unauthorized').end('authentication required');
  });
  try {
    const result = await runPreflight(app.url, backend.url);
    assert.equal(result.code, 2, 'unavailable prerequisites must use the existing exit code');
    assert.equal(result.signal, null);
    assert.equal(result.report.preflight.appReady, false);
    assert.equal(result.report.preflight.backendReady, true, '401 remains an accepted backend-ready response');
    assert.equal(typeof result.report.unavailable, 'string');
    assert.equal(backendRequests, 1, 'the backend endpoint must still be checked after app failure');

    const appStage = result.report.preflight.app;
    const backendStage = result.report.preflight.backend;
    assert.ok(appStage, 'the app request must have an independent stage result');
    assert.ok(backendStage, 'the backend request must have an independent stage result');
    assert.equal(appStage.stage, 'app');
    assert.equal(backendStage.stage, 'backend-auth');
    assert.equal(appStage.url, app.url);
    assert.equal(appStage.status, 503);
    assert.equal(appStage.ready, false);
    assert.equal(appStage.error?.name, 'HttpStatusError');
    assertTiming(appStage);
    assert.equal(backendStage.url, `${backend.url}/auth/me`);
    assert.equal(backendStage.status, 401);
    assert.equal(backendStage.ready, true);
    assert.equal(backendStage.error, null);
    assertTiming(backendStage);
  } finally {
    await Promise.all([close(app.server), close(backend.server)]);
  }
});

test('a backend transport failure retains the successful app result and its timing', async () => {
  const app = await listen((_request, response) => {
    response.writeHead(200, 'OK').end('app ready');
  });
  const backend = await listen((request) => {
    if (request.url === '/auth/me') request.socket.destroy();
  });
  try {
    const result = await runPreflight(app.url, backend.url);
    assert.equal(result.code, 2, 'unavailable prerequisites must use the existing exit code');
    assert.equal(result.signal, null);
    assert.equal(result.report.preflight.appReady, true);
    assert.equal(result.report.preflight.backendReady, false);
    assert.equal(typeof result.report.unavailable, 'string');

    const appStage = result.report.preflight.app;
    const backendStage = result.report.preflight.backend;
    assert.ok(appStage, 'the successful app result must survive a later backend failure');
    assert.ok(backendStage, 'the backend failure must have its own stage result');
    assert.equal(appStage.stage, 'app');
    assert.equal(backendStage.stage, 'backend-auth');
    assert.equal(appStage.url, app.url);
    assert.equal(appStage.status, 200);
    assert.equal(appStage.ready, true);
    assert.equal(appStage.error, null);
    assertTiming(appStage);
    assert.equal(backendStage.url, `${backend.url}/auth/me`);
    assert.equal(backendStage.status, null);
    assert.equal(backendStage.ready, false);
    assert.equal(typeof backendStage.error?.name, 'string');
    assert.equal(typeof backendStage.error?.message, 'string');
    assert.ok(backendStage.error.message.length > 0);
    assertTiming(backendStage);
  } finally {
    await Promise.all([close(app.server), close(backend.server)]);
  }
});
