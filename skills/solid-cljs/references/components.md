# Components, templates and control flow

All of these are in `solid.core`, required as `[solid.core :as s :refer [$ defui]]`.

## `defui` and `s/ui`

```clojure
(defui button
  "A styled button."
  [{:keys [on-click children kind] :or {kind :primary}}]
  ($ :button {:class kind :on-click on-click} children))
```

The body runs once per instance. Destructured props become prop lookups, so they stay reactive,
and `:or` defaults apply to the lookups. A destructured name that the body binds again is read
once (the compiler warns). `s/ui` makes an anonymous component with the same props handling:
`(s/ui [{:keys [label]}] ($ :span label))`.

## `$`

`($ tag attrs? & children)`. A keyword tag is an element; a symbol is a component (a `defui`
or a JavaScript component such as Solid's `Show` or `Portal`). Nested `$` forms with keyword
tags compile to one template, cloned per instance, with only the dynamic parts bound.
Children may be strings, numbers, elements, `nil`, vectors of them, and reactive expressions.
`$h` creates through Solid's runtime hyperscript instead, for tags known only at runtime;
`(s/dynamic expr)` renders, without props, the component that `expr` returns, and switches when
it changes.

## Props

- A component receives a JavaScript props object. Keyword keys become camelCase
  (`:on-click` -> `onClick`, `:active-class` -> `activeClass`), except `aria-*` and `data-*`.
- Values other than literals and `fn` forms become getters, so they stay reactive.
- `defui` reads props with kebab-case keywords: `(:on-toggle props)`.
- Children arrive as `:children`. `(s/children (:children props))` returns an accessor of the
  resolved children, for code that inspects or rearranges them.
- Values pass unchanged: a JavaScript component receives ClojureScript maps unless converted.
- `{:& props}` spreads props or attributes; keys written beside it win. `s/merge-props` and
  `s/omit-props` combine props: `($ :input {:& (s/omit-props props :label) :type :text})`.

## Attributes

- Names other than events go to the DOM as written: `:tabindex`, `:viewBox`, `:stroke-width`,
  `:for`. `:inner-html` and `:text-content` set those properties.
- Events: `:on-click`, `:on-input`, `:on-submit`, ... with a function, or `[f data]`, which
  calls `(f data event)` without a closure per element.
- `:class` takes `"a b"`, `:a`, `[:a :b "c"]` or `{:active @on? :disabled @off?}`.
- `:style` takes a map; numeric values get `px` where CSS expects a length.
- `:ref` takes `(s/ref)` (deref it after mount) or a vector of functions called with the element.
- `:prop/page-size` sets the DOM property `pageSize` instead of an attribute (custom elements).
- Custom elements (tags with a hyphen) are supported; their attributes stay attributes.

## Control flow

| Macro | Solid | Notes |
|---|---|---|
| `(s/if test then else)`, `(s/when test & body)` | `Show` | |
| `(s/if-let [x expr] then else)`, `s/when-let` | `Show` | falsy values take the else branch |
| `s/if-some`, `s/when-some` | `Show` | only `nil` takes the else branch, so `0` and `false` render |
| `(s/cond test expr ... :else expr)` | `Switch`/`Match` | |
| `(s/case value k expr ... default)` | `Switch` | the default is required |
| `(s/or a b)`, `s/some->`, `s/some->>` | | reactive versions of the core macros |
| `(s/for [[item idx] coll :key f :fallback el] body)` | `For` | keyed by identity, or by `:key` (item derefable); `idx` derefable |
| `(s/index [[item idx] coll] body)` | `For keyed={false}` | item derefable, `idx` a number |
| `(s/repeat [i n :fallback el] body)` | `Repeat` | renders `n` items without a collection |

Use the collection's value in `s/for`: `(s/for [[t] @todos] ...)` or `(s/for [[t] (todos-memo)] ...)`.

## Error and loading boundaries

```clojure
(s/try
  ($ widget {})
  (catch [err reset]                       ; or (catch err ...)
    ($ :button {:on-click #(reset)} "Retry")))

(s/loading ($ spinner)                     ; fallback while async reads in the body settle
  ($ profile {:user (user)}))

(s/loading {:fallback ($ spinner) :on @id} ; show the fallback again when @id changes
  ...)

(s/reveal {:order :together}               ; :sequential (default), :together, :natural
  (s/loading ($ spinner) ($ a))
  (s/loading ($ spinner) ($ b)))
```

After first render, a refetch keeps the old content; `(s/pending? (user))` is true meanwhile.

## Context

```clojure
(def theme (s/create-context))             ; no default: use-context throws without a provider
($ theme {:value @current-theme} ($ app))  ; the context is its own provider component
(s/use-context theme)
```

## Routing

`@solidjs/router` 2.0 works with plain `$ :a` and `$ :form` elements, which `$` lets the router
claim. `solid.router` wraps it with ClojureScript data:

```clojure
(require '[solid.router :as router])
(def app-router (router/router [{:path "/" :component home}
                                {:path "/users/:id" :component user-page}]))
;; in components: (router/use-params), (router/use-navigate), (router/use-location),
;; (router/use-search-params), router/query, router/action, router/revalidate
```

## Code splitting

`(s/lazy promise)` makes a component from a promise of a module with a default export or of a
component, such as `(lazy/load (lazy/loadable app.editor/view))` from `shadow.lazy`. Render it
under `s/loading`.

## Head tags

`(s/use-head {:tag :title :props {:children "Page"}})` adds head tags from a component; the
server renders them and the client keeps them in sync.
