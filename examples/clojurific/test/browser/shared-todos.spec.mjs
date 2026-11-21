// The shared todo list in two windows of one browser, which share the sign-in cookie. Every
// example has a copy of this file and runs it with `yarn test:browser`. A project's
// `metadata.path` picks the page, such as `/?via=server-functions` in the JVM example.
import { test, expect } from '@playwright/test';

test('the shared list stays in sync between two windows', async ({ browser }, testInfo) => {
  const path = testInfo.project.metadata.path ?? '/';
  const stamp = `${testInfo.project.name} ${Date.now()}`;
  const first = `Buy milk ${stamp}`;
  const second = `Walk the dog ${stamp}`;
  const renamed = `Buy oat milk ${stamp}`;

  const context = await browser.newContext();
  const errors = [];
  const open = async () => {
    const page = await context.newPage();
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    page.on('pageerror', (e) => errors.push(String(e)));
    await page.goto(path);
    return page;
  };
  // The examples that render on the server have a second todo list below, kept in the browser
  const shared = (page) =>
    page.locator('section', { has: page.getByRole('heading', { name: 'Shared todos' }) });
  const row = (page, title) => shared(page).locator('li', { hasText: title });
  const userName = (page) => shared(page).locator('p', { hasText: 'Signed in as' }).locator('strong');

  const a = await open();
  // A window closed by an earlier run may still count until the server notices
  await expect(shared(a)).toContainText(/ 1 viewing/, { timeout: 30_000 });
  await shared(a).getByPlaceholder('Your name').fill('Ann B');
  await shared(a).getByRole('button', { name: 'Sign in' }).click();
  await expect(userName(a)).toHaveText('Ann B');

  await test.step('adding shows the saved time from the server and the row', async () => {
    await shared(a).getByPlaceholder('What needs doing?').fill(first);
    await shared(a).getByRole('button', { name: 'Add', exact: true }).click();
    await expect(shared(a)).toContainText(new RegExp(`Saved “${first}” at \\d\\d:\\d\\d:\\d\\d`));
    await expect(row(a, first)).toContainText(/Ann B · added \d\d:\d\d/);
  });

  const b = await open();
  await test.step('a second window is signed in by the cookie and counts as a viewer', async () => {
    await expect(userName(b)).toHaveText('Ann B');
    await expect(row(b, first)).toBeVisible();
    await expect(shared(a)).toContainText(/ 2 viewing/);
  });

  await test.step("an open edit keeps its draft while the other window's changes arrive", async () => {
    await row(a, first).getByRole('button', { name: 'Edit' }).click();
    await row(a, first).locator('form input').fill(renamed);
    await shared(b).getByPlaceholder('What needs doing?').fill(second);
    await shared(b).getByRole('button', { name: 'Add', exact: true }).click();
    await row(b, first).getByRole('checkbox').check();
    await expect(row(a, second)).toBeVisible();
    await expect(row(a, first).getByRole('checkbox')).toBeChecked();
    await expect(row(a, first)).toContainText(/· done \d\d:\d\d/);
    await expect(row(a, first).locator('form input')).toHaveValue(renamed);
  });

  await test.step('renaming and deleting reach the other window', async () => {
    await row(a, first).getByRole('button', { name: 'Save' }).click();
    await expect(row(b, renamed)).toBeVisible();
    await row(b, second).getByRole('button', { name: 'x', exact: true }).click();
    await expect(row(a, second)).toHaveCount(0);
  });

  await test.step('closing a window lowers the viewer count', async () => {
    await b.close();
    // The JVM notices a closed stream on its next heartbeat writes, within about ten seconds
    await expect(shared(a)).toContainText(/ 1 viewing/, { timeout: 30_000 });
  });

  expect(errors).toEqual([]);
  await context.close();
});
