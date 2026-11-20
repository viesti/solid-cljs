# Solid 2 work order

Remaining work for full Solid 2.0 support in solid-cljs, in order. Update the status of each
step as it changes, and record decisions under the step.

## Working notes

- Work happens on branch `solid-2`, pushed to the fork remote `viesti`
  (`git push viesti solid-2`). No pull request to roman01la/solid-cljs for now.
- Tests: `yarn test` (unit tests, then server tests under plain Node), `yarn vitest`
  (browser tests, compiled normally and with `:solid/hydratable`, plus the hydration round
  trip). Run `rm -rf .shadow-cljs/builds` first after changing macros or compiler options.
- After pushing library changes, pin the new commit in README.md, examples/shadow-cljs/deps.edn,
  examples/clojurific/deps.edn, examples/squint/squint.edn and examples/squint/README.md, check
  the three examples against the pushed commit, and push again.
- Reference output: compile JSX with `@solidjs/babel-plugin` from the scratchpad project
  jsxref (`node compile.cjs file.jsx dom|ssr`, `node h.cjs file.jsx dom|ssr` for hydratable).
- Where things are: macros in src/solid/core.clj, template compiler in src/solid/template.clj,
  runtime in src/solid/core.cljs, prop names in src/solid/props.cljc, linter in
  src/solid/lint.clj, clj-kondo config in resources/clj-kondo.exports.

## Steps

### 1. Router claims for anchors and forms — done

Templates claim `<a>` and `<form>` (src/solid/template.clj `claim-attrs`), and server output adds
`ssrLinkClaim` attributes to anchors. Tests: test/vitest/router.cljs (active link and
navigation with router 2.0) and `router-links-on-the-server` in test/solid/ssr_test.cljs. The
browser test failed once, on its first run after a clean build, and passed in 14 runs since,
including one with a cold Vite cache. Watch for it.

Solid's compiler calls `claimElement(el)` for `<a>` and `<form>` on the client (after the
dynamic `href`/`action` is set, with the owner when it is dynamic) and `ssrLinkClaim(attrs)` on
the server. Router 2.0 uses the claims for `aria-current`/`data-active` and form interception.
Emit the same from templates, then test with `@solidjs/router` 2.0 (devDependency).

### 2. Signal, memo and effect options; contexts without a default — done

Options maps convert keys like props (`reactive-options` in src/solid/core.cljs). Effects take
`:error`, which builds Solid's `{effect, error}` bundle. Test: "Signal, memo and effect options"
in test/vitest/features.cljs. The linter still warns about writes in a `defui` body even when
the signal has `:owned-write`, since it cannot see the option; `{:solid/lint false}` silences it.

- `s/signal` and `s/memo`: `:owned-write`, `:unobserved`, `:lazy`, `:name`.
- `s/effect`: the `{effect, error}` form, as an `:error` option.
- `s/create-context` with no argument (default-less, throws without a provider).

### 3. Server functions — done

`defserverfn` in src/solid/server_functions.clj; runtime in src/solid/server_functions.cljs
(loads in both builds) and src/solid/server_functions/server.cljs (handler, server only: it
uses `@solidjs/web/storage`, which imports `node:async_hooks`). Ids are `"<ns>/<name>"`.
Arguments and results travel as EDN unless `{:encoding :js}`. The macro refuses the `:both`
render target. Test: test/solid/server_functions_test.cljs, run by the server test runner,
which routes the browser runtime's global fetch to the handler in the same process. Checked by
hand that a `:dom` build contains only the reference; the dev build's source map still holds
the source, which the README warns about.

A macro (working name `s/defserverfn`) that registers the function with
`registerServerReference` on the server and emits `createServerReference` with the same id on
the client, plus a handler wrapper for `handleServerFunctionRequest`. The server body must not
reach browser bundles: require `:solid/render :dom` for client builds, or remove the server
branch otherwise. Decide the id scheme (namespace and name).

