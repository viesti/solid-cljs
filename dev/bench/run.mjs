// Runs the release build of the benchmark page in headless Chromium and prints medians in ms
import { chromium } from '@playwright/test';
import { preview } from 'vite';

const server = await preview({ configFile: 'vite.bench.config.mjs', preview: { port: 4173 } });
const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  await page.goto('http://localhost:4173/bench.html');
  await page.waitForFunction(() => window.runBench);
  const results = await page.evaluate(() => { window.runBench(); return window.benchResults; });
  for (const [size, variants] of Object.entries(results)) {
    console.log(size);
    for (const [variant, m] of Object.entries(variants)) {
      console.log(`  ${variant.padEnd(12)} create ${m.create.toFixed(1).padStart(7)}  update ${m.update.toFixed(1).padStart(6)}  clear ${m.clear.toFixed(1).padStart(6)}`);
    }
  }
} finally {
  await browser.close();
  server.httpServer.close();
}
