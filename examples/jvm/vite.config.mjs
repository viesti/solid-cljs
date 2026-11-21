import { defineConfig } from 'vite';

// In development Vite serves the page on port 3000 and passes /api, including the event
// stream, to the JVM on port 8080. `vite build` writes the page and its assets to dist/,
// which the uberjar serves.
export default defineConfig({
  root: 'public',
  server: {
    port: 3000,
    strictPort: true,
    proxy: { '/api': 'http://localhost:8080' },
    // shadow-cljs reloads changed code itself and keeps component state, where Vite would
    // reload the whole page whenever shadow-cljs writes public/js
    hmr: false,
  },
  // Bundled together up front, so that every module shares one copy of solid-js
  optimizeDeps: {
    include: ['solid-js', 'solid-js/refresh', '@solidjs/h', '@solidjs/web'],
  },
  build: {
    outDir: '../dist',
    emptyOutDir: true,
  },
});