### 4. Smaller API gaps — done

Wrappers in src/solid/core.cljs ("More of Solid's API" and the server utilities), `s/until` as
a macro, and `[handler data]` vectors for events. Tests: the "Event data pairs, until..." test
in test/vitest/features.cljs and `cookies` in test/solid/ssr_test.cljs. Thin wrappers without
their own tests: `enable-external-source`, `configure-client-errors`, `reset-error-halt!`,
`on-hydration-end`, `lazy-module`, `respond`, `reload`, `configure-server-errors`.

`markRaw`, `until`, `NotReadyError`, `TimeoutError`, `createOwner`, `isDisposed`, `repeat`,
`enableExternalSource`, `configureClientErrors`, `resetErrorHalt`, `isHydrating`,
`onHydrationEnd`, `lazyModule`, event handler data tuples, `respond`, `reload`, cookie helpers,
`configureServerErrors`.

### 5. Tooling — done

- Hot reload through `solid-js/refresh`: done. With `:solid/refresh`, `defui` defines a refresh
  proxy (`-refresh-component` in src/solid/core.cljs), standing in for the bundler hot API with a
  per-component table that survives reloads. Components get Solid's `$DEVCOMP` marker, which
  the proxy needs to follow updates, and a signature hashed from their source as written, with
  the numbers of generated names removed, so unchanged components keep their state. Test:
  test/vitest/refresh.cljs; checked by hand in the playground with shadow-cljs watch, where an
  edit to the toolbar kept a todo added before it. Both examples now enable it in dev builds
  (Clojurific through a cljs.edn `:dev` profile) and render once; checked by hand in both that
  editing the counter keeps a todo added before the reload.

- Component names and locations: done. `$` passes `"<ns>/<name>"` and `"<ns>:<line>:<col>"` to
  `createComponent` behind `goog.DEBUG`, built with an explicit `{:tag 'boolean}` because
  syntax-quote qualifies a `^boolean` hint. Test: test/vitest/devnames.cljs.
- Custom elements: done. Templates containing a hyphenated tag clone with `importNode`
  (template flag 1), which upgrades them at once, and each custom element gets `_$owner`, as
  Solid's compiler does. Attributes stay attributes, as in Solid 2. Test:
  test/vitest/custom_elements.cljs.
- `solid.squint`: done. Added `store` and `replacement` (squint/src/solid/squint.cljs); the
  Squint example's todo list uses them. Squint writes generators (`^:gen`, `js-yield`), so it
  uses Solid's `action` directly, and server rendering goes through Solid's own compiler.
- Found while checking release output: the benchmark bundle had grown to 290 kB because Closure
  aliased the `solid-js` module object in `s/signal`, which stops Vite from tree-shaking it.
  Runtime functions now bind computed arguments before calling Solid, the bundle is back to
  178 kB, and dev/bench/run.mjs fails if a module object is used other than by property access.

- Component names and locations in dev builds (`createComponent(Comp, props, name, loc)`).
- Hot reload through `solid-js/refresh`, keeping component state.
- Custom elements through templates: test properties and attributes.
- `solid.squint`: store, action and server-rendering helpers.

### 6. Deferred Solid features — done

