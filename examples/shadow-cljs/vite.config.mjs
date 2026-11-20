import { defineConfig } from 'vite';
import { resolve } from 'node:path';

// demo.server runs Vite in middleware mode during development and serves dist/ in production
export default defineConfig({
  root: 'public',
  // Bundled together up front, so that every module shares one copy of solid-js
  optimizeDeps: {
    include: ['solid-js', 'solid-js/refresh', '@solidjs/h', '@solidjs/web',
              '@solidjs/web/frames', '@solidjs/web/server-functions'],
  },
  build: {
    outDir: '../dist',
    emptyOutDir: true,
    rollupOptions: {
      input: resolve('public/js/main.js'),
      output: { entryFileNames: 'assets/app.js', chunkFileNames: 'assets/[name]-[hash].js' },
    },
  },
});
