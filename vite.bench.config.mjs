import { defineConfig } from 'vite';

export default defineConfig({
  root: 'public',
  build: { outDir: '../out/bench', emptyOutDir: true, rollupOptions: { input: 'public/bench.html' } },
});
