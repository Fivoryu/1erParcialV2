import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';

// @types/node is not installed in this project, so node:http types are unavailable here. This
// structural type describes exactly the surface the bypass reads (IncomingMessage.headers.accept,
// typed as string | string[] | undefined), so the header read is checked without importing node:http.
interface ProxyRequest {
  headers: { accept?: string | string[] | undefined };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_');
  const backendTarget = env.VITE_API_PROXY_TARGET || env.VITE_API_BASE_URL || 'http://localhost:8080';

  return {
    plugins: [react()],
    server: {
      proxy: {
        // Every top-level API path needs its own entry here, because the editor talks to the backend with
        // relative URLs when VITE_API_BASE_URL is unset. A path that is missing silently 404s in development
        // while the build stays green: that already happened with /deployments and with /invitations.
        '/projects': {
          target: backendTarget,
          changeOrigin: true,
          // /projects is the ONLY API prefix that collides with a client route (/projects/:id in
          // App.tsx), so it is the only entry with this bypass. Do not copy it to the other proxy
          // entries out of symmetry: they have no route collision and a bypass there would be
          // dead code that masks future route additions.
          // A browser hard navigation sends Accept: text/html,...; every application fetch sends
          // Accept: */* or application/json, so discriminating on Accept routes the SPA document
          // to index.html while data requests keep reaching the backend unchanged.
          bypass: (req: ProxyRequest) => {
            const accept = req.headers.accept;
            const acceptValues = typeof accept === 'string' ? [accept] : accept ?? [];
            return acceptValues.some((value) => value.includes('text/html'))
              ? '/index.html'
              : undefined;
          },
        },
        '/auth': {
          target: backendTarget,
          changeOrigin: true,
        },
        '/deployments': {
          target: backendTarget,
          changeOrigin: true,
        },
        '/invitations': {
          target: backendTarget,
          changeOrigin: true,
        },
      },
    },
    test: {
      include: ['src/**/*.test.{ts,tsx}'],
      clearMocks: true,
    },
  };
});
