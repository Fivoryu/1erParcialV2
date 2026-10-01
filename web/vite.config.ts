import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';

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
