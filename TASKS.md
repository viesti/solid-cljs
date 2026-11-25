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
  examples/clojurific/deps.edn, examples/jvm/deps.edn, examples/squint/squint.edn and
  examples/squint/README.md, check the four examples against the pushed commit, and push again.
- The remote is named `origin` in some checkouts.
- Examples: `yarn build` then `yarn test:browser` in each example folder runs the Playwright
  test of the shared list against its production server (`BASE_URL=... yarn test:browser` for
  a running dev server). Run it in all four after re-pinning.
- Toolchain on this machine: Temurin JDK 25 in ~/.local/jdk/current and the Clojure CLI in
  ~/.local/bin, neither on the default PATH; Playwright's Chromium for `yarn vitest`.
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
in test/vitest/features.cljs, test/vitest/api_extras.cljs (`enable-external-source`,
`configure-client-errors`, `reset-error-halt!`, `on-hydration-end`, `lazy-module`),
test/solid/hydration_check.cljs (`on-hydration-end` during hydration), and `cookies`,
`response-helpers` (`respond`, `reload`) and `server-observability` (`configure-server-errors`)
in test/solid/ssr_test.cljs.

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
- `async-arg` (`asyncArg`) is tested in `async-slot-arg` in test/solid/server_components_test.cljs
  (frame stream and document render) and in test/hydration/server_components_test.cljs (settling
  in a browser slot fill). Note that `$` wraps dynamic props in a getter, so the promise passed
  to `async-arg` should be bound outside `s/ui` (or `$`) rather than constructed inside the prop
  expression.

### 8. Live server components — done

`{:live true}` and `{:broadcast true}` on `defservercomponent` wrap the reference with Solid's
`live` and `broadcast` on both sides (`declare-call` in src/solid/server_components.cljs).
Tests: `live-frame-stream`, `broadcast-frame-stream` and `live-document-render` in
test/solid/server_components_test.cljs, the live update and reconnect/`onstatus` tests in
test/hydration/server_components_test.cljs, and adoption of a live component from the document
in test/hydration/live_server_components_test.cljs. The browser tests play back streams that
hydration.server recorded, and helpers shared by both files are in
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
  updating (checked in Node while planning step 9). Reconnects after a dropped connection and
  the `onstatus` hook (`connected` -> `reconnecting` -> `connected` -> `closed`) are tested in
  test/hydration/server_components_test.cljs.

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

### 11. Remaining Solid API — done

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
   keeping the live source brand and passing `onstatus` through. Tests: `live-values`,
   `broadcast-values` and `live-reconnect-and-onstatus` in test/solid/server_functions_test.cljs,
   and a browser memo over a live server function with reconnect and `onstatus` in
   test/vitest/api_extras.cljs.
5. Forms without JavaScript — done. `sf/action-url` wraps `serverFunctionActionUrl`. The EDN
   transport treats a call that is not one string argument as a form post: the body gets the
   bound arguments and the `FormData`, and its raw result goes to Solid's default no-JS
   handler, which redirects back with a flash cookie. `sfs/flash` decodes it and
   `sfs/clear-flash-cookie` removes it. The wrappers and the directly called function now carry
   the reference's `id`. Test: `form-posts` in test/solid/server_functions_test.cljs.
6. `sf/url` — done, wrapping `serverFunctionUrl`, with the arguments as EDN for EDN functions.
   Tested in `form-posts`.
7. `sfs/invocation` — done, wrapping `getServerFunctionInvocation`. Tested in
   `server-function-calls`. The examples still call Solid's function through interop.
8. `solid.attribution` — done (`enable!`, `disable!`, `history`, `explain`, `dependencies`,
   `cost-tables`, `feedback-tables`, `format-rerun`). The interaction test in
   test/vitest/features.cljs reads its records through it.
9. Boundary primitives — done as `s/create-error-boundary`, `s/create-loading-boundary` and
   `s/create-reveal-order`. `s/error-boundary` was already Solid's `Errored` component, so the
   primitives take Solid's own names.
