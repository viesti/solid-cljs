import { defineConfig } from '@playwright/test';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// `yarn test:browser` runs the production server from `yarn build` on an empty database.
// With BASE_URL set it tests a server that is already running instead, such as `yarn dev` at
// http://localhost:3000.
const port = 3000;

export default defineConfig({
  testDir: 'test/browser',
  // The tests count viewers, so they run one at a time
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  use: { baseURL: process.env.BASE_URL ?? `http://localhost:${port}` },
  projects: [{ name: 'production', metadata: { path: '/' } }],
  webServer: process.env.BASE_URL ? undefined : {
    // Starts each run from an empty database
    command: 'rm -f "$TODOS_DB" && yarn start',
    url: `http://localhost:${port}`,
    env: { PORT: String(port), TODOS_DB: join(tmpdir(), 'solid-cljs-clojurific-browser-test.db') },
    reuseExistingServer: false,
  },
});
