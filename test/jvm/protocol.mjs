// Calls the server functions of solid.server-functions.ring-test with Solid's own browser
// client, as a page does: node test/jvm/protocol.mjs http://localhost:18091
// Arguments and results are EDN strings, as solid-cljs's defserverfn sends them.
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const client = await import(fileURLToPath(new URL(
  '../../node_modules/@solidjs/web/server-functions/dist/client.js', import.meta.url)));
const { createServerReference, GET, live } = client;

// The client requests paths, as a page would from its own origin
const origin = process.argv[2];
const nodeFetch = globalThis.fetch;
globalThis.fetch = (input, init) =>
  nodeFetch(typeof input === 'string' && input.startsWith('/') ? origin + input : input, init);

const ref = (name) => createServerReference('solid.server-functions.ring-test/' + name, name);

// An EDN string literal, as pr-str writes one
const edn = (s) => '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n') + '"';
// A string whose EDN holds every character that seroval escapes in a string node: EDN escapes
// control characters itself, which leaves quotes, backslashes, < and the line separators
const title = 'Buy "milk" <b>\\ \n   ✓';

const check = async (label, f) => {
  await f();
  console.log('ok   ' + label);
};

await check('a call returns its result as EDN, with a timestamp as #inst', async () => {
  const result = await ref('add-item')(`[${edn(title)}]`);
  assert.ok(result.startsWith(`{:title ${edn(title)}, :at #inst "`), result);
});

await check('a GET function answers through a GET', async () => {
  assert.equal(await GET(ref('echo-get'))(`[${edn(title)}]`), `[${edn(title)} :get]`);
});

await check('a failure rejects with its message, also outside Latin-1', async () => {
  await assert.rejects(ref('fail-with-message')('["nope ✗"]'), { message: 'nope ✗' });
});

await check('other exceptions reject with a generic message', async () => {
  await assert.rejects(ref('fail-badly')('[]'), { message: 'Internal Server Error' });
});

await check('a custom encoding is used both ways', async () => {
  assert.equal(await ref('custom-encoded')('X[41]'), 'X42');
});

await check('a live function sends its current value, then one per change', async () => {
  const feed = live(ref('counter-feed'))('[]')[Symbol.asyncIterator]();
  const first = Number((await feed.next()).value);
  await ref('bump')('[]');
  assert.equal(Number((await feed.next()).value), first + 1);
  await feed.return();
});

console.log('done');
