# Solid 2 with Squint

A small app written in [Squint](https://github.com/squint-cljs/squint) that uses Solid 2 directly:
a counter, a todo list and an async greeting under `Loading`. Squint compiles `#jsx` literals to
JSX, and Solid's own JSX compiler compiles that JSX, so the app gets the same template cloning and
fine-grained updates as a JavaScript Solid app.

## Running it

Needs Node.js 22 and the [Clojure CLI](https://clojure.org/guides/install_clojure), which
Squint uses to resolve the git dependency in squint.edn.

```bash
cd examples/squint
yarn install
yarn dev
```

Open [localhost:3000](http://localhost:3000). `yarn build` writes a production bundle to `dist/`,
and `yarn preview` serves it. The dev server also starts Squint's nREPL server on port 1339, which
evaluates forms in the open page.

## How it is set up

- **squint.edn** compiles `src/` to `.jsx` files in `out/` and names `app.core` as the entry
  namespace, which the Squint Vite plugin adds to index.html. Its `:deps` pulls the
  `solid.squint` namespace from the solid-cljs repository at a pinned commit:
  ```clojure
  :deps {com.github.roman01la/solid-cljs
         {:git/url "https://github.com/viesti/solid-cljs"
          :git/sha "f50b0cce7130f95d9885635e169d75ad2519513b"
          :deps/root "squint"}}
  ```
  `:deps/root "squint"` selects the repository's squint directory, which holds only
  `solid.squint`, since Squint compiles every file on a dependency's paths. To work on
  `solid.squint` itself, replace the coordinate with `{:local/root "../../squint"}`.
- **vite.config.mjs** runs the Squint plugin (`squint-cljs/vite`) and Solid's plugin
  (`@solidjs/vite-plugin`). Solid's plugin compiles every `.jsx` file, including Squint's output.
- **src/app/core.cljs** has the components.
- **solid.squint** makes signals work with `deref`, `reset!` and `swap!`, wraps
  `createEffect` so that an effect callback may return any value, and wraps `createStore` so
  that a draft function's return value is ignored unless it is `(s/replacement v)`. The todo
  list keeps its items in such a store.

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

The Squint plugin re-runs a changed namespace and then calls its `^:dev/after-load` functions.
`defonce` keeps its value across reloads, so app.core keeps the disposer of the rendered app in a
`defonce` and re-renders in an after-load function, as the solid-cljs playground does.

After removing a top-level definition, restart the dev server. The plugin keeps each namespace's
analysis between compiles and otherwise still exports the removed name, which fails in the
browser with "Export ... is not defined in module".

## Compared with solid-cljs

solid-cljs runs on ClojureScript, and its `$` macro plays the part of Solid's JSX compiler: it
compiles elements to templates and components to `createComponent` calls, and wraps reactive
expressions in functions. Here, Solid's own compiler does that work, so there is no macro layer to
keep in step with Solid. The production bundle of this app is 75 kB (29 kB gzipped).

The solid-cljs source cannot be shared with Squint as it stands. Its macros are written in Clojure
(`.clj` files) for the ClojureScript compiler, and its runtime relies on ClojureScript's
persistent data structures. Squint compiles maps and vectors to plain JavaScript objects and
arrays. `solid.squint` holds the part that carries over: atom-like signals through Squint's
`IDeref`, `IReset` and `ISwap` protocols, and effects that drop non-function return values.
