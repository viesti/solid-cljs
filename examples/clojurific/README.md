# solid-cljs demo with Clojurific

The demo app of [`../shadow-cljs`](../shadow-cljs), built with
[Clojurific](https://github.com/WhimsicalCode/clojurific) instead of shadow-cljs: a page rendered
on the server and hydrated, with a todo list in SQLite that every open window shares, and below
it a counter, a todo list kept in the browser and an async greeting. Clojurific compiles each
namespace to an ES module inside Vite, and a [Hono](https://hono.dev) app is the server.

## Running it

Needs Node.js 22.13 or later (for `node:sqlite`) and Java 17 or later. The Clojure CLI is not
needed: Clojurific resolves deps.edn itself.

```bash
cd examples/clojurific
yarn install
yarn dev
```

Open [localhost:3000](http://localhost:3000) in two windows. The todos are stored in todos.db,
which `TODOS_DB` can point elsewhere.

`yarn build` bundles the browser code into dist/client and the server into
dist/server/index.js, and `yarn start` runs it. `yarn dev:local` and `yarn build:local` build
against the solid-cljs checkout this folder lives in instead of the pinned commit.

## How it differs from the shadow-cljs example

src/demo/app.cljs, client.cljs and db.cljs are the same files as in the shadow-cljs example, and
the app shows the same features. Clojurific compiles each namespace once, for both the browser
and the server, which changes the rest:

- **No `:solid/render`.** `defserverfn` and `defservercomponent` compile both sides, and Solid's
  `isServer` chooses at runtime. Development bundles contain the server bodies. The production
  browser bundle does not, since the bundler drops the server branch.
- **demo/store.cljs** holds the database functions, which demo/server.cljs installs. demo/todos.cljs
  calls them through it instead of requiring demo/db.cljs, since the browser loads
  demo/todos.cljs too and must not import `node:sqlite`.
- **demo/server.cljs** is a Hono app, compiled as a `:js-entries` module.
  `@hono/vite-dev-server` serves it in development and hot reloads it in place, and
  `@hono/vite-build` bundles it for production.

## How it is set up

- **deps.edn** pulls solid-cljs from GitHub at a pinned commit.
- **vite.config.mjs** adds Clojurific's Vite plugin, with `demo.client` as `:main` (no HTML file
  names it, since the server renders the page) and the server entry in `:js-entries`. The Hono
  dev server passes `.cljs` requests to Vite. `vite build --mode server` builds the server.
- **cljs.edn** sets `:solid/hydratable`, and `:solid/refresh` in the `dev` profile, which the
  plugin uses while the dev server runs, so a hot reload re-renders only the components whose
  code changed and keeps the state of the others.
- **package.json** sets `"type": "module"`, which Clojurific requires.

Clojurific is experimental, and version 0.12.15 was tested here. In that version a
`^:dev/after-load` hook does not run after hot updates, which `:solid/refresh` makes unneeded.