10. Store utilities — done: `s/track-keys` (`$TRACK`) and `s/wrappable?`. `isWrappable` checks
    only the shape, so a value given to `mark-raw` still counts as wrappable. Not wrapped:
    `isEqual`, the core's default reference equality, which `=` and `identical?` cover.
11. `s/wait-asset` — done.
12. `solid.server/trace-context` and `solid.server/report-request-failure` — done.
13. `sc/frame-applied-event` — done, tested in test/hydration/server_components_test.cljs.
    Tests for 9 to 12: "Boundary primitives, store keys and assets" in
    test/vitest/features.cljs and `server-observability` in test/solid/ssr_test.cljs.
14. `solid.router` — done: `router` from route maps, the histories, `pending-links`,
    `intent-preload`, the `use-*` hooks, `query`, `action`, `use-action`, `use-submissions` and
    `revalidate`. Test: "solid.router: route maps, params, navigation and queries" in
    test/vitest/router.cljs. Not wrapped: `liveQuery`, `formAction` beyond `action`, the
    file-system routes and the other preload strategies, which stay reachable through interop.
15. `configureRefresh` — not wrapped. Its documentation says it is meant for bundler
    integrations, called once before any hot update, and solid-cljs's `:solid/refresh` plays
    that part itself.

Found while pinning: Clojurific munges the reserved word `for`, so `js/Symbol.for` compiled to
`Symbol.for$`. Library code uses `(js-invoke js/Symbol "for" ...)` instead. The pinned examples
caught it; the library's own test builds are shadow-cljs only.

Optional: a transit encoding for server functions beside EDN and `:js`. It would not close any
gap above, but it is faster for large payloads and has extensible type handlers.

### 12. Pluggable server function encoding and timestamps — done

`defserverfn` and `defservercomponent` encode arguments and results as EDN (`pr-str` and
`cljs.reader`) by default, or pass them to Solid's serializer with `:encoding :js`. Solid's
serializer is seroval, not JSON: it carries `Date`, `Map`, `Set`, `BigInt`, typed arrays,
errors, promises, streams, files and repeated references, but not ClojureScript's persistent
data, which is why EDN is the default. The user chose a pluggable encoding over building transit
into the library.

Sub-steps 1 to 3 are done. `:encoding` takes `:js`, `:edn` or a map of `:encode` and `:decode`,
and `sf/set-default-encoding!` sets the encoding used when it is left out. The encoding is
resolved at each call, so a default set in an entry applies to functions defined before it ran.
`sf/edn` is the EDN encoding, and `sf/-codec` resolves an encoding for solid.server-components.
The internal helpers are renamed `sf/-caller`, `sf/-live-caller` and `sc/-encoded-arguments`,
each taking an optional encoding. A form post is now recognised by a `FormData` last argument
instead of by not being one string, so an encoding may produce values other than strings. The
README's transit example was compiled and run against `com.cognitect/transit-cljs` 0.8.280
(round-tripping `Money` and `js/Date` through `sfs/handle-request`), fixing a missing
`:refer [defserverfn]` in its `ns` form. Tests: `dates-in-edn` and `custom-encodings` in
test/solid/server_functions_test.cljs, `frame-stream-with-an-encoding` in
test/solid/server_components_test.cljs.

Sub-step 4: the examples store `created_at` and `done_at`, adding the columns to a database
file created before them, and show "added 14:02 · done 14:20" in the shared list. `add-todo`
returns the new todo and the page shows "Saved “…” at 14:02:05" from its `js/Date`, which
crosses as EDN's `#inst` in shadow-cljs and Clojurific and as seroval's `Date` in Squint.
Checked by hand in production builds of all three against the local checkout (adding, checking
and unchecking, and the server render after a reload). The README and the examples pin `8d259d6`,
and all three build against it, with production servers that render the page. Development mode
was not checked for this sub-step. `defserverfn` takes no docstring, so the example's note on `add-todo` is a
comment.

