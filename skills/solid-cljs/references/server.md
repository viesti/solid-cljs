# Server rendering, server functions and server components

## Builds

A server build is a Node.js build (`:target :node-script` or `:esm`) with
`:compiler-options {:solid/render :server}`; the browser build sets `:solid/render :dom`. Both
set `:solid/hydratable true` to hydrate. A .cljc file can require server-only namespaces with a
custom reader feature: `:reader-features #{:server}` in the server build and
`#?@(:server [[app.db :as db]])` in the file.

## Rendering on the server

```clojure
(s/render-to-string ($ app))            ; pending async reads render their s/loading fallbacks
(s/render-to-string-async ($ app))      ; promise of HTML once every read has settled
(s/render-to-stream ($ app) opts)       ; streams as reads settle

(solid.server/render-to-response
  request ($ app)
  {:plugins [sc/plugin]
   :document [:html [:head (solid.server/raw (s/hydration-script))]
              [:body [:div#root :solid/content]
               [:script {:type "module" :src "/js/main.js"}]]]})
```

`render-to-response` takes a fetch `Request` and returns a promise of a streamed `Response`;
`s/http-status`, `s/http-header` and `s/redirect` shape it. On the server, event handlers and
refs are left out, and error messages reaching `s/try` become "Internal Server Error" unless
created with `s/safe-error`. `s/request-event` returns the current request event;
`s/parse-cookie-header` and `s/serialize-cookie` handle cookies. `s/client-only` loads a
component in the browser only, and `s/no-hydration` marks a region that is not hydrated.

Async memos may resolve to ClojureScript data (collections, keywords, symbols, UUIDs, with
`js/Date` and JavaScript data inside): the render functions serialize it and `s/hydrate`
rebuilds it. Records and sorted maps arrive as maps, other sequences as lists, without
metadata.

## Hydration

```clojure
(s/hydrate ($ app) (js/document.getElementById "root"))  ; same app, same container
```

The server puts `(s/hydration-script)` in the head. Without `:solid/hydratable`, use `s/render`
and clear the server HTML first.

## Server functions

```clojure
(ns app.todos
  (:require [solid.server-functions :as sf :refer [defserverfn]]))

(defserverfn add-todo [title]            ; the browser gets a function returning a promise
  (db/add! title))

(defserverfn list-todos {:method :get} [] (db/todos))     ; cacheable reads
(defserverfn feed {:live true} []                          ; async iterable; read in a memo
  (s/from (fn [next _ _] (db/subscribe next))))
```

- The id is `"<ns>/<name>"`. With `:solid/render :dom` the browser build leaves the body out.
- Arguments and results travel as EDN (a `js/Date` arrives as `js/Date`). `{:encoding :js}`
  uses Solid's serializer (files, streams); `{:encoding {:encode f :decode g}}` or
  `sf/set-default-encoding!` (in both entries) for others such as transit.
- The server answers with `(solid.server-functions.server/handle-request request)` for paths
  under `/_server`. Return `(s/respond value {:headers {...} :status n})` for headers such as
  `Set-Cookie`.
- `{:broadcast true}` shares one live source between callers with the same arguments.
- `sf/invoke` calls with options such as an abort `:signal`; `{:meta {...}}` reaches
  `:prepare-request` of `sf/configure-client`.
- `sf/action-url` makes a form work without JavaScript; the function receives `FormData`.
- Read a live call in a memo: `(let [items (s/memo #(feed))] ...)`.

## Server functions on a Clojure (JVM) server

A .cljc file defines functions once; Clojure compiles each into a registered function, and
`solid.server-functions.ring/handler` answers the browser:

```clojure
(ns app.todos
  (:require #?@(:clj [[app.db :as db]
                      [solid.server-functions.ring :as sfr]])
            [solid.server-functions :refer [defserverfn]]))

(defserverfn add-todo [title]
  (db/add! title (get-in sfr/*request* [:cookies "name" :value])))

(defserverfn todo-feed {:live true} []
  (fn [send] (db/subscribe send)))       ; on the JVM: return (fn [send] stop)

;; Ring app, creating the handler once:
;; (def server-functions (sfr/handler))
;; (defn app [request] (or (server-functions request) (other-routes request)))
```

Supported: calls, `{:method :get}`, failures (`ex-info` sends its message), live functions as
server-sent events, EDN and `{:encode :decode}` encodings, `(sfr/respond v {:cookies ...})`.
Not supported: server components, form posts without JavaScript, `{:encoding :js}`. Needs
ring-core. Jetty 12 must be configured to accept `%2F` in paths (`UriCompliance` with
`AMBIGUOUS_PATH_SEPARATOR`). A proxy in front must keep the page's `Host`, or pass `:allow?`.
Live streams block a thread each, so use virtual threads.

**Caveat to tell the user:** this speaks Solid's server function wire protocol, which Solid
documents as not contractual. It matches `@solidjs/web` 2.0.3. A Solid upgrade may break the
JVM side until solid-cljs follows, so pin `@solidjs/web` exactly and upgrade it together with
solid-cljs.

## Server components

```clojure
(ns app.todos
  (:require [solid.core :as s :refer [$]]
            [solid.server-components :as sc :refer [defservercomponent]]))

(defservercomponent todo-list {:broadcast true} []
  (s/ui [{:keys [check]}]                                  ; props are slots
    (let [todos (s/memo #(s/from db/subscribe))]
      ($ :ul (s/for [[t] (todos)]
               ($ :li {:$key (str (:id t))}
                  ($ check {:$key (str (:id t)) :id (:id t)}) (:title t)))))))

;; browser
(let [todos (sc/server-component (todo-list))]
  (s/loading ($ :p "Loading") ($ todos {:check check})))
```

The browser receives markup, never the component's code. Slots are client components placed
by the server markup; `:$key` keeps a slot's state across updates. `{:live true}` keeps the
response open and updates the parts that read changed async sources; create those sources in
the component body. The server calls `(sc/configure-server!)` and renders with
`{:plugins [sc/plugin]}`; the browser calls `(sc/install!)` once before `s/hydrate`.
Server component ids join the namespace and name with slashes (`app/todos/todo-list`).
