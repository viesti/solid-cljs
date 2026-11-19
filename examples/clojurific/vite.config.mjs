import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';

export default defineConfig({
  // LOCAL=1 builds against the solid-cljs checkout this folder lives in
  plugins: [cljf({ outputDir: 'target/cljs', aliases: process.env.LOCAL ? ['local'] : [] })],
  server: { port: 3000 },
});
