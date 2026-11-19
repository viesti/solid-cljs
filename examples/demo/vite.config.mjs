import { defineConfig } from 'vite';

export default defineConfig({
  root: 'public',
  // shadow-cljs hot reloads its own output, a reload from Vite would reset the page
  server: { port: 3000, watch: { ignored: ['**/js/**'] } },
  build: { outDir: '../dist', emptyOutDir: true },
});
