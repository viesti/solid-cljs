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

The Solid 2 port is not released to Clojars yet. Depend on it with a git dependency in deps.edn:

```clojure
{:deps {com.github.roman01la/solid-cljs
        {:git/url "https://github.com/viesti/solid-cljs"
         :git/sha "71062315e3495487075863707afb8ee95da035af"}}}
```

Then install the npm packages it requires:

```bash
npm i solid-js @solidjs/web @solidjs/h
```

shadow-cljs reads deps.edn when shadow-cljs.edn sets `:deps true`. The
[`examples`](examples) folder has the same demo app set up with shadow-cljs, with Clojurific and with Squint.

### Bundling

Solid 2's npm packages have to be bundled by a JavaScript bundler. shadow-cljs cannot bundle
`@solidjs/web` itself, because the Closure Compiler rejects the dynamic `import()` in its
hydration code.

#### shadow-cljs with Vite

shadow-cljs compiles the ClojureScript to ES modules and leaves npm imports to Vite:

```clojure
;; shadow-cljs.edn
{:builds {:app {:target :esm
                :output-dir "public/js"
                :js-options {:js-provider :import}
                :modules {:main {:entries [app.core]}}}}}
```

Load `/js/main.js` with `<script type="module">`, and run `shadow-cljs watch app` beside
`vite`. The `example` script in package.json does both. Keep Vite from watching the
`:output-dir`, so that shadow-cljs hot reloads changes instead of Vite reloading the page:

```js
// vite.config.mjs
import { defineConfig } from 'vite';

export default defineConfig({
  root: 'public',
  server: { watch: { ignored: ['**/js/**'] } },
});
```

#### Clojurific