- Assets (`s/warm-asset`, `s/acquire-asset`), trusted types (`s/set-trusted-types-policy!`,
  `s/trusted-html`), performance tracks (src/solid/performance.cljs, its own namespace so only
  builds that use it bundle it) and the request scope from `@solidjs/web/storage`
  (src/solid/server.cljs, server only): done. Tests in test/vitest/features.cljs ("Assets,
  trusted HTML and performance tracks") and `request-scope` in test/solid/ssr_test.cljs.
- `s/dispatch-as-interaction`: done. Solid's production and server builds call the function
  directly, and the development and observe builds open an interaction record for the event.
  Test: "A listener wrapped in dispatch-as-interaction records an interaction" in
  test/vitest/features.cljs, which reads the record through `solid-js/attribution`. The first
  `yarn vitest` after adding that import failed three tests while Vite optimized the new
  dependency mid-run; every run since passed, including two with a cold Vite cache.

### 7. Server components — done

`defservercomponent` and `server-component` in src/solid/server_components.clj; runtime in
src/solid/server_components.cljs (`install!`, `configure-server!`, `plugin`, `async-arg`).
`s/ui` makes anonymous components, `:$key` on an element renders `_key` on the server and
nothing in the browser, and the render functions take `:plugins`. Tests:
test/solid/server_components_test.cljs (frame stream and document render, Node) and
test/hydration/server_components_test.cljs (document adoption without requests, and a
streamed component whose keyed fill keeps its state across a refetch that reorders the list),
with fixtures written by test/hydration/server.cljs.

Decisions and findings:

- Slots need no compiler support. Solid's compiler emits a slot placement as an ordinary
  component call, so `($ editor {...})` with the slot as the tag is enough.
- Ids are the namespace and name joined with slashes (`app/todos/todo-list`). Frames ignore a
  boundary id that contains a dot (`isBoundaryId`), so `"<ns>/<name>"` broke document adoption.
- Arguments travel as one EDN string on both sides, including direct calls during a document
  render, so the document's frame address matches the browser's.
- `defui` components now resolve their element when called. Solid's frames runtime calls the
  server component and slot fills directly, and an unresolved element computed its hydration key
  outside any owner.
- `install!` indexes the document's frames on first use, so it must run after the body exists.
- `asyncArg` has no test of its own.

### 8. Live server components — done

`{:live true}` and `{:broadcast true}` on `defservercomponent` wrap the reference with Solid's
`live` and `broadcast` on both sides (`declare-call` in src/solid/server_components.cljs).
Tests: `live-frame-stream`, `broadcast-frame-stream` and `live-document-render` in
test/solid/server_components_test.cljs, the live update in
test/hydration/server_components_test.cljs, and adoption of a live component from the document
in test/hydration/live_server_components_test.cljs. The browser tests play back a stream that
hydration.server recorded in two parts, and helpers shared by both files are in
test/solid/frame_fixtures.cljs.

Decisions and findings:

- Server signals do not change, so a live component changes through async sources. A memo over
  `s/from` with a subscribe function works; each value updates the live hole that reads it.
- The server marks a call live by its address (`/live/<id>`), so the handler needs nothing new.
- A test reading a frame stream must read the response itself, not a clone, or cancelling it
  leaves the server's subscription open.
- Solid indexes a page's server-rendered frames once, on first use, so a second document in the
  same page is not adopted. The live adoption test has a file of its own for a fresh page.
- A live hole stops updating (`SSR_LIVE_HOLE_LATCHED`) when its re-run creates an async source,
  or when a live attribute calls a slot. Keyed slots in the content of a live list keep
  updating (checked in Node while planning step 9). Not tested: reconnects after a dropped
  connection and the `onstatus` hook.

### 9. Server features in the examples — done

Each example gets a shared todo list backed by SQLite, beside the existing in-browser todo list,
which stays in a section of its own. Open the page in two windows and changes in one appear in
the other. Examples may show fewer features where a tool cannot support one; record why here.

Features to show, and what each demonstrates:

- The page arrives rendered and hydrates: `s/render-to-stream`, `s/hydrate`.
- Adding, toggling, renaming and deleting todos write to SQLite: `defserverfn`.
- A new todo shows at once until the server confirms it: `s/action` with an optimistic update.
- The list and the "open · done · viewers" counts update for every viewer: a
  `defservercomponent` with `{:broadcast true}`, reading a change feed through `s/memo` over
  `s/from`. The viewer count is the number of open subscriptions.
- Each row's checkbox and edit field keep their state while other viewers' changes arrive:
  slots with `:$key`.
- "Signed in as Ann", with a name prompt on the first visit and a cookie: the request event and
  the cookie helpers. Each todo records who added it.
- A production build: `yarn build` and `yarn start` run the server without Vite's dev server.

Storage is Node's built-in `node:sqlite`, with a database file in the example folder (ignored by
git). Writes publish to an in-process emitter that the live component reads.

