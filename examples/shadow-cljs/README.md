# solid-cljs demo with shadow-cljs

An app that uses solid-cljs with Solid 2 as a git dependency. A Node.js server renders the page,
the browser hydrates it, and a todo list kept in SQLite is shared by everyone who opens the page.
Open it in two windows and changes made in one appear in the other. Below the shared list are a
counter, a todo list kept in the browser and an async greeting under `s/loading`.

## Running it

Needs Node.js 22.13 or later (for `node:sqlite`), Java 17 or later and the
[Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
cd examples/shadow-cljs
yarn install
yarn dev
```

Open [localhost:3000](http://localhost:3000). `yarn dev` compiles the browser and the server
builds, then starts the server once its build exists. The todos are stored in todos.db, which
`TODOS_DB` can point elsewhere, and `PORT` changes the port.

`yarn build` compiles both builds for production and bundles the browser code into dist/, and
`yarn start` runs the server in production mode, serving dist/ without Vite.

`yarn dev:local` builds against the solid-cljs checkout this folder lives in instead of the pinned
commit, through the `:local` alias in deps.edn.

## Browser tests

test/browser/shared-todos.spec.mjs checks the shared list in two windows with Playwright:
signing in, adding a todo and its saved time, a second window signed in by the cookie, the
viewer count, an open edit keeping its draft while the other window ticks and adds todos,
renaming, deleting and console errors.

```bash
npx playwright install chromium   # once
yarn build
yarn test:browser
```

`yarn test:browser` starts the production server on port 3000 with an empty database in the system's
temporary folder. To test a server that is already running, such as `yarn dev`, set
`BASE_URL`:

```bash
BASE_URL=http://localhost:3000 yarn test:browser
```

## What it shows

| In the app | solid-cljs feature | Where |
|---|---|---|
| The page arrives rendered and becomes interactive without rendering again | `s/render-to-stream` on the server, `s/hydrate` in the browser | demo/server.cljs, demo/client.cljs |
| Adding, ticking, renaming and deleting todos write to SQLite | `defserverfn` | demo/todos.cljc |
| A new todo shows at once, marked as saving, until the server's list has it | `s/action` with `s/optimistic` | `shared-todos` in demo/app.cljs |
| The list and the "open · done · viewing" counts update in every window | A `defservercomponent` with `{:broadcast true}`, reading the database's change feed with `s/memo` over `s/from` | `shared-list` in demo/todos.cljc, demo/db.cljs |
| A row's checkbox and edit field are client components, and an open edit keeps its draft while other changes arrive | Slots with `:$key` | `check` and `actions` in demo/app.cljs |
| "Signed in as Ann", kept in a cookie, and each todo records who added it | `s/respond` with `s/serialize-cookie`, `s/request-event` and `s/parse-cookie-header` | `sign-in` and `author` in demo/todos.cljc |

## How it is set up

- **deps.edn** pulls solid-cljs from GitHub at a pinned commit. shadow-cljs reads it because
  shadow-cljs.edn sets `:deps true`.
- **shadow-cljs.edn** has two builds. `:app` compiles the browser code to ES modules with
  `:solid/render :dom`, which leaves server function and server component bodies out, and
  `:js-provider :import`, which leaves the npm imports to Vite, since shadow-cljs cannot bundle
  `@solidjs/web`. `:server` compiles the server for Node.js with `:solid/render :server`. Both
  set `:solid/hydratable`.
- **demo/todos.cljc** is compiled by both builds. The server build sets
  `:reader-features #{:server}`, so `#?@(:server [...])` requires the database there only.
- **demo/server.cljs** answers `/_server` with `handle-request` (server functions and live
  streams), renders the page for `/`, and serves the browser code through Vite in middleware mode
  in development, or from dist/ in production.
- **public/css/style.css** holds the styles. In development shadow-cljs reloads the linked
  stylesheet in the page when the file changes (`:devtools {:watch-dir "public"}`), and the
  production build emits it as `dist/assets/style.css`.
- **vite.config.mjs** pre-bundles the Solid packages together, so that the page loads one copy of
  `solid-js`, and builds `public/js/main.js` into `dist/assets/app.js`.
- The browser build sets `:solid/refresh` in development, so a hot reload re-renders only the
  components whose code changed and keeps the state of the others. Server code hot reloads too:
  the server calls its request handler through its var.
