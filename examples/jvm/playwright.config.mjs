import { defineConfig } from '@playwright/test';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// `yarn test:browser` runs the uberjar from `yarn build` on an empty database. With
// BASE_URL set it tests a server that is already running instead, such as `yarn dev` at
// http://localhost:3000.
const port = 8080;

export default defineConfig({
  testDir: 'test/browser',
  // The tests count viewers, so they run one at a time
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  use: { baseURL: process.env.BASE_URL ?? `http://localhost:${port}` },
  projects: [
    { name: 'reitit API', metadata: { path: '/' } },
    { name: 'server functions', metadata: { path: '/?via=server-functions' } },
  ],
  webServer: process.env.BASE_URL ? undefined : {
    // Starts each run from an empty database
    command: 'rm -f "$TODOS_DB" && java -jar target/todos.jar',
    url: `http://localhost:${port}`,
    env: { PORT: String(port), TODOS_DB: join(tmpdir(), 'solid-cljs-jvm-browser-test.db') },
    reuseExistingServer: false,
  },
});