1. Pluggable encoding: `:encoding {:encode f :decode g}`, where `encode` turns the argument
   vector or the result into a string (or another value Solid's serializer carries) and `decode`
   reverses it. EDN and `:js` stay as they are, EDN as the default. It must work everywhere the
   EDN encoding does today: plain calls, `s/respond` envelopes (encode the value, keep status
   and headers), live and broadcast values (each value on its own), `invoke`, `url` (GET calls),
   form posts (which bypass the encoding), and server component arguments. Consider a way to set
   the default encoding once (a var or `configure-client`/`configure-server` option) instead of
   on every function.
2. A transit example in the README, using transit-cljs in the app, not as a library dependency.
   Show handlers for a custom type and that timestamps arrive as `js/Date`.
3. Tests: a custom encoding round-trips through the transport, including a `js/Date`; a
   `js/Date` survives the default EDN encoding (`#inst`, read by `cljs.reader`'s `inst` reader,
   which no test covers yet).
4. Timestamps in the examples: `created_at` and `done_at` columns in SQLite (epoch
   milliseconds), `js/Date` values in the rows, shown as "added 14:02" and "done 14:20" in the
   shared list. Make one timestamp cross the wire as data, since the list is rendered on the
   server: for example `add-todo` returns the new todo and the client shows "saved at 14:02:05".
   In shadow-cljs and Clojurific that exercises EDN's `#inst`; in Squint, seroval's `Date`.
   Re-pin and check all three examples, development and production.
5. Investigate: a seroval plugin for ClojureScript data (persistent maps, vectors, sets,
   keywords, symbols), which would give one encoding that carries both Clojure data and
   seroval's types (dates, streams, files) without a string inside Solid's serializer. Check
   seroval's plugin API (`seroval-plugins`, `createPlugin`) and how Solid's server functions
   accept serializer plugins. Record findings here before deciding whether to build it.

   Findings, from a probe run in Node.js against seroval 1.8.2 and `@solidjs/web` 2.0.3:
   - Seroval refuses ClojureScript collections and keywords (`SerovalUnsupportedTypeError`).
     A plugin per type works: `createPlugin` with `test`, `parse` (`sync`, `async` and `stream`,
     each recursing with `ctx.parse`), `serialize` and `deserialize` (recursing with
     `ctx.deserialize`), and `skipPlainObjects`. Six small plugins (keyword, symbol, map, set,
     vector, list) round-trip nested data with `js/Date` values intact.
   - Solid takes the plugins as `codec: {plugins}` in both `configureServerFunctionsClient`
     and `configureServerFunctionsServer`, and they must match. Arguments travel as plain JSON
     unless `enableRichArguments()` from `@solidjs/web/server-functions/rich-args` runs once
     in the browser.
   - Solid's server treats any result with `Symbol.iterator` (other than arrays, `Map`, `Set`
     and typed arrays) as a deferred iterable body before the codec sees it. ClojureScript
     collections are iterable, so a returned map arrived as an empty object. Wrapping the
     result in a `deftype` box, which Solid's result walker leaves alone, with a plugin for the
     box, fixed it. Live values would need the same box.
   - Size: 50 todo maps with dates are 7.2 kB as EDN (614 bytes gzipped) and 27.9 kB through
     the plugins (2.6 kB gzipped), about four times larger either way. Seroval spends a node
     on every keyword. `clj->js` data through seroval is 12.2 kB (1.0 kB gzipped).
   - Hydration data is serialized as JavaScript that runs inline as the page parses, before
     the ClojureScript bundle loads, so the plugins' `serialize` output (`$cljs.map(...)`)
     has no constructors to call. Server functions decode from JSON and do not have this
     problem.

   Decision (proposed): do not build it now. EDN or a custom encoding covers Clojure data,
   `:encoding :js` covers files and streams, and the plugin encoding costs four times the
   bytes, a global `enableRichArguments`, boxing and no hydration support. Revisit if a user
   needs Clojure data and streams in one call.

   Found on the way, an existing bug: on the server, an async `s/memo` whose promise resolves
   to ClojureScript data (a vector, keyword or map) makes `s/render-to-string-async` resolve
   to an empty string, with a `SerovalUnsupportedTypeError` in the log. Strings and JS
   objects work. Step 14 fixed it.

