const REQUEST_TIMEOUT_MS = 5000;

function asError(error) {
  if (error && typeof error === 'object') {
    return {
      name: typeof error.name === 'string' ? error.name : 'Error',
      message: typeof error.message === 'string' ? error.message : String(error),
    };
  }
  return { name: 'Error', message: String(error) };
}

async function requestStage(stage, url, isReady) {
  const startedAt = new Date().toISOString();
  const started = performance.now();
  let status = null;
  let ready = false;
  let error = null;

  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
    status = response.status;
    ready = isReady(response);
    if (!ready) {
      const statusText = response.statusText ? ` ${response.statusText}` : '';
      error = { name: 'HttpStatusError', message: `HTTP ${status}${statusText}` };
    }
  } catch (requestError) {
    error = asError(requestError);
  }

  const completedAt = new Date().toISOString();
  return {
    stage,
    url,
    startedAt,
    completedAt,
    elapsedMs: Math.max(0, performance.now() - started),
    status,
    ready,
    error,
  };
}

export async function runMemoryPreflight(appUrl, backendUrl) {
  const app = await requestStage('app', appUrl, (response) => response.ok);
  const backend = await requestStage('backend-auth', `${backendUrl.replace(/\/+$/, '')}/auth/me`,
    (response) => response.ok || response.status === 401);

  return {
    app,
    backend,
    appStatus: app.status,
    backendAuthStatus: backend.status,
    appReady: app.ready,
    backendReady: backend.ready,
  };
}
