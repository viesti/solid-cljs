import { defineConfig } from 'vite';
import squint from 'squint-cljs/vite';
import solid from '@solidjs/vite-plugin';

// Squint compiles src/**/*.cljs to out/**/*.jsx, which Solid's JSX compiler then compiles. In
// Solid's start mode the plugin renders the page on the server, compiles "use server"
// functions, and builds a Node.js server for production.
export default defineConfig({
  plugins: [
    squint(),
    solid({
      ssr: true,
      // Squint's output in out/ holds the "use server" functions
      serverFunctions: { components: true, filter: { include: 'out/**/*.jsx' } },
      start: { app: 'out/app/app.jsx', document: 'out/app/document.jsx', nodeServer: true },
    }),
  ],
  server: { port: 3000 },
});
