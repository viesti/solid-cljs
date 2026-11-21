---
name: solid-cljs
description: Writes and fixes ClojureScript UIs with solid-cljs, the ClojureScript bindings to SolidJS 2 (solid.core with $, defui, s/signal, s/memo, s/for, s/if, s/loading, s/action; defserverfn and defservercomponent for server functions and server components). Use when code requires solid.core, solid.server-functions or solid.server-components, when the user mentions solid-cljs or Solid with ClojureScript, or when setting up shadow-cljs, Clojurific or Vite builds for it.
license: EPL-2.0
compatibility: For projects that depend on solid-cljs's solid-2 branch (Solid 2.0, @solidjs/web 2.0.3) through a git dependency.
---

# solid-cljs

solid-cljs wraps Solid 2 for ClojureScript. Its macros do the work of Solid's JSX compiler:
`$` compiles element trees to templates and wraps reactive expressions in functions, so that
Solid updates only the DOM nodes that depend on a changed value. Components run once; they do
not re-render as in React.

This skill describes the solid-2 branch at the commit it was installed from. If the project
pins another commit, the README of that commit is the authority:
`~/.gitlibs/libs/com.github.roman01la/solid-cljs/<sha>/README.md`, with `<sha>` from the
project's deps.edn.

## A component

```clojure
(ns app.core
  (:require [solid.core :as s :refer [$ defui]]))

(defui counter [{:keys [label]}]          ; destructured props stay reactive
  (let [n (s/signal 0)                    ; atom-like: @n reads, swap!/reset! write
        doubled (s/memo #(* 2 @n))]       ; a memo is a function: (doubled)
    ($ :div {:class [:counter (when (> @n 9) :big)]}
       ($ :button {:on-click #(swap! n inc)} label)
       ($ :span @n " doubled is " (doubled)))))

(defonce dispose (s/render ($ counter {:label "+"}) (js/document.getElementById "root")))
```

## Rules that matter most

1. **Inside `$` trees use solid-cljs's control flow, not clojure.core's.** `s/if`, `s/when`,
   `s/if-let`, `s/when-let`, `s/if-some`, `s/when-some`, `s/cond`, `s/case`, `s/or`,
   `s/some->`, `s/some->>`, `s/for`, `s/index`, `s/repeat`. Core `if`/`or`/`for` evaluate once
   and lose reactivity. There is no `s/and`; plain `and` inside an `s/if` or `s/when` test is
   fine, since the test is wrapped.
2. **Reads are tracked only in tracked places**: attributes and children of `$`, the function
   of `s/memo`, the compute function of `s/effect`, and the tests and bodies of the control-flow
   macros. A `@signal` in the component body (outside those) is read once. The compiler warns
   about this.
3. **Signals are derefed, memos are called.** `@sig`, `(reset! sig v)`, `(swap! sig f)`;
   `(my-memo)`. Never pass a signal where a value is meant: `($ :span @n)`, not `($ :span n)`.
4. **No writes while a component body or a computation runs** (Solid 2 throws in development).
   Write from event handlers, the apply function of `s/effect`, `s/on-settled`, or actions.
   `(s/signal v {:owned-write true})` allows it where it is intended.
5. **Writes are queued.** After `reset!`, `@sig` returns the old value until Solid flushes on
   the next microtask. Call `(s/flush)` when code (such as a test) needs the new value at once.
6. **Async data is a memo returning a promise, read under `s/loading`.** There is no
   `s/resource`. `(let [user (s/memo #(api/fetch-user @id))] (s/loading ($ spinner) ($ profile {:user (user)})))`.
   `s/pending?` shows a refetch in flight. Data the UI shows belongs in a memo, not in a signal
   that an effect fills.
7. **Mutations use `s/action`** with `s/yield` to wait for promises, and `s/optimistic` for
   values that show at once and revert when the action settles. `s/refresh` refetches a memo.
8. **`s/effect` takes two functions**: a tracked compute function and an apply function for
   side effects, which may return a cleanup: `(s/effect (fn [] @id) (fn [id] (log id)))`.
9. **Lists**: `(s/for [[item idx] @items] ...)` keys by identity, and `idx` is derefable.
   With `:key :id` the item is derefable (`@item`) and follows updates to the item with that
   key, so rows keep their state: `(s/for [[todo] @todos :key :id] ($ row {:todo todo}))`.
   `s/index` is non-keyed with a derefable item and a numeric index.
10. **Props**: `defui` destructuring keeps props reactive, but a name the body binds again
    (in `let`, `fn` params, `s/for`) is read once. Children arrive as `children`. Props going
    to JavaScript components become camelCase keys (`:on-click` -> `onClick`), and values pass
    as they are (convert maps with `clj->js` if a JS component needs objects). Spread with
    `{:& props}`.
11. **Attributes are passed as written**, except events: `:tabindex`, `:viewBox`,
    `:stroke-width`. `:class` takes a string, keyword, vector or map of conditions; `:style` a
    map. `:prop/foo` sets a DOM property. An event handler may be `[f data]`.
12. **Stores hold JavaScript data**: read with `(.-title todo)` or `(s/sget store :items 0 :title)`,
    write with `s/sset!`, `s/supdate!` or a draft function, whose return value is ignored unless
    it is `(s/replacement v)`.
13. **Render once.** Put the root render under `defonce` and set `:solid/refresh` in dev builds;
    a hot reload then keeps component state.
14. **JavaScript interop**: a promise's `.then` ignores a keyword, which is not a JS function.
    Write `(.then p #(:name %))`, not `(.then p :name)`.

## Where to read more

Read these when the task needs them:

- [references/setup.md](references/setup.md): deps.edn, npm packages, shadow-cljs with Vite,
  Clojurific, compiler options (`:solid/render`, `:solid/hydratable`, `:solid/refresh`),
  clj-kondo config.
- [references/components.md](references/components.md): `$`, `defui`, props, spreading,
  attributes, control flow, lists, `s/try`, `s/loading`, `s/reveal`, context, refs, routing,
  code splitting.
- [references/reactivity.md](references/reactivity.md): signals, memos, effects and their
  options, async data, actions and optimistic updates, stores, lifecycle, Solid 2 changes.
- [references/server.md](references/server.md): server rendering, hydration, `defserverfn`,
  server components, and server functions on a Clojure (JVM) server, with its caveat.