Sub-steps:

1. shadow-cljs example — done. Shows every feature above. Checked by hand in two browser
   windows, in development (`yarn dev:local`, including hot reloads of browser and server code)
   and in production (`yarn build`, `yarn start`). Findings, each fixed in the library with a
   test:
   - A `defserverfn` returning `s/respond` lost its headers, since the EDN transport printed
     the envelope. `:headers` in `s/respond` also needed converting from a map.
   - The linter reported writes inside `s/action` as writes in the component body.
   - In hydratable builds `s/for` rendered a fixed string inside a hydration scope, so a live
     list never updated. Lists are now component children, as Solid's compiler treats `<For>`.
   - `defui` checked for children with `in` in the body, which warned `STRICT_READ_UNTRACKED`
     for slot fills, whose props are reactive.

   Example-side notes: Vite must pre-bundle all Solid entries (`optimizeDeps.include`), or a
   late discovery loads two copies of `solid-js`. Vite must watch public/js in middleware mode,
   or page reloads get stale code. An earlier commit (`f1eadf9`, the linter fix) accidentally
   deleted the example's old core.cljs and index.html, which this example replaces. The warning
   "A broadcast server component read the request" comes from Solid's own `renderToStream` in
   its development build. Once the viewer count stayed one too high after a window closed,
   which did not happen again: watch for it.
2. Clojurific example — done, with every feature. Clojurific compiles each namespace once for
   the browser and its server environment, so per-build compiler options are impossible. The
   user chose to make `defserverfn` and `defservercomponent` compile both sides without
   `:solid/render` and choose with `isServer` at runtime (library change with a test in
   test/solid/core_test.cljs). The production browser bundle contains none of the server
   bodies (checked by grepping it for their strings); development bundles do. Shared code
   reaches the database through demo/store.cljs, which the server entry fills. The server is a
   Hono app (`:js-entries`), served by `@hono/vite-dev-server` with `.cljs` added to its
   excludes, and bundled by `@hono/vite-build` in `vite build --mode server`. `demo.client` is
   in `:main`, since no HTML file names it. Checked by hand in two windows in development
   (including hot reloads of browser and server code) and in production. The server build
   warns `IMPORT_IS_UNDEFINED` for browser-only imports of Solid's server modules, which is
   expected and harmless. The library commit `de8a462` accidentally deleted the old
   core.cljs and index.html, which this example replaces.
