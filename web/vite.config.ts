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
      },
    },
    test: {
      include: ['src/**/*.test.{ts,tsx}'],
      clearMocks: true,
    },
  };
});
