import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Production build goes straight into the jar's static resources (ADR-0022).
// `npm run dev` serves the editor on http://localhost:5173 and proxies /api to a running backend.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    // Lets a test read styles.css as text (`?raw`) to check the theme tokens and hover rules (ADR-0024).
    css: { include: [/styles\.css/] },
  },
});
