# solid-cljs

[![Clojars Project](https://img.shields.io/clojars/v/com.github.roman01la/solid-cljs.svg)](https://clojars.org/com.github.roman01la/solid-cljs)

ClojureScript bindings to Solid. solid-cljs wraps Solid API documented at [docs.solidjs.com](https://docs.solidjs.com/).
Compatible with Solid 2.0

_Early alpha, unstable and highly experimental_

## How it works

Solid.js achieves high performance through fine-grained reactivity. Unlike React, Solid doesn't re-render entire components — instead, it updates only the specific DOM nodes that depend on changed values. To make this work, Solid's JSX compiler wraps reactive expressions in functions, creating precise reactive boundaries.

Since ClojureScript doesn't use JSX, solid-cljs provides macros that act as a compiler, automatically wrapping expressions to preserve Solid's fine-grained reactivity. This is why we provide Solid-specific versions of common Clojure macros like `if`, `when`, `or`, `if-let`, `if-some`, `some->`, etc.

**Why not just use Clojure's built-in macros?**

Using standard Clojure macros would break Solid's reactivity model:

```clojure
;; ❌ Using clojure.core/or - evaluates eagerly, loses reactivity
($ :h1 (or @title "Default"))

;; ✅ Using s/or - preserves reactive boundaries
($ :h1 (s/or @title "Default"))
```

The solid-cljs macros ensure that reactive expressions remain wrapped in functions, so Solid can track dependencies and update only what's necessary. This gives you Solid's performance benefits while writing idiomatic ClojureScript.

## Installation

1. `npm i solid-js @solidjs/web @solidjs/h`
2. `{:deps {com.github.roman01la/solid-cljs {:mvn/version "0.1.1"}}}`

shadow-cljs cannot bundle `@solidjs/web` itself, because the Closure Compiler rejects the
dynamic `import()` in its hydration code. Let a JavaScript bundler resolve npm packages
instead: build with `:target :esm` and `:js-options {:js-provider :import}`, then serve or
bundle the output with Vite (see shadow-cljs.edn and the `example` script in package.json).

### Upgrading from Solid 1

- Signal writes are queued. `@sig` returns the previous value until Solid flushes on the next
  microtask, or until `(s/flush)`. Consecutive `swap!` calls compose on the queued value.
- Solid 2 throws when a signal is written while a component body or a computation runs. Write
  from event handlers, effects, or `s/on-settled`.
- `s/effect` takes a tracked `compute` function and an `apply` function for the side effects.
  The single-function form still works but uses Solid's deprecated `createTrackedEffect`.
- `s/batch` runs its body and applies the queued writes before returning.
- `s/computed` and `s/resource` are removed, as in Solid 2. Return a Promise from `s/memo` and
  read it under `s/loading`.
- `s/on-mount` is an alias of the new `s/on-settled`.
- Attribute names other than event handlers are passed to the DOM as written: use
  `:tabindex`, `:stroke-width` and `:viewBox`. `:inner-html` and `:text-content` still map to
  the DOM properties.
- Root rendering takes an element or a component: `(s/render ($ app) el)` or `(s/render app el)`.

## Example

```clojure
(ns app.core
  (:require [solid.core :as s :refer [$ defui]]))

(defui app []
  (let [value (s/signal 0)]
    (s/effect
      #(println "value:" @value))
    ($ :div
      ($ :button {:on-click #(swap! value inc)} "+")
      @value
      ($ :button {:on-click #(swap! value dec)} "-"))))

(s/render ($ app) (js/document.getElementById "root"))
```

## API

Functions and macros below are a part of `solid.core` namespace:

`defui` creates Solid component. Supports docstrings and metadata.

```clojure
(defui button
  "A styled button component."
  {:private true}
  [{:keys [on-click children]}]
  ($ :button {:on-click on-click} children))
```

`$` creates Solid element

```clojure
($ button {:on-click #(prn :pressed)} "press")
```

### Attributes

The `:class` attribute supports multiple formats:

```clojure
;; String
{:class "btn primary"}

;; Keyword
{:class :btn}

;; Vector of classes
{:class [:btn :btn-primary "active"]}

;; Map for conditional classes
{:class {:active @is-active?
         :disabled @is-disabled?}}
```

### Rendering

Conditional rendering with `if` and `when`, via [<Show> component](https://docs.solidjs.com/reference/components/show)

```clojure
(s/if test ... ...)

(s/when test ...)
```

Conditional rendering with bindings via `if-let` and `when-let`:

```clojure
(s/if-let [user @current-user]
  ($ :div "Hello, " (:name user))
  ($ :div "Please log in"))

(s/when-let [user @current-user]
  ($ :div "Hello, " (:name user)))
```

Nil-checking (not falsiness) with `if-some` and `when-some`. Unlike `if-let`, these render for falsy values like `false` or `0`:

```clojure
(s/if-some [count @item-count]
  ($ :span count " items")  ;; shows "0 items" when count is 0
  ($ :span "Loading..."))

(s/when-some [count @item-count]
  ($ :span count " items"))
```

Fallback values with `or`:

```clojure
($ :h1 (s/or @custom-title "Default Title"))
```

Nil-safe threading with `some->` and `some->>`:

```clojure
;; Returns nil if any step is nil
(s/some-> @user :profile :settings :theme)

;; Thread as last argument
(s/some->> @items (filter :active) first :name)
```

Conditional rendering with `cond`, via [<Switch> and <Match> components](https://docs.solidjs.com/reference/components/switch-and-match)

```clojure
(s/cond
  (= x 1) ($ button {})
  (= y 2) ($ link {})
  :else ($ text {}))
```

List rendering via [`<For>` component](https://docs.solidjs.com/reference/components/for)

```clojure
(def xs [1 2 3])

(s/for [[x idx] xs] ;; <- index is added implicitly
  ($ :li x))
```

Non-keyed list rendering via `<For keyed={false}>`, which replaces Solid 1's `<Index>`. The item is derefable and the index is a number.

```clojure
(def xs [1 2 3])

(s/index [[x idx] xs] ;; <- index is added implicitly
  ($ :li @x))
```

Catching rendering errors via [<Errored> component](https://docs.solidjs.com/reference/solid-js/components-jsx/errored).
Bind a vector to also receive a function that retries rendering.

```clojure
(s/try
  ($ my-component {})
  (catch err
    ($ error-view {})))

(s/try
  ($ my-component {})
  (catch [err reset]
    ($ :button {:on-click #(reset)} "retry")))
```

Showing a fallback while async reads settle via [<Loading> component](https://docs.solidjs.com/reference/solid-js/components-jsx/loading)

```clojure
(let [user (s/memo #(fetch-user @id))]
  (s/loading ($ spinner)
    ($ profile {:user (user)})))
```

### Lifecycle

Run the code after initial rendering via `onSettled`.
A returned function runs as cleanup.

```clojure
(s/on-settled
  (fn []
    (let [id (js/setInterval tick 1000)]
      #(js/clearInterval id))))
```

Clean up side effects via [onCleanup](https://docs.solidjs.com/reference/lifecycle/on-cleanup)

```clojure
(s/on-cleanup (fn [] (prn :component-unmounted)))
```

### Reactive utilities

Create atom-like signal via [createSignal](https://docs.solidjs.com/reference/basic-reactivity/create-signal)

```clojure
(let [state (s/signal [])]
  ...)
```

Access DOM element via [ref](https://docs.solidjs.com/reference/jsx-attributes/ref)

```clojure
(let [ref (s/ref)]
  (s/on-mount (fn [] (js/console.log @ref)))
  ($ :button {:ref ref} "press"))
```

Execute side effects after DOM update via [createEffect](https://docs.solidjs.com/reference/solid-js/reactivity/create-effect).
The first function tracks reads, the second receives its value and may return a cleanup function.

```clojure
(s/effect (fn [] @value) (fn [v] (prn v)))
```

Execute side effects before DOM update via [createRenderEffect](https://docs.solidjs.com/reference/solid-js/advanced/specialized-reactivity/create-render-effect)

```clojure
(s/render-effect (fn [] @value) (fn [v] (prn v)))
```

Apply queued signal writes synchronously via `flush`

```clojure
(reset! value 1)
(s/flush)
@value ;; => 1

(s/batch
  (swap! value1 inc)
  (swap! value2 inc))
```

Create a reactive owner outside rendering, for example in tests, via [createRoot](https://docs.solidjs.com/reference/solid-js/advanced/owner-introspection/create-root)

```clojure
(s/root (fn [dispose] ... (dispose)))
```

Separate tracking from re-execution, via [createReaction](https://docs.solidjs.com/reference/secondary-primitives/create-reaction)

```clojure
(let [track (s/reaction (prn :update))]
  ;; run the reaction next time `s` changes
  (track (fn [] @s)))
```

Created cached computation via [createMemo](https://docs.solidjs.com/reference/basic-reactivity/create-memo)

```clojure
(s/memo (fn [] ...))
```

### Store utilities

Create data store via `createStore`. The setter receives a draft to mutate.

```clojure
(let [[store set-store] (s/store #js {:value 0})]
  (set-store (fn [draft] (set! (.-value draft) 1))))
```

## Playground

_source in [`dev/app/core.cljs`](src/app/core.cljs)_

1. Install NPM deps
2. Run local build `yarn example`, which starts the shadow-cljs watcher and Vite
3. Go to [localhost:3000](http://localhost:3000)

## Testing

Run the test suite:

```bash
yarn test
```

Or watch tests during development:

```bash
yarn test:watch
```

Run the browser tests with Vitest and Playwright (run `npx playwright install chromium` once):

```bash
yarn vitest
```