3. Squint example — done, with every feature. It uses Solid's own pieces: the Vite plugin's
   start mode (`ssr: true`, `serverFunctions` with `components`, `start.nodeServer`), with the
   app and document modules in out/ as default exports (`(def default App)`). A string as the
   first form of a Squint function body becomes a `"use server"` directive. The server
   functions filter must include `out/**/*.jsx`, and `squint compile` must run before Vite,
   since the plugin checks that the app module exists. Server functions import app.db
   dynamically, so the browser never loads `node:sqlite`. Solid's production compiler refuses a
   `broadcast` render that reads a binding of the function's body, so the list subscribes
   through a module-level function. The user name comes from a `GET` server function read in a
   memo. Changing vite.config.mjs while the dev server runs crashes it (Squint's nREPL port is
   taken on restart). Checked by hand in two windows in development (including a hot reload that
   kept another component's state) and in production; the browser bundle has no server code.
4. READMEs — done. Each example's README describes its server setup, and the main README
   lists the three examples after its code example.
5. Pins — done. The README and the three examples pin `8cab7bf`. All three build with
   `yarn build` against it, and their production servers render the page and answer the live
   stream. Development was checked by hand against the local checkout in sub-steps 1 to 3.

### 10. Format the code with clojure-lsp — done

`clojure-lsp format` in the repository root formats src, dev, resources and test. squint/src and
the examples' sources are separate projects, formatted with
`clojure-lsp format --filenames squint/src:examples/shadow-cljs/src:examples/clojurific/src:examples/squint/src`.
The formatting commit changes whitespace only (`git diff -w` is empty).

### 11. Remaining Solid API — not started

From a review that compared the exports of `solid-js` and `@solidjs/web` (and its frames and
server-functions entries) with what solid-cljs references, leaving out Solid's internals, the
compiler's runtime helpers and wire constants. Work in this order:

1. A response from a page render — done. `solid.server/render-to-response` wraps
   `createSSRResponse`; a `Location` header set during the render makes a redirect (`s/redirect`
   returns a `Response` for server functions and does nothing in a render). Its `:document`
   option is hiccup, turned into HTML by the new `solid.server/html`, with `solid.server/raw`
   for the hydration script. Solid has no hook for HTML after the render, so the wrapper writes
   the document's end through a proxy of the writable before the stream ends. The shadow-cljs and
   Clojurific example servers use it. Tests: `hiccup-html`, `page-responses` and
   `page-redirects` in test/solid/ssr_test.cljs.
2. Per-call options for server functions — done. The EDN wrapper shares the reference's
   metadata object and forwards its invocation channel (`Symbol.for` keys), so Solid's `invoke`
   and `withMeta` work on a `defserverfn` in the browser. On the server, the directly called
   function gets an in-process channel that rejects on abort. `defserverfn` takes `:meta`, and
   `solid.server-functions/invoke` takes an options map. Test: `per-call-options` in
   test/solid/server_functions_test.cljs, whose fetch stub now rejects an aborted signal.
3. The `prop:` attribute namespace — done. `:prop/page-size` sets the property `pageSize`:
   once when the value is a literal, in an effect otherwise, and nothing on the server, as
   Solid's compiler does. Spread maps pass it to Solid's `spread` as `prop:pageSize`, and server
   spreads leave it out. Tests: "Keywords in the prop namespace set DOM properties" in
   test/vitest/custom_elements.cljs and `properties-on-the-server` in test/solid/ssr_test.cljs.
4. Live data from server functions — done. `{:live true}` and `{:broadcast true}` on
   `defserverfn`. The transport maps each value to EDN, and the browser side maps them back,
   keeping the live source brand and passing `onstatus` through. Tests: `live-values` and
   `broadcast-values` in test/solid/server_functions_test.cljs. Not tested: a memo over a live
   server function in the browser, and reconnects.
5. Forms that work without JavaScript: a server function's form-post URL (`.url`,
   `serverFunctionActionUrl`), `createNoJSHandler`, and the flash-cookie helpers.
6. `serverFunctionUrl`, the URL of a GET server function call, for preloading.
7. `getServerFunctionInvocation` (whether a call is live or broadcast), which the examples
   reach through interop to count viewers.
8. `solid-js/attribution` (history, costs, feedback, why), as a namespace of its own.
9. The primitives behind the boundary components: `createErrorBoundary`,
   `createLoadingBoundary`, `createRevealOrder`.
10. Store utilities: `$TRACK`, `isWrappable`, `isEqual`.
11. `waitAsset`.
12. Server observability: `getTraceContext`, `reportRequestFailure`.
13. Frames: the `FRAME_APPLIED_EVENT` event name.
14. A `solid.router` namespace for `@solidjs/router` 2.0, beyond the link and form claims.
15. `configureRefresh` from `solid-js/refresh`.

Optional: a transit encoding for server functions beside EDN and `:js`. It would not close any
gap above, but it is faster for large payloads and has extensible type handlers.

### Status

Steps 1 to 10 are done. Step 11 is in progress: items 1 to 4 are done.

### Deferred

Nothing.

Not planned: a Clojars release. The fork is used through git dependencies pinned to a commit
on `viesti/solid-2`.
