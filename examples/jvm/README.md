# solid-cljs with a JVM backend

The shared todo list from the other examples, with its server written in Clojure on the JVM
instead of Node.js. The page renders in the browser with `s/render`, and the JVM serves an API
with reitit, stores the todos in SQLite and streams every change to every open page as
server-sent events. Open the page in two windows and changes made in one appear in the other.

The page can make its calls two ways. By default it uses the reitit API. Opened as
[localhost:3000/?via=server-functions](http://localhost:3000/?via=server-functions), it uses
`defserverfn` instead, and the JVM answers in the wire protocol of Solid's server function
runtime, which the other examples answer from Node.js.

The other examples render the page on the server and hydrate it. This one does not, so the
page shows "Loading..." until the first value arrives.

## Running it

Needs Java 21 or later (the server runs each request on a virtual thread), the
[Clojure CLI](https://clojure.org/guides/install_clojure) and Node.js.

```bash
cd examples/jvm
yarn install
yarn dev
```

Open [localhost:3000](http://localhost:3000). `yarn dev` runs three processes: shadow-cljs
watching the browser build, the JVM server on port 8080 and Vite's dev server on port 3000,
which serves the page and passes `/api` and `/_server` to the JVM. The todos are stored in
todos.db, which `TODOS_DB` can point elsewhere, and `PORT` changes the JVM's port.

To work on the server from a REPL instead, run shadow-cljs and Vite on their own and start the
server from the REPL:

```bash
yarn shadow-cljs watch app
yarn vite
clj -M:server:dev
```

```clojure
(start!)  ; the API on port 8080
(stop!)
```

The server calls its handler through its var, so re-evaluating routes takes effect without a
restart.

`yarn build` compiles the browser build for production, bundles it with Vite into dist/ and
builds target/todos.jar, which holds the server and the bundle. `yarn start` (or
`java -jar target/todos.jar`) serves both on port 8080.

`yarn test` runs Solid's own server function client, under Node.js, against the JVM's
answers (test/protocol.mjs, started by test/demo/server_functions_test.clj).
`yarn build:local`, `yarn test:local` and `yarn dev:local` use the solid-cljs checkout this
folder lives in instead of the pinned commit, through the `:local` alias in deps.edn.

## Browser tests

test/browser/shared-todos.spec.mjs checks the shared list in two windows with Playwright:
signing in, adding a todo and its saved time, a second window signed in by the cookie, the
viewer count, an open edit keeping its draft while the other window ticks and adds todos,
renaming, deleting and console errors. It runs twice, once with the reitit API and once with
`?via=server-functions`. The JVM notices a closed window within about ten seconds, so a run
takes about half a minute.

```bash
npx playwright install chromium   # once
yarn build
yarn test:browser
```

`yarn test:browser` starts the uberjar on port 8080 with an empty database in the system's
temporary folder. To test a server that is already running, such as `yarn dev`, set
`BASE_URL`:

```bash
BASE_URL=http://localhost:3000 yarn test:browser
```

## What it shows

| In the app | How | Where |
|---|---|---|
| Adding, ticking, renaming and deleting todos write to SQLite | A reitit API, with EDN bodies through muuntaja, and next.jdbc | demo/server.clj, demo/db.clj, demo/api.cljs |
| A new todo shows at once, marked as saving, until the server answers | `s/action` with `s/optimistic` | `app` in demo/app.cljs |
| The list and the "open · done · viewing" counts update in every window | Server-sent events from the JVM, read with `s/memo` over `s/from` and the browser's `EventSource` | `events` in demo/server.clj, `subscribe` in demo/api.cljs |
| A row's edit field keeps its draft while other viewers' changes arrive | `s/for` with `:key :id`, so a row follows its todo instead of being replaced | `shared-list` and `todo-row` in demo/app.cljs |
| "Signed in as Ann", kept in a cookie, and each todo records who added it | A `Set-Cookie` from the API, read with `s/parse-cookie-header` in the browser and Ring's cookies middleware on the server | `/api/session` in demo/server.clj, demo/client.cljs |
| "Saved “Milk” at 14:02:05" and "added 14:02 · done 14:20" | The API answers with `java.util.Date` values, which EDN prints as `#inst` and `cljs.reader` reads as `js/Date` | demo/db.clj, `time-text` in demo/app.cljs |
| With `?via=server-functions`, the same operations as server functions answered by the JVM, and the list as a live server function | `defserverfn` on both sides, answered on the JVM by `solid.server-functions.ring/handler` | demo/todos.cljc, demo/server.clj |

## Server functions on the JVM

demo/todos.cljc defines each operation once for both sides with solid-cljs's `defserverfn`:

```clojure
(defserverfn add-todo [title]
  (db/add! title (author)))

(defserverfn todo-feed {:live true} []
  live/subscribe)
```

The browser build, with `:solid/render :dom`, leaves the bodies out and keeps references that
call the server. On the JVM the same macro defines each function and registers it under the
same id, `"demo.todos/add-todo"`, for `solid.server-functions.ring/handler`, which demo/server.clj
mounts at `/_server` inside the reitit router, so that the cookies middleware applies. A reader
conditional requires the database on the JVM only.

On the JVM a live function returns a subscribe function, which takes a callback and returns a
function that stops it; the browser reads the values with `(s/memo #(todo-feed))`.
`solid.server-functions.ring/respond` adds Ring response keys, such as the sign-in `:cookies`,
and `solid.server-functions.ring/*request*` is the Ring request of the call. The main README's
section "On a Clojure server" lists what the handler supports.

> [!WARNING]
> Solid documents the mapping from a call to a request as not contractual, and solid-cljs
> follows `@solidjs/web` 2.0.3. A Solid release may change the wire and break the JVM side
> until solid-cljs follows, so this example pins Solid through yarn.lock, and `yarn test`
> checks the JVM against Solid's own client.

## The API

Other clients can use the same API. Bodies are EDN, transit or JSON, chosen by `Content-Type`
and `Accept`.

| Request | Answer |
|---|---|
| `GET /api/todos` | `{:todos [...]}` |
| `POST /api/todos` with `{:title "..."}` | 201 and the new todo, by the name in the cookie |
| `PUT /api/todos/:id` with `{:title "..."}` | 204, or 404 |
| `POST /api/todos/:id/toggle` | 204, or 404 |
| `DELETE /api/todos/:id` | 204, or 404 |
| `POST /api/session` with `{:name "..."}` | `{:name "..."}` and a `name` cookie |
| `GET /api/events` | Server-sent events, each `{:todos [...] :viewers n}` as EDN |

A blank title or name answers 400 with `{:error "..."}`.

## How it is set up

- **deps.edn** depends on solid-cljs, pinned to a commit on GitHub, on both sides, and has an
  alias for each side's own libraries. `:server` holds the JVM's (Jetty through
  ring-jetty-adapter, reitit, next.jdbc, sqlite-jdbc), and `:cljs` holds shadow-cljs.
  shadow-cljs.edn names `:cljs`, so the browser build's classpath has none of the server's
  libraries.
- **shadow-cljs.edn** compiles the browser code to ES modules with `:solid/render :dom` and
  `:js-provider :import`, which leaves the npm imports to Vite, since shadow-cljs cannot bundle
  `@solidjs/web`. Development builds set `:solid/refresh`, so a hot reload re-renders only the
  components whose code changed and keeps the state of the others.
- **vite.config.mjs** serves public/ in development, passes `/api` and `/_server` to the JVM
  with the page's own `Host` header, which the JVM's origin check needs, and turns off
  Vite's own reloading, which would reload the page whenever shadow-cljs writes public/js. In
  production it bundles public/index.html and the code it loads into dist/.
- **demo/live.clj** streams events from a blocking response body, one virtual thread per open
  page, for both the API's event stream and the live server function. Each stream has a queue
  that changes are put on, and a heartbeat goes out after five quiet seconds. A write to a
  closed connection fails, which is how the server notices that a page went away, so the
  viewer count drops within about ten seconds of a window closing.
- **demo/server.clj** lets Jetty accept `%2F` in a path, which it refuses by default, since
  Solid's client puts the server function id in the path with its slash encoded.
- **build.clj** builds the uberjar with tools.build: it copies dist/ under public/ on the
  classpath, which the server's resource handler serves, and compiles the server ahead of time.