[Clojurific](https://github.com/WhimsicalCode/clojurific) is a ClojureScript compiler fork
that runs as a Vite 8 plugin. It compiles each namespace to an ES module and lets Vite resolve
and bundle npm packages, so Solid 2 needs no extra configuration. It reads source paths and
dependencies from deps.edn instead of shadow-cljs.edn:

```clojure
;; deps.edn
{:paths ["src"]
 :deps {com.github.roman01la/solid-cljs
        {:git/url "https://github.com/viesti/solid-cljs"
         :git/sha "71062315e3495487075863707afb8ee95da035af"}}}
```

```js
// vite.config.mjs
import { defineConfig } from 'vite';
import cljf from 'clojurific/vite';

export default defineConfig({
  plugins: [cljf({ outputDir: 'target/cljs' })],
});
```

```html
<!-- index.html -->
<div id="root"></div>
<script type="module" src="/src/app/core.cljs"></script>
```

Install it with `npm i -D clojurific vite@8`. `npx vite` serves the page with hot reloading and
`npx vite build` bundles it. Clojurific is experimental (version 0.12.15 was tested here).
In that version a `^:dev/after-load` hook does not run after hot updates. Use `:solid/refresh`
instead (see Hot reloading), set in a `cljs.edn` profile for the dev server:

```clojure
;; cljs.edn, with config: 'cljs.edn' in the plugin's options
{:profiles {:dev {:solid/refresh true}}}
```

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

The examples folder has the same app built three ways. A Node.js server renders the page and the
browser hydrates it, and a todo list kept in SQLite is shared by everyone who opens the page, so
changes made in one window appear in the others. The app uses server functions, a live server
component with slots, an optimistic add and a cookie, and each example builds a production
server.

- [examples/shadow-cljs](examples/shadow-cljs) uses solid-cljs with shadow-cljs and Vite.
- [examples/clojurific](examples/clojurific) uses solid-cljs with Clojurific and a Hono server.
- [examples/squint](examples/squint) uses Squint with Solid's own compiler and Vite plugin.

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

Props destructured with a map stay reactive: `defui` replaces each use of a destructured name
with a lookup of the prop, so `label` below always shows the current value. `:or` defaults
apply to the lookups.

```clojure
(defui badge [{:keys [label tone] :or {tone :info}}]
  ($ :span {:class tone} label))
```

A name that the body binds again, in a `let`, `fn` parameters or an `s/for` binding, keeps the
inner binding, so that prop is read once, when the component is created. The compiler warns
about it. An attribute map of `{:solid/lint false}` turns the component's warnings off.

`s/ui` returns an anonymous component that takes props as `defui` does. It suits a component
that closes over local values, such as the one a server component returns.

```clojure
(s/ui [{:keys [label]}] ($ :span label))
```

`$` creates Solid element

```clojure
($ button {:on-click #(prn :pressed)} "press")
```

Elements with keyword tags compile to templates, as Solid's JSX compiler does. The macro
collects the static HTML of a tree of nested `$` forms, which Solid parses once and clones for
each instance, and generates code that binds only the dynamic attributes and children:

```clojure
($ :div {:class "card"}
   ($ :span @count)
   ($ :button {:on-click inc} "+"))
;; parses <div class="card"><span></span><button>+</button></div> once,
;; then inserts @count into the span and attaches the click handler
```

Components compile to `createComponent` calls, as in Solid's compiled JSX. Elements whose tags
are not literal keywords go through Solid's runtime hyperscript, `h`. `$h` creates any element or
component that way, which helps when comparing the two: `yarn bench` measures both in a
production build. On the server, where hyperscript cannot create DOM nodes, `$h` renders
elements as `$` does, and it does not hydrate.

### Component props

A component receives its props as a JavaScript object. Keys become camelCase JavaScript names
(`:on-click` is `onClick`, `:active-class` is `activeClass`), except `aria-*` and `data-*` names.
Values other than literals and `fn` forms become getters, which read their expression each time
the prop is read, so props stay reactive. Children arrive as the `children` prop.

A `defui` component looks props up by keyword, converting the keyword the same way, so `defui`
code keeps using kebab-case keys:

```clojure
(defui toggle [{:keys [on-toggle label]}]
  ($ :button {:on-click on-toggle} label))

($ toggle {:on-toggle #(swap! open? not) :label "Menu"})
```

Components written in JavaScript receive the props they expect. Solid's own components are
examples:

```clojure
(ns app.panel
  (:require ["solid-js" :refer [Show]]
            ["@solidjs/web" :refer [Portal]]
            [solid.core :refer [$]]))

($ Show {:when @user :fallback ($ :p "Signed out")}
   ($ :p "Signed in"))

($ Portal {:mount (js/document.getElementById "modal")}
   ($ dialog))
```

Values are passed as they are, so a keyword value stays a keyword, and a JavaScript component
receives ClojureScript maps and vectors unless the code converts them.

### Spreading props

The `:&` key of an attribute map spreads props onto a component or attributes onto an element,
like `{...props}` in JSX. The source can be a `defui` props value, a map or a JavaScript object,
and the attributes written next to `:&` win over the spread ones. Spreads stay reactive.

```clojure
(defui text-field [props]
  ($ :label (:label props)
     ($ :input {:& (s/omit-props props :label) :type :text})))

($ text-field {:label "Name" :placeholder "Ann" :on-input on-name})
```

`s/merge-props` merges props sources, the later ones winning, and `s/omit-props` leaves props
out. Both return props that a `defui` component reads with keywords:

```clojure
(defui button [props]
  (let [props (s/merge-props {:type :button} props)]
    ($ :button {:& props})))
```

On elements, spread names follow attribute rules whether they come from keywords or from
JavaScript props: `onClick` and `:on-click` are event handlers, and `strokeWidth` and
`:stroke-width` both set `stroke-width`.

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

Options follow the collection. `:fallback` renders while the collection is empty, and `:key`
keys items by a function instead of by identity. With `:key`, the item is derefable and
follows updates to the item with that key.

```clojure
(s/for [[todo] @todos :key :id :fallback ($ :li "Nothing to do")]
  ($ :li (:title @todo)))
```

Non-keyed list rendering via `<For keyed={false}>`, which replaces Solid 1's `<Index>`. The item is derefable and the index is a number.

```clojure
(def xs [1 2 3])

(s/index [[x idx] xs] ;; <- index is added implicitly
  ($ :li @x))
```

Rendering a count of items without a collection via `<Repeat>`. `:fallback` renders when the
count is zero.

```clojure
(s/repeat [i @row-count]
  ($ row {:index i}))
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

Once content has rendered, a refetch keeps it on screen. `s/pending?` is true while a change
is in flight, for showing that the content is updating. A map with `:on` makes the boundary show
its fallback again when what `:on` reads changes:

```clojure
(s/loading {:fallback ($ spinner) :on @id}
  ($ :p {:class {:stale (s/pending? (user))}} (:name (user))))
```

Coordinating when sibling `s/loading` boundaries reveal their content via `<Reveal>`.
`:order` is `:sequential` (the default), `:together` or `:natural`.

```clojure
(s/reveal {:order :together}
  (s/loading ($ spinner) ($ profile))
  (s/loading ($ spinner) ($ posts)))
```

`s/latest` reads a value including writes still in flight, `s/refresh` computes an async memo
again, `s/affects` declares that work in flight will change a memo or store, and `s/resolve`
returns a promise of the first settled value of an expression.

Mutations via Solid's `action`. An action runs in a transition and returns a promise of its
result. `(s/yield expr)` waits for a promise while the transition holds and returns its settled
value. It works in `let` bindings, `do`, `if`, `when`, `if-not`, `when-not` and the arguments of
function calls, and a body form `(s/yield [result expr])` binds the value for the forms that
follow. Solid's actions are generator functions, which ClojureScript does not have, so
`s/action` rewrites its body into the steps of one. `s/yield` inside another macro or special
form, such as `and`, `loop` or `try`, or inside a nested function, is a compile error.

```clojure
(def save-profile
  (s/action [profile]
    (let [saved (s/yield (api/save profile))]
      (when (:avatar-changed? saved)
        (s/yield (api/refresh-avatar (:id saved))))
      (s/refresh user)
      saved)))
```

`s/optimistic` creates an atom-like optimistic signal and `s/optimistic-store` an optimistic
store: a write inside an action shows at once and reverts when the action settles, by which
time the action has refreshed the source of truth.

```clojure
(let [todos (s/memo #(api/fetch-todos))
      shown (s/optimistic #(todos))
      add-todo (s/action [todo]
                 (swap! shown conj todo)
                 (s/yield (api/add-todo todo))
                 (s/refresh todos))]
  ...)
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

Signals and memos take an options map. `:equals` compares values (`=` by default for signals),
`:owned-write` allows writes while a component body or computation runs, which Solid 2 rejects
otherwise, `:unobserved` is called when nothing reads the value any more, `:lazy` makes a memo
compute on first read, and `:name` labels it in diagnostics. Effects take `:defer`, and
`:error`, a function that handles errors from the compute function, including rejected async
values:

```clojure
(s/signal 0 {:owned-write true})
(s/memo expensive {:lazy true})
(s/effect #(fetch-user @id) #(show-user %) {:error (fn [err _] (show-error err))})
```

`s/create-context` without a default value makes `s/use-context` throw when no provider is
above it, which is Solid 2's recommended form for state that a subtree owns.

Access DOM element via [ref](https://docs.solidjs.com/reference/jsx-attributes/ref)

```clojure
(let [ref (s/ref)]
  (s/on-mount (fn [] (js/console.log @ref)))
  ($ :button {:ref ref} "press"))
```

`:ref` also takes a vector of functions, each called with the element.

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

`s/on-flush` runs a function right after the pending updates apply, `s/get-owner` and
`s/run-with-owner` carry the reactive owner across async code, `s/from` reads an external
source in a memo, and `s/map-array` maps a reactive list with a function called once per item.

`s/until` returns a promise of the value of an expression once it is truthy, with an
optional `:timeout`. `s/repeat-map` maps a count to an array with a function called once per
index, and `s/create-owner`, `s/disposed?`, `s/hydrating?`, `s/on-hydration-end`,
`s/lazy-module`, `s/enable-external-source`, `s/configure-client-errors` and
`s/reset-error-halt!` wrap the rest of Solid's reactive and hydration utilities. On the server,
`s/respond` and `s/reload` shape server function responses, `s/serialize-cookie` and
`s/parse-cookie-header` handle cookies, and `s/configure-server-errors` configures error
reporting.

An event handler may be a vector of a function and data, which calls the function with the
data and the event without creating a closure per element:

```clojure
(s/for [[item] @items]
  ($ :button {:on-click [select-item (:id item)]} (:label item)))
```

### Store utilities

Create a store via `createStore` from a map or a JavaScript object. Stores hold plain
JavaScript data, so maps and vectors written into a store become objects and arrays, and reads
are tracked per property.

```clojure
(let [[todos set-todos] (s/store {:items [{:id 1 :title "Write docs" :done? false}]})]
  ($ :ul
     (s/for [[todo] (s/sget todos :items)]
       ($ :li (.-title todo)))))
```

`s/sget` reads a path of keywords and indexes. `s/sset!` and `s/supdate!` write a path through
the setter, and the setter also takes a function that mutates the draft:

```clojure
(s/sset! set-todos [:items 0 :done?] true)
(s/supdate! set-todos [:items 0 :title] str "!")
(set-todos (fn [draft] (.push (.-items draft) #js {:id 2 :title "Ship" :done? false})))
```

A draft function replaces the store's contents only when it returns `(s/replacement value)`.
Its other return values are ignored, since a ClojureScript function returns its last value,
such as the object that a `set!` assigns.

```clojure
(set-todos (fn [draft]
             (s/replacement (update (s/store->clj draft) :items #(remove :done? %)))))
```

A function instead of initial data derives the store: a function of no arguments returns its
value (which may be a promise), and a function of a draft mutates it, starting from a seed.
`s/projection` creates a read-only derived store whose items keep their identity by key:

```clojure
(def active (s/projection (fn [] (remove :done? @all-todos)) nil {:key "id"}))
```

`s/mark-raw` keeps an object out of a store's reactivity, for large values that are replaced
rather than edited. `s/reconcile!` merges new data into a draft subtree by key, `s/snapshot` and `s/store->clj` copy
a store's data, and `s/deep` reads all of it in an effect's compute function so that the effect
follows every change.

## Server rendering

`s/render-to-string` renders an element or a component to HTML on the server, in a Node.js build
that resolves the server builds of `solid-js` and `@solidjs/web` (the `node` export condition,
which Node.js uses by default):

```clojure
(s/render-to-string ($ app))
;; => "<main><h1>solid-cljs demo</h1>..."
```

Async reads that are still pending render their `s/loading` fallbacks. `s/render-to-string-async`
returns a promise of the HTML once every async read has settled, and `s/render-to-stream`
streams the HTML as the reads settle.

The `$` macro compiles each element tree twice, to a client template and to server HTML, and
Solid's `isServer` chooses between them at runtime. A build can keep only one of them with a
compiler option, which also makes its bundle a little smaller:

```clojure
;; shadow-cljs.edn
{:builds {:server {:target :node-script
                   :compiler-options {:solid/render :server}
                   ...}
          :app {:target :esm
                :compiler-options {:solid/render :dom}
                ...}}}
```

shadow-cljs does not recompile cached namespaces when this option changes, so delete
.shadow-cljs/builds after changing it.

On the server, event handlers and refs are left out, `:inner-html` becomes the element's content,
and Solid replaces the messages of errors that reach `s/try` with "Internal Server Error" unless
they are marked safe.

`s/use-head` adds tags to the document head from a component: maps of `:tag`, `:props` and an
optional `:key`. The server renders them into the head, or passes them to an `:on-head`
function when the rendered HTML has no `</head>`, and the client keeps them in sync.

```clojure
(s/use-head {:tag :title :props {:children (str "Post: " (:title post))}})
```

`s/safe-error` marks an error whose message the server may show. `s/request-event`,
`s/redirect`, `s/http-status` and `s/http-header` wrap Solid's request and response helpers,
`s/client-only` loads a component on the client only, and `s/no-hydration` and `s/hydration`
are Solid's components for regions that are not hydrated:

```clojure
($ s/no-hydration ($ static-footer))
```

`solid.server/render-to-response` renders a page for a fetch `Request` and returns a promise of
a streamed `Response`. The response takes the status and headers that the render set with
`s/http-status` and `s/http-header`, and a `Location` header makes it a redirect. `:document` is
the page around the render, as hiccup with `:solid/content` where the render goes, which
`solid.server/html` turns into HTML. `solid.server/raw` inserts HTML as it is:

```clojure
(solid.server/render-to-response
  request ($ app)
  {:plugins [sc/plugin]
   :document [:html {:lang "en"}
              [:head [:meta {:charset "UTF-8"}] [:title "App"]
               (solid.server/raw (s/hydration-script))]
              [:body [:div#root :solid/content]
               [:script {:type "module" :src "/js/main.js"}]]]})
```

### Server functions

`defserverfn` defines a function that runs on the server and that browser code calls over
HTTP, like a `"use server"` function in JavaScript. Server code calls it directly. It runs on
Solid's server-functions runtime, and returns a promise in the browser:

```clojure
(ns app.users
  (:require [solid.server-functions :refer [defserverfn]]))

(defserverfn rename-user [id new-name]
  (db/rename! id new-name)
  {:id id :name new-name})
```

The server answers calls with `solid.server-functions.server/handle-request`, for requests to
`/_server` (the default path), and server functions see the request through `s/request-event`:

```clojure
(ns app.server
  (:require [solid.server-functions.server :as sfs]))

(defn handler [^js request]
  (if (.startsWith (.-pathname (js/URL. (.-url request))) "/_server")
    (sfs/handle-request request)
    (render-page request)))
```

Arguments and results travel as EDN, so maps, keywords and sets arrive as they were sent.
`{:encoding :js}` hands them to Solid's serializer as they are instead, for files and streams,
and `{:method :get}` marks a function that only reads, so that its calls can be cached.
`solid.server-functions/configure-client` and `solid.server-functions.server/configure-server`
pass options to Solid's runtime, such as `:base` for another path.

With `:solid/render` set to `:dom` for the browser and `:server` for the server, the browser
build leaves the function bodies out. A build without the option, such as Clojurific's, which
compiles each namespace once for both, compiles both sides and chooses with Solid's `isServer`
at runtime. Its development bundles contain the bodies. In the Clojurific example the
production bundle does not, since the bundler drops the server branch. Code that both sides
load must not require server-only namespaces then, such as one that opens a database: the
Clojurific example hands its database functions to the shared code from the server entry. A
browser source map with embedded sources still contains the original code, so do not publish
such source maps for these namespaces.

### Server components

A server component is a server function whose result is a component. The browser receives the
component's markup and never its code. `defservercomponent` in `solid.server-components`
defines one, and its body returns a component made with `s/ui` or `defui`, or a promise of one:

```clojure
(ns app.todos
  (:require [solid.core :as s :refer [$]]
            [solid.server-components :as sc :refer [defservercomponent]]))

(defservercomponent todo-list [filter]
  (.then (db/todos filter)
         (fn [todos]
           (s/ui [{:keys [editor pending]}]
             ($ :ul
                (s/for [[t] todos]
                  ($ :li {:$key (:id t)} (:title t) ($ editor {:$key (:id t) :id (:id t)})))
                ($ pending))))))
```

The component's props are slots. A slot is a client component that the browser passes in, and
the server's markup places it with `$`, with props that the server sends along as data. Slot
props should be values that Solid's serializer handles, such as strings, numbers and JavaScript
objects. `:$key` on an element or a slot gives it an identity across updates, so that a slot
keeps its state when the list reorders. `sc/async-arg` marks a promise passed to a slot, which
the slot reads as its settled value.

In the browser, `sc/server-component` returns a component that renders a call of the server
component, and slots are passed to it as props:

```clojure
(s/defui editor [{:keys [id]}]
  (let [n (s/signal 0)]
    ($ :button {:on-click #(swap! n inc)} id ": " @n)))

(s/defui page []
  (let [filter (s/signal "all")
        todos (sc/server-component (todo-list @filter))]
    (s/loading ($ :p "Loading")
      ($ todos {:editor editor :pending (s/ui [] ($ :i "adding"))}))))
```

When the call changes, here when `filter` does, the browser requests the new markup and
updates the existing DOM in place. Slots with the same `:$key` keep their state.

A server component declared `{:live true}` keeps its response open, and each change on the
server updates the parts of the markup that read the changed value. Values change on the
server through async sources, such as a memo over `s/from` with a subscribe function:

```clojure
(defservercomponent headline {:live true} [room]
  (s/ui [{:keys [editor]}]
    (let [title (s/memo #(s/from (fn [next _ _] (chat/subscribe room next))))]
      ($ :section ($ :h1 (title)) ($ editor {:id room})))))
```

The browser renders it with `sc/server-component` as before, and reconnects when the connection
drops. `{:broadcast true}` declares a live component whose markup is the same for every viewer,
so viewers calling it with the same arguments share one render on the server. Create async
sources such as the memo in the component body. Markup that re-renders and creates one stops
updating and keeps the markup it last sent, as does an attribute that calls a slot.

The server calls `(sc/configure-server!)` once, which makes `handle-request` answer server
components with frame streams. A page rendered on the server renders its server components
into the document, with their slots, when the render is given `{:plugins [sc/plugin]}`. The
render needs the request event, as in a server function:

```clojure
(solid.server/provide-request-event (solid.server/create-request-event request)
  #(s/render-to-stream ($ page) {:plugins [sc/plugin]}))
```

The browser calls `(sc/install!)` once, after the document's body has loaded and before
`s/hydrate`. Hydration then adopts the server components in the document without requests.
As with server functions, browser builds set `:solid/render :dom` and server builds `:server`.
Server component ids are the namespace and the name joined with slashes, such as
`app/todos/todo-list`, because Solid's frames take an id containing a dot as something else.

### Hydration

`s/hydrate` makes server-rendered HTML interactive without rendering it again: the client adopts
the server's DOM nodes and attaches event handlers and reactive updates to them. Both the server
build and the client build need the compiler option `:solid/hydratable`, which adds hydration
keys and markers to the server HTML and compiles client code that claims those nodes:

```clojure
;; shadow-cljs.edn
{:builds {:server {:target :node-script
                   :compiler-options {:solid/render :server :solid/hydratable true}
                   ...}
          :app {:target :esm
                :compiler-options {:solid/render :dom :solid/hydratable true}
                ...}}}
```

The server puts `s/hydration-script` in the page head, which records events that happen before
hydration so that they can be replayed, and renders the app into the body:

```clojure
(str "<!doctype html><html><head>" (s/hydration-script) "</head>"
     "<body><div id=\"root\">" (s/render-to-string ($ app)) "</div>"
     "<script type=\"module\" src=\"/js/main.js\"></script></body></html>")
```

The client hydrates the same app into the same container:

```clojure
(s/hydrate ($ app) (js/document.getElementById "root"))
```

Hydratable client code also renders normally with `s/render`, so a build can compile it for
both. Streamed HTML hydrates once its content has arrived and been revealed, and `s/reveal`
coordinates the reveal of streamed `s/loading` boundaries on the server as in the browser. Without `:solid/hydratable`, `s/render` appends to a container that is not empty, so clear
the server HTML from it before rendering.

## Routing

[`@solidjs/router`](https://github.com/solidjs/solid-router) 2.0 works with plain `<a>` and
`<form>` elements: it claims them to mark the current link with `aria-current` and
`data-active` and to handle navigation. `$` lets the router claim the anchors and forms it
creates, as Solid's compiler does, on the client and on the server:

```clojure
(def router
  (createRouter #js {:routes #js [#js {:path "/" :component home}
                                   #js {:path "/about" :component about}]}))

(s/render ($ router (fn [props]
                      ($ :main
                         ($ :nav ($ :a {:href "/"} "Home") ($ :a {:href "/about"} "About"))
                         (.-children props))))
          (js/document.getElementById "root"))
```

## Code splitting

`s/lazy` creates a component from a promise of its code, which renders under `s/loading` while
it loads. The promise may resolve to a module with a default export, as from a dynamic import,
or to a component, as from shadow-cljs's `shadow.lazy/load`:

```clojure
(ns app.routes
  (:require [shadow.lazy :as lazy]
            [solid.core :as s :refer [$]]))

(def editor (s/lazy (lazy/load (lazy/loadable app.editor/view))))

(s/loading ($ spinner) ($ editor {:doc doc}))
```

## Assets, trusted types and performance tracks

`s/acquire-asset` adds a stylesheet or module to the document while a component uses it and
returns a function that releases it, and `s/warm-asset` starts loading one early:

```clojure
(let [release (s/acquire-asset {:type :style :href "/editor.css"})]
  (s/on-cleanup release))
```

On a page that requires Trusted Types, `s/set-trusted-types-policy!` makes Solid parse its
templates through the policy's `createHTML`, and `s/trusted-html` passes HTML through it.

A listener that Solid did not attach (a `click` listener on `document`, say) can run its body
with `s/dispatch-as-interaction`, so that development builds count its work in the same
interaction record as the component handlers for the same event:

```clojure
(.addEventListener js/document "click"
                   (fn [e] (s/dispatch-as-interaction e #(handle-click e))))
```

`solid.performance/enable-performance-tracks` records Solid's reactive work as tracks in the
browser's performance panel and returns a function that stops recording.

On the server, `solid.server/provide-request-event` runs a function with a request event in
scope, which `s/request-event` returns, and `solid.server/create-request-event` builds one from
a fetch `Request`.

## Hot reloading

With the compiler option `:solid/refresh` in a development build, a hot reload swaps the
components whose code changed and keeps the state of the others, as Solid's refresh does for
JSX. Render the app once, under `defonce`, instead of again on every reload:

```clojure
;; shadow-cljs.edn
{:builds {:app {...
                :dev {:compiler-options {:solid/refresh true}}}}}
```

```clojure
(defonce dispose (s/render ($ app) (js/document.getElementById "root")))
```

A component whose code changed renders again and starts with fresh local state, and the state
of its parents and siblings stays.

## Editor support

solid-cljs exports a [clj-kondo](https://github.com/clj-kondo/clj-kondo) configuration that
teaches clj-kondo the bindings of `defui`, `s/for`, `s/try`, `s/action` and the other macros.
Import it into a project once, after adding solid-cljs to its dependencies:

```bash
clj-kondo --copy-configs --dependencies --lint "$(clojure -Spath)"
```

The compiler also warns about reactivity mistakes in `defui` bodies: reactive reads outside a
tracked scope, writes to signals while the body runs, and destructured props whose names the
body binds again.

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
