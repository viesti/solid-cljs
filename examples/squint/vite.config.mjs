import { defineConfig } from 'vite';
import squint from 'squint-cljs/vite';
import solid from '@solidjs/vite-plugin';

export default defineConfig({
  // Squint compiles src/**/*.cljs to out/**/*.jsx, which Solid's JSX compiler then compiles
  plugins: [squint(), solid()],
  server: { port: 3000 },
});
