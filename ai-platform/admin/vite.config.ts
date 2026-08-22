import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The console is served from /admin/ behind Nginx, so built asset URLs must carry
// that prefix; without it every /assets/* request 404s in the container deployment.
export default defineConfig({
  base: '/admin/',
  plugins: [react()],
  server: { port: 5173 },
  build: { sourcemap: false },
  test: { environment: 'jsdom', globals: true },
});