### 13. A JVM backend example — done (13.2 moved to the library in step 16)

A fourth example: a solid-cljs frontend whose backend runs on the JVM (Clojure), for teams
whose servers are Clojure rather than Node.js. It keeps the shared todo list from step 9
(a JVM database, the sign-in cookie, the timestamps from step 12) so that the four examples
can be compared.

Decisions (the user's, 2026-11-20):

- Rendering in the browser only, with `s/render`. The JVM serves a static page and the
  release bundle. No server rendering or hydration for now.
- Calls go to a reitit API, since a JVM backend often serves an API to other clients too and
  reitit is familiar. The example may also show `defserverfn` answered by the JVM through
  Solid's server function protocol, beside reitit, if the investigation below holds up.

Searching found no one implementing Solid's server function protocol outside JavaScript.
Solid with Java or Kotlin backends appears as a single-page app calling a REST API.

Protocol findings, from wire captures of EDN-encoded calls (`@solidjs/web` 2.0.3):

- A call is `POST <base>/data/<url-encoded id>` with `Content-Type: text/plain`,
  `X-Server-Function-Format: 1` (a string) and the EDN argument vector as the raw body. The
  answer is 200 with `X-Server-Function-Format: 1` and the EDN result as the raw body, so a
  JVM handler needs no seroval for ordinary calls. Format `8` (JSON) is also accepted.
- A `GET` call is `GET <base>/data/<id>?args=<JSON array holding the EDN string>`.
- A failure is a status of 400 or more. Without the format header the browser rejects with
  "Server function call failed with status N". To pass a message, answer format `0` with the
  length-framed seroval error node `;0x<length in hex, 8 digits>;{"t":13,"i":0,"s":0,"m":"<message>","p":{"k":[],"v":[]}}`
  and `X-Server-Function-Error: <message>`.
- Response headers such as `Set-Cookie` pass through, so `s/respond` maps to Ring headers.
- A live call is `GET <base>/live/<id>?args=...`, answered with `text/event-stream` and
  `X-Server-Function-Format: 0`: a constant first event declaring an async iterable (node 4),
  one event per value, `data: {"t":32,"i":4,"f":{"t":1,"s":"<EDN, JSON-escaped>"}}`, and an
  end event, `data: {"t":34,"i":4,"f":{"t":2,"s":1}}`. A `:` line is a heartbeat. Each value
  event may carry an `id:`, a digest that lets a reconnect (`Last-Event-ID`) skip an unchanged
  first value; the JVM may leave it out. So live updates for other viewers can go through the
  protocol: the JVM writes server-sent events, and the browser's `defserverfn {:live true}`
  reads them as a live source for `s/memo`.
- Not covered: server components, which need Solid's frames renderer, form posts without
  JavaScript, CSRF checks (the JVM must check `Origin` itself), and broadcast, which is a
  server-side choice with no wire difference.
- Risk: Solid's client types say "the call-to-request mapping itself is not contractual", so
  a JVM implementation may break with Solid releases. Pin Solid's version in the example and
  keep a test that runs the browser runtime against the JVM handler.

Work, in order:

Sub-step 1 is done: examples/jvm. Checked with a Playwright script in two windows, against the
uberjar and in development through Vite (sign-in, adding with the saved time, ticking, renaming
with a draft open in the other window, deleting, the viewer count rising and falling, no console
errors), plus a hot reload that kept another component's state and the REPL's `start!`,
namespace reload and `stop!`. Built against the pinned `8d259d6`. Findings:

- http-kit (2.8.1 and 2.9.0-beta4) never reported a closed streaming response: `on-close` did
  not run and `send!` kept returning true after the client left, so the viewer count never
  dropped. The example uses Jetty 12 through ring-jetty-adapter instead, with a blocking
  `StreamableResponseBody` per stream on virtual threads (`QueuedThreadPool` with a virtual
  threads executor, Java 21 or later). A write is how the server notices a closed connection,
  so a five-second heartbeat makes the count drop within about ten seconds.
- Vite's dev server reloads the whole page whenever shadow-cljs writes public/js, which loses
  component state; `server.hmr: false` leaves reloads to shadow-cljs and `:solid/refresh`.
- `(.then promise :name)` does not work: a promise's `then` ignores a keyword, which is not a
  JavaScript function, and passes the whole value through.
- sqlite-jdbc loads a native library, which Java 25 warns about. The `:server` alias passes
  `--enable-native-access=ALL-UNNAMED`, and the uberjar's manifest sets `Enable-Native-Access`.
- Development runs Vite on port 3000 with `/api` proxied to the JVM on 8080; production serves
  everything from the uberjar on 8080.
- The example has the shared list only, without the counter, the local todo list and the
  greeting of the other examples, since those show nothing about the backend.

Sub-step 2 is done, in the example: demo/server_functions.clj answers calls, `{:method :get}`,
errors and `{:live true}`, and demo/todos.cljc defines each operation once, compiled by
solid-cljs's `defserverfn` in the browser and by the example's own `defserverfn` macro on the
JVM, which registers the same `"<ns>/<name>"` id. The page uses them with
`?via=server-functions`. Test: `yarn test` in examples/jvm (test/demo/server_functions_test.clj
starts the server and runs test/protocol.mjs, Solid's own client under Node.js). The
two-window check passed for both transports, against the uberjar and in development. Findings:

- Seroval escapes the text of a string node (`"`, `\`, `<` as `\x3C`, U+2028, U+2029 and
  control characters) and unescapes it on reading, so a live value's EDN must be escaped the
  same way before it is JSON-encoded, or quotes inside strings are lost.
- The length prefix of a format 0 body (`;0x<length>;`) counts UTF-8 bytes.
- An error message outside Latin-1 goes in the header as `=?1?` and the URI-encoded text.
- Jetty 12 refuses `%2F` in a path ("Ambiguous URI path separator"), and Solid's client puts
  the id there; the server allows that one violation on its connector.
- Solid's client ignores the `base` argument of `createServerReference` for the request URL
  in Node.js; the test resolves the client's paths against the server in a `fetch` wrapper.
- The JVM must check `Origin` against `Host` itself. Vite's proxy needs `changeOrigin: false`
  so that the JVM sees the page's host.
- The live stream sends no `id:` lines, so a reconnect gets the current value again.

Open question for the user: keep the JVM side in the example, or move it to the library. A
library version would need solid-cljs's `defserverfn` to expand on the JVM (with no `:ns` in
`&env`) into a definition and a registration, and a Ring handler namespace in src/ with no
dependencies beyond Clojure (the example uses jsonista for JSON and Ring's codec). Against it:
Solid calls the wire format non-contractual, so the library would follow Solid's releases more
closely than it does now.

Sub-step 3 is done: examples/jvm/README.md, and the example lists in README.md and
examples/README.md.

1. The example with reitit: http-kit or Jetty, reitit routes for the todo API (EDN or transit
   bodies with muuntaja), next.jdbc with SQLite, the sign-in cookie, a shadow-cljs browser
   build, and live updates for other viewers through server-sent events read with `s/from`.
   Development: a REPL for the JVM and shadow-cljs watch. Production: an uberjar serving
   the release bundle.
2. A small JVM implementation of the protocol (plain, `GET`, errors, live), in the example,
   with the same todo operations as `defserverfn`s answered by the JVM. Decide after step 1
   whether it stays in the example or becomes a library.
3. README for the example, and a line in the main README's example list.

### 14. Async values holding Clojure data during server rendering — done

On the server, an async `s/memo` whose promise resolves to ClojureScript data rendered an
empty page (see step 12.5): seroval walked the vector's internals and threw
`SerovalUnsupportedTypeError`. The user asked (2026-11-21) to fix it. Done, carrying the value:

- A seroval plugin (src/solid/core.cljs, `cljs-data-plugin`) that `s/render-to-string`,
  `-async` and `-stream` (and so `solid.server/render-to-response`) always put first in their
  plugins, a new one per render. It takes collections, keywords, symbols and UUIDs and writes
  each as `_$CLJS("<type>", <contents>)`: a map as a flat array of keys and values, a vector,
  set or list as an array of items, a keyword, symbol or UUID as its string. Seroval serializes
  the contents, so strings, numbers, `js/Date` and JavaScript data inside keep their types and
  shared values stay shared. Records and sorted maps become maps, other sequences lists, and
  metadata is dropped.
- The inline scripts may run before the ClojureScript bundle loads, so a render's first value
  defines `self._$CLJS` when it is missing, as a function that queues and returns a
  placeholder object; later values call it by name (one definition per render: seroval makes
  a new context for each plugin call, so the plugin carries the state).
- `s/hydrate` builds each queued placeholder's value with `cljs.core` constructors and turns
  the placeholder into it in place (prototype and own fields), so every reference that the
  hydration data holds sees real ClojureScript data. The queue holds contents before the
  collections that hold them, so keys are real keywords before their maps are built. It then
  replaces `self._$CLJS` with the constructor for values that stream in later. A memo's value
  is used as it is, with no cost on reads.
- First tried EDN (`pr-str` and `cljs.reader/read-string`): the release bundle of the dev app,
  which never hydrates, grew from 152,911 to 178,655 bytes (5.7 kB gzipped) because
  `cljs.reader`'s tag tables are kept. The structural form keeps it at 152,911 bytes. Repeating
  the definition with every value cost about 100 bytes a value, hence once per render.
- Tests: `async-clojure-data` in test/solid/ssr_test.cljs (failed before the fix with an
  empty render), and `async-data` in solid.hydration-app, which the string and stream
  hydration tests hydrate in Chromium and check that the memo's value is a vector of maps with
  a set, a `js/Date`, a UUID, a symbol, a namespaced keyword, a list and a vector map key, plus
  the direct `self._$CLJS` path. The hydration server also
  passed when compiled with `:advanced`. All library suites pass.

### 15. Browser tests for the examples — done

Each example has test/browser/shared-todos.spec.mjs (the same file in all four) and a
playwright.config.mjs that starts its production server on an empty database in the system's
temporary folder, or tests `BASE_URL` when it is set. `@playwright/test` is pinned to 1.65.1,
the version of the repository's own devDependency, so the installed Chromium matches. The JVM
example runs the spec twice, through the reitit API and through server functions. All four
pass against their production builds (pinned `8d259d6`), and the JVM example also passed
against `yarn dev`. The spec waits for the viewer count to settle at one before it opens the
second window, since the JVM takes about ten seconds to notice a closed stream.

### 16. Server functions on the JVM in the library — done

The user asked (2026-11-21) to explore moving the JVM side of step 13.2 from examples/jvm into
the library, and to say plainly in the README that Solid may change the wire protocol under it.

Plan:

1. `defserverfn` expands on the JVM (no `:ns` in `&env`) into `defn` and a registration, so a
   .cljc file defines a server function once for both sides. The macro namespace must load
   without ClojureScript on the classpath, so it resolves `solid.template` lazily.
2. `solid.server-functions.ring`: the registry, `*request*`, `respond`, encodings (EDN and
   `{:encode :decode}`), and a Ring handler for the `/_server` paths that returns nil for other
   requests. No dependencies beyond Clojure and ring-core, which a Ring app has anyway.
3. A JVM test in the library that runs Solid's own client (Node.js) against the handler.
4. The JVM example uses the library instead of its own copy.
5. README: a section on the JVM, with the warning that Solid calls the mapping from a call to a
   request non-contractual, so a Solid release may break it until solid-cljs follows.

Done:

- src/solid/server_functions.clj loads without ClojureScript: it resolves
  `solid.template/render-target` only when it expands for ClojureScript. On the JVM (no `:ns` in
  `&env`, id from `*ns*`) `defserverfn` expands to `defn` and a call to
  `solid.server-functions.ring/-register!` through `requiring-resolve`, so the macro namespace
  does not need ring-core.
- src/solid/server_functions/ring.clj: `handler` (options `:base`, `:allow?`,
  `:heartbeat-seconds`, `:on-error`; returns nil outside its paths), `*request*`, `respond`,
  `edn`, `set-default-encoding!`, `same-origin?`, `registered`. No JSON library: it writes
  JSON itself and reads the `args` parameter with EDN, which reads JSON.stringify's array of
  strings the same way. `{:encoding :js}` throws at registration. Live functions return
  `(fn [send] stop)`.
- Tests: `clojure -M:jvm-test` (test/jvm/solid/server_functions/ring_test.clj): Ring-map tests of
  results, `respond`, custom encodings, fall-through, the 403/404/405/415 refusals and errors,
  plus Solid's own client (test/jvm/protocol.mjs) against a Jetty server. `yarn test` and
  `yarn vitest` pass with the macro change (55 and 122 tests).
- examples/jvm uses the library and has no copy of its own. solid-cljs moved to the example's
  top-level deps, since the server needs it too, and the example gained `build:local`,
  `test:local` and `dev:local` scripts. `yarn test:local` and `yarn test:browser` after
  `yarn build:local` pass.
- README: "On a Clojure server" under "Server functions", with a warning that the protocol is
  not contractual, matches `@solidjs/web` 2.0.3, and wants Solid pinned and upgraded together
  with solid-cljs. The example's README repeats it. CHANGELOG entry.

Pushed as `b0120aa`. The README and the four examples pin it, and all four build with
`yarn build` against it and pass `yarn test:browser`; the JVM example's `yarn test` passes too.
Development mode was not re-checked after the re-pin.

### 17. Instructions for agents that use the library — done

The user asked (2026-11-21) whether solid-cljs should ship instructions for AI coding agents
working in a user's project, using a widely supported mechanism, and to investigate first.
Findings, from web research done in this session (sources as of November 2026; some per-agent
details come from secondary sources and are marked preview or experimental by the vendors):

- Agent Skills (a folder with SKILL.md, an open spec at agentskills.io) is the one format that
  Claude Code, OpenAI Codex, GitHub Copilot, Cursor and Gemini CLI all read. Paths differ:
  `.claude/skills` for Claude Code, `.agents/skills` for most others, and some read both.
  A skill loads when the agent judges its description to match the task.
- No agent picks up instructions from a dependency by itself, AGENTS.md or skills alike. The
  user always takes a step: copy the folder, run `npx skills add <owner>/<repo>` (Vercel's
  skills CLI, which installs for several agents and keeps a lockfile), or add a Claude Code
  plugin marketplace from the repository (`/plugin marketplace add <owner>/<repo>`).
- AGENTS.md is read by most agents but only for the repository being worked on. It suits a
  one-line pointer in the user's own project ("this project uses solid-cljs; use the
  solid-cljs skill"), which makes the skill load more reliably.
- Package-shipped skills exist for npm (TanStack Intent, alpha) and Maven jars (SkillsJars),
  each with an install command. Nothing like it exists for deps.edn or git dependencies, so
  tools.deps users cannot get it automatically from the dependency itself.
- llms.txt is rarely fetched by agents. A docs MCP server (Context7) works with any MCP agent
  but depends on a third-party service.

Proposal: add `skills/solid-cljs/SKILL.md` (short, with a description that names `$`, `defui`,
`s/for`, `defserverfn`, Solid 2) and `skills/solid-cljs/references/*.md` for the details
(macros, reactivity rules, differences from Solid 1 and from React-style wrappers, server
functions and the JVM caveat); make the repository installable with `npx skills add` and as a
Claude Code plugin marketplace; and give the README an "Using solid-cljs with coding agents"
section with the install commands, the AGENTS.md pointer line, and the advice to install the
skill from the same commit as the pinned dependency, since a skill from another commit can
describe a different API.

The user approved the proposal (2026-11-21). Done:

- skills/solid-cljs/SKILL.md (95 lines: what solid-cljs is, a component, 14 rules, an index)
  and references/setup.md, components.md, reactivity.md and server.md, written from the README
  and checked against the macros (`s/for`'s index is derefable, `s/repeat` takes `:fallback`,
  `s/dynamic` renders without props, `s/children` returns an accessor). The frontmatter follows
  the agentskills.io spec (name matches the folder, description 465 of 1024 characters,
  license EPL-2.0). The server reference states the JVM caveat and, since step 14, how
  ClojureScript data in async values travels.
- test/vitest/skill_examples.cljs compiles and renders the skill's examples (counter, keyed and
  non-keyed lists, `s/repeat`, `s/case`, `s/if-some`, async memo under `s/loading`, action with
  `s/optimistic`, effect, `s/try`, store writes, context). It passes in Chromium.
- .claude-plugin/marketplace.json and plugin.json make the repository a Claude Code plugin
  marketplace whose one plugin is the repository root (`"source": "./"`), so `skills/` is the
  plugin's skills folder.
- README: "Coding agents", with `npx skills add viesti/solid-cljs@<sha>`, the Claude Code
  commands (`/plugin marketplace add viesti/solid-cljs#solid-2`, since the default branch is
  `main`), copying the folder, an AGENTS.md line, and installing from the pinned commit.

Verified installing through the skills CLI: `npx skills add viesti/solid-cljs@a09b5c11cb9c0d1de9715d2615e36906460e0210`
clones the pinned commit, installs `SKILL.md` and `references/*.md` into `.claude/skills/solid-cljs`,
and records the commit in `skills-lock.json`. Not verified: Claude Code's `/plugin marketplace add`
and `claude plugin validate`, since the `claude` CLI is not installed here. The JSON files parse,
and the formats follow the vendors' documentation as of November 2026.

The README and the four examples now pin `a09b5c1` (step 14: ClojureScript data in async hydration
values, which also updated `skills/solid-cljs/references/server.md`). All four build with
`yarn build` against it and pass `yarn test:browser` in both production and development mode
(`yarn dev` + `BASE_URL=http://localhost:3000 yarn test:browser`), and the JVM example's
`yarn test` passes. (On a cold dev start in `examples/shadow-cljs`, `yarn dev` hit a startup race
where `out/server.js` existed before `cljs-runtime` finished flushing; once `.shadow-cljs/builds/server/dev`
was populated, `yarn dev` started cleanly and passed `yarn test:browser`.)

### Status

Steps 1 to 17 are done (12.5 as findings with a proposal not to build the plugin encoding; step 17
verified with `npx skills add`, while Claude Code's `/plugin` commands remain unverified because
the `claude` CLI is not installed here). All previously noted test gaps from steps 4, 7, 8 and 11
are now covered, the README transit example was compiled and verified, and all four examples pass
in both production and development mode against the pinned commit `a09b5c1`.

### Deferred

Nothing.

Not planned: a Clojars release. The fork is used through git dependencies pinned to a commit
on `viesti/solid-2`.
