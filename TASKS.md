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

### 5. Tooling — in progress

- Component names and locations: done. `$` passes `"<ns>/<name>"` and `"<ns>:<line>:<col>"` to
  `createComponent` behind `goog.DEBUG`, built with an explicit `{:tag 'boolean}` because
  syntax-quote qualifies a `^boolean` hint. Test: test/vitest/devnames.cljs.
- Custom elements: done. Templates containing a hyphenated tag clone with `importNode`
  (template flag 1), which upgrades them at once, and each custom element gets `_$owner`, as
  Solid's compiler does. Attributes stay attributes, as in Solid 2. Test:
  test/vitest/custom_elements.cljs.
- Found while checking release output: the benchmark bundle had grown to 290 kB because Closure
  aliased the `solid-js` module object in `s/signal`, which stops Vite from tree-shaking it.
  Runtime functions now bind computed arguments before calling Solid, the bundle is back to
  178 kB, and dev/bench/run.mjs fails if a module object is used other than by property access.

- Component names and locations in dev builds (`createComponent(Comp, props, name, loc)`).
- Hot reload through `solid-js/refresh`, keeping component state.
- Custom elements through templates: test properties and attributes.
- `solid.squint`: store, action and server-rendering helpers.

### Deferred

Server components (`@solidjs/web/frames`), `storage`, `performance-tracks`, trusted types,
`dispatchAsInteraction`, asset functions, and the Clojars release.
