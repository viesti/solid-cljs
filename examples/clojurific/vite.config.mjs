import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';
import devServer, { defaultOptions } from '@hono/vite-dev-server';
import build from '@hono/vite-build/node';

// The server entry, a module exporting the Hono app (:js-entries below)
const entry = 'target/cljs/cljs-esm-entries/server.js';

// `vite` serves the app, `vite build` bundles the browser code into dist/client, and
// `vite build --mode server` bundles the server into dist/server/index.js
export default defineConfig(({ mode }) => ({
  plugins: [
    cljf({
      outputDir: 'target/cljs',
      config: 'cljs.edn',
      // LOCAL=1 builds against the solid-cljs checkout this folder lives in
      aliases: process.env.LOCAL ? ['local'] : [],
      compilerOptions: {
        // The server renders the page, so no HTML file names the browser entry
        main: ["'demo.client"],
        jsEntries: { server: { exports: { default: "'demo.server/handler" } } },
      },
      // The dev server runs the server entry once it is compiled
      ssrEntries: ['server'],
      entries: mode === 'server' ? ['server'] : [],
      manifest: false,
    }),
    devServer({
      entry,
      // Vite serves the ClojureScript sources the page loads, compiled by the cljf plugin
      exclude: [...defaultOptions.exclude, /\.cljs($|\?)/],
      // The ssr environment's runner hot reloads namespaces in place
      loadModule: async (server, entry) => (await server.environments.ssr.runner.import(entry)).default,
    }),
    mode === 'server' && build({ entry, outputDir: 'dist/server', emptyOutDir: true, minify: false }),
  ],
  server: { port: 3000 },
  build: mode === 'server' ? {} : {
    outDir: 'dist/client',
    emptyOutDir: true,
    rollupOptions: {
      // The stylesheet is an input of its own, which the page links instead of the script loading it
      input: { app: 'src/demo/client.cljs', style: 'src/demo/style.css' },
      output: {
        entryFileNames: 'assets/[name].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name][extname]',
      },
    },
  },
}));
