# Changelog

## Unreleased: Solid 2

solid-cljs now targets Solid 2.0. See "Upgrading from Solid 1" in the README for the changes an
app needs.

### Changed

- Depends on `solid-js`, `@solidjs/web` and `@solidjs/h` 2.x. npm packages must be bundled by a
  JavaScript bundler such as Vite, since shadow-cljs cannot bundle `@solidjs/web`.
- Signal writes are queued until Solid flushes. `s/flush` applies them at once.
- `s/effect` takes a compute and an apply function. Effect callbacks may return any value.
- Elements compile to templates and components to `createComponent` calls, as Solid's JSX
  compiler does. Element creation is about six times faster than with hyperscript.
- Component props are a JavaScript object with camelCase names, so JavaScript components
  receive the props they expect. `defui` keeps keyword lookups and destructuring, and
  destructured props stay reactive.
- Attribute names other than events are passed to the DOM as written (`:tabindex`,
  `:stroke-width`), and numeric styles get `px` by CSS property.
- Directives always receive an accessor.
- Keys in the `prop` namespace, such as `:prop/rows`, set DOM properties, as Solid's `prop:` does.
- `s/for`, `s/index` and `s/repeat` inside `$` are created in place, as Solid's compiler creates
  `<For>`, so a live server component in a hydratable build updates them.
- A `defui` component creates its element when it is called, as a component compiled from JSX
  does, so components that Solid's runtime calls directly, such as slot fills, render in place.

### Added

- Server rendering with `s/render-to-string`, `s/render-to-string-async` and
  `s/render-to-stream`, and hydration with `s/hydrate`, `s/hydration-script` and the compiler
  options `:solid/render` and `:solid/hydratable`.
- Async: `s/loading` options, `s/pending?`, `s/latest`, `s/refresh`, `s/affects`, `s/resolve`.
- Actions and optimistic updates: `s/action` with `s/yield` (in let bindings, branches and
  call arguments), `s/optimistic`, `s/optimistic-store`.
- Stores: maps as initial data, `s/sget`, `s/sset!`, `s/supdate!`, `s/replacement`,
  `s/projection`, `s/reconcile!`, `s/snapshot`, `s/store->clj`, `s/deep`.
- Control flow: `:fallback` and `:key` for `s/for`, `s/repeat`, `s/reveal`.
- Props: spreading with `:&`, `s/merge-props`, `s/omit-props`.
- `:ref` vectors, `s/on-settled`, `s/on-flush`, `s/root`, `s/get-owner`, `s/run-with-owner`,
  `s/from`, `s/map-array`, `s/use-head`, `s/safe-error`, `s/client-only`, `s/no-hydration`,
  `s/hydration` and the request helpers.
- `s/lazy` accepts a promise of a component, as from `shadow.lazy/load`.
- `$h` renders on the server.
- `solid.squint`, helpers for Squint apps that use Solid's own JSX compiler.
- A clj-kondo configuration, and compiler warnings for writes in component bodies and for
  destructured props that are read once.
- Example projects for shadow-cljs, Clojurific and Squint, and `yarn bench`.

- Routing: anchors and forms that `$` creates are claimed by `@solidjs/router` 2.0, on the
  client and the server.
- Options for signals, memos and effects (`:owned-write`, `:unobserved`, `:lazy`, `:error`),
  and contexts without a default.
- `solid.server/render-to-response`, which renders a page to a `Response` with the status and
  headers the render set, and `solid.server/html`, which turns hiccup into HTML for the page
  around it.
- Server functions with `defserverfn` and `solid.server-functions.server/handle-request`.
  Without `:solid/render`, as in Clojurific, `defserverfn` and `defservercomponent` compile
  both sides and choose with Solid's `isServer` at runtime.
- Live server functions with `{:live true}` or `{:broadcast true}` on `defserverfn`.
- `defserverfn` takes `:meta`, and `solid.server-functions/invoke` calls a server function with
  per-call options such as an abort signal. Solid's own `invoke` and `withMeta` work on it too.
- A `defserverfn` may return `s/respond`, whose status and headers reach the browser while
  its value travels as EDN. `s/respond` and `s/reload` take `:headers` as a map.
- `mark-raw`, `until`, `repeat-map`, owner and hydration utilities, cookie helpers, and
  `[handler data]` event handlers.
- Component names and source locations in Solid's diagnostics in development builds.
- Custom elements are created as Solid's compiler creates them.
- Hot reloading that keeps component state, with `:solid/refresh`.
- `solid.squint/store`.
- `s/dispatch-as-interaction` for listeners that Solid did not attach.
- Server components with `solid.server-components/defservercomponent`, slots, `:$key` on
  elements, and `s/ui` for anonymous components. Render functions take `:plugins`.
- Live server components, with `{:live true}` or `{:broadcast true}` on `defservercomponent`.

### Removed

- `s/computed` and `s/resource`, as in Solid 2. An async `s/memo` under `s/loading` replaces
  resources.
