// Calls the JVM's server functions with Solid's own browser client, as the page does:
// node test/protocol.mjs http://localhost:8080
// demo.server-functions-test runs it against a server it starts. Arguments and results are EDN
// strings, as solid-cljs's defserverfn sends them.
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const client = await import(fileURLToPath(new URL(
  '../node_modules/@solidjs/web/server-functions/dist/client.js', import.meta.url)));
const { createServerReference, GET, live } = client;

// The client requests paths, as a page would from its own origin
const origin = process.argv[2];
const nodeFetch = globalThis.fetch;
globalThis.fetch = (input, init) =>
  nodeFetch(typeof input === 'string' && input.startsWith('/') ? origin + input : input, init);

const ref = (name) => createServerReference('demo.todos/' + name, name);
const addTodo = ref('add-todo');
const toggleTodo = ref('toggle-todo');
const listTodos = GET(ref('list-todos'));
const todoFeed = live(ref('todo-feed'));

// An EDN string literal, as pr-str writes one
const edn = (s) => '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n') + '"';
// A title whose EDN holds every character that seroval escapes in a string node: EDN escapes
// control characters itself, which leaves quotes, backslashes, < and the line separators
const title = 'Buy "milk" <b>\\ \n \u2028 ✓';

const check = async (label, f) => {
  await f();
  console.log('ok   ' + label);
};

// The feed's first value is the current state, then one value per change
const feed = todoFeed('[]')[Symbol.asyncIterator]();
const first = await feed.next();

await check('a live call sends the current state first', () => {
  assert.match(first.value, /^\{:todos \[.*\], :viewers \d+\}$/s);
});

let added;
await check('a call returns its result as EDN, with a timestamp as #inst', async () => {
  added = await addTodo(`[${edn(title)}]`);
  assert.ok(added.includes(`:title ${edn(title)}`), added);
  assert.match(added, /:created-at #inst "\d{4}-\d\d-\d\dT/);
});

await check('a live call sends the next value after a change', async () => {
  const next = await feed.next();
  assert.ok(next.value.includes(`:title ${edn(title)}`), next.value);
});

await check('a call returns a result that is not a map', async () => {
  const id = added.match(/:id (\d+)/)[1];
  assert.equal(await toggleTodo(`[${id}]`), 'true');
  assert.equal(await toggleTodo('[-1]'), 'nil');
});

await check('a failure rejects with the server\'s message', async () => {
  await assert.rejects(addTodo('[" "]'), { message: 'A todo needs a title' });
});

await check('an unknown function fails, with a message outside Latin-1 intact', async () => {
  await assert.rejects(ref('missing-✗')('[]'), { message: 'No server function demo.todos/missing-✗' });
});

await check('a GET function answers', async () => {
  const id = added.match(/:id (\d+)/)[1];
  assert.match(await listTodos('[]'), new RegExp(`:id ${id},`));
});

await feed.return();
console.log('done');
