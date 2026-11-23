# Solid 2 with Squint

An app written in [Squint](https://github.com/squint-cljs/squint) that uses Solid 2 directly. The
server renders the page and the browser hydrates it, and a todo list kept in SQLite is shared by
everyone who opens the page: open it in two windows and changes made in one appear in the other.
Below the shared list are a counter, a todo list kept in the browser and an async greeting under
`Loading`. Squint compiles `#jsx` literals to JSX, and Solid's own compiler compiles that JSX and
the `"use server"` functions, so the app is set up as a JavaScript Solid app would be.

https://github.com/user-attachments/assets/2eb8293c-bbc5-4018-87d2-2ef680487879

## Running it

Needs Node.js 22.13 or later (for `node:sqlite`) and the
[Clojure CLI](https://clojure.org/guides/install_clojure), which Squint uses to resolve the git
dependency in squint.edn.

```bash
cd examples/squint
yarn install
yarn dev
```

Open [localhost:3000](http://localhost:3000). The todos are stored in todos.db, which `TODOS_DB`
can point elsewhere. `yarn build` writes the browser bundle to dist/client and a Node.js server to
dist/server, and `yarn start` runs it. The dev server also starts Squint's nREPL server on port
1339, which evaluates forms in the open page.

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

## How it is set up

- **squint.edn** compiles `src/` to `.jsx` files in `out/`. Its `:deps` pulls the `solid.squint`
  namespace from the solid-cljs repository at a pinned commit:
  ```clojure
  :deps {com.github.roman01la/solid-cljs
         {:git/url "https://github.com/viesti/solid-cljs"
          :git/sha "aea9e4558a46a1a36fa2a766ccb7bd5dcd397662"
          :deps/root "squint"}}
  ```
  `:deps/root "squint"` selects the repository's squint directory, which holds only
  `solid.squint`, since Squint compiles every file on a dependency's paths. To work on
  `solid.squint` itself, replace the coordinate with `{:local/root "../../squint"}`.
- **vite.config.mjs** runs the Squint plugin (`squint-cljs/vite`) and Solid's plugin
  (`@solidjs/vite-plugin`) in its start mode with `ssr: true`. The Solid plugin renders the page on
  the server in development, compiles `"use server"` functions (`serverFunctions`, with
  `components` for server components and a filter that includes `out/`), and builds a Node.js
  server (`start.nodeServer`). The `dev` and `build` scripts run `squint compile` first, since the
  Solid plugin checks that the app module exists when it starts.
- **src/app/app.cljs** has the components and default-exports the root component, and
  **src/app/document.cljs** default-exports the document around it.
- **src/app/style.css** holds the styles. document.cljs imports it with a static `import`
  written with `js*`, since in development Squint turns a `:require` into a dynamic import,
  which Solid's plugin does not follow. The plugin then puts the styles in the server-rendered
  head (inlined in development, linked from the bundle in production), and Vite updates them in
  place when the file changes.
- **src/app/todos.cljs** has the server functions and the shared list, written as
  `"use server"` functions. A string as the first form of a Squint function body becomes a
  directive, so `(defn ^:async add-todo [title] "use server" ...)` is a server function.
  `SharedList` is a server component wrapped in Solid's `broadcast`, which renders it once for
  every viewer and updates it as the todos change. The functions import **src/app/db.cljs**
  inside their bodies, which Solid's compiler leaves out of the browser build, so the browser
  never loads `node:sqlite`.
- **solid.squint** makes signals work with `deref`, `reset!` and `swap!`, wraps
  `createEffect` so that an effect callback may return any value, and wraps `createStore` so
  that a draft function's return value is ignored unless it is `(s/replacement v)`. The todo
  list in the browser keeps its items in such a store.

The shared list's render is shared by every viewer, so Solid's compiler refuses a render that
reads a binding of the server function's body. It reads the database through a module-level
subscribe function instead.

Solid's `action` takes a generator function, which Squint writes with `^:gen` and `js-yield`,
so actions need no helper:

```clojure
(def save (action (^:gen fn [todo]
                    (js-yield (api/save todo))
                    (refresh todos))))
```

Squint turns this component

```clojure
(defn TodoItem [props]
  #jsx [:li
        [:span {:class {:done (.-done props)}} (.-title props)]
        [:button {:onClick (.-onRemove props)} "x"]])
```

into this JSX, which Solid's compiler then compiles like any other `.jsx` file:

```jsx
var TodoItem = function (props) {
return <li><span class={({"done": props.done})}>{props.title}</span><button onClick={props.onRemove}>x</button></li>;
};
```

## Writing components

- Write component names in PascalCase. JSX treats a lowercase tag as an HTML element.
- Read props with `(.-title props)` where the value is used. Destructuring `props` in the
  argument list reads each prop once, and the component stops following changes.
- Use JSX attribute names as Solid spells them: `:onClick`, `:onInput`, `:class`. Squint does not
  rename keys, unlike solid-cljs's `$` macro.
- A Clojure expression inside `#jsx` leaves the JSX context. Start nested markup inside it with
  `#jsx` again, as in the `fallback` of `Show` and the item function of `For`.
- Squint functions return their last value, like ClojureScript functions. Solid's development
  build halts reactivity when an effect callback returns anything other than a function or
  `undefined`, so use `effect` from `solid.squint`, or end the callback with `js/undefined`.
- Signal writes become visible after Solid flushes its queue, on the next microtask.

## Hot reloading

Solid's plugin hot reloads changed components through its refresh transform, keeping the state of
the components around them, so the app needs no `^:dev/after-load` function.

After removing a top-level definition, restart the dev server. The plugin keeps each namespace's
analysis between compiles and otherwise still exports the removed name, which fails in the
browser with "Export ... is not defined in module".

## Compared with solid-cljs

solid-cljs runs on ClojureScript, and its `$` macro plays the part of Solid's JSX compiler: it
compiles elements to templates and components to `createComponent` calls, and wraps reactive
expressions in functions. Here, Solid's own compiler does that work, so there is no macro layer to
keep in step with Solid.

The solid-cljs source cannot be shared with Squint as it stands. Its macros are written in Clojure
(`.clj` files) for the ClojureScript compiler, and its runtime relies on ClojureScript's
persistent data structures. Squint compiles maps and vectors to plain JavaScript objects and
arrays. `solid.squint` holds the part that carries over: atom-like signals through Squint's
`IDeref`, `IReset` and `ISwap` protocols, and effects that drop non-function return values.
