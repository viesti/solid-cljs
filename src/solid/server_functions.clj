(ns solid.server-functions
  (:require [solid.template :as tpl]))

(defmacro defserverfn
  "Defines a server function: on the server, a function that runs `body`; in the browser, a
  function that calls it over HTTP through Solid's server-functions runtime and returns a
  promise of its result. Both builds give it the id `\"<namespace>/<name>\"`.

  With the compiler option `:solid/render :dom` the browser build leaves `body` out, and with
  `:server` the server build leaves out the browser side. Without the option, as in a build
  that compiles once for both, such as Clojurific's, both are compiled and Solid's `isServer`
  chooses at runtime, so the browser code contains `body`. The server answers calls with
  `solid.server-functions.server/handle-request`.

  `opts` may set `:method :get`, for calls that read and may be cached, `:encoding :js`,
  which passes arguments and results to Solid's serializer as they are instead of as EDN, and
  `:meta`, a map that `:prepare-request` of `configure-client` receives as `(.-meta context)`,
  with camelCase keys. `invoke` calls it with per-call options, such as an abort signal.

  With `:live true` the body returns an async iterable, such as `(s/from subscribe)`, and the
  browser's call returns an async iterable of its values, which reconnects when the connection
  drops. Each value travels as EDN. `:broadcast true` is live with one source for every caller
  with the same arguments.
  ```clojure
  (defserverfn get-user {:method :get} [id]
    (db/find-user id))
  ```"
  {:arglists '([name opts? [params*] & body])}
  [fn-name & args]
  (let [[opts [params & body]] (if (map? (first args)) [(first args) (rest args)] [{} args])
        id (str (-> &env :ns :name) "/" fn-name)]
    (case (tpl/render-target)
      :server `(def ~fn-name (solid.server-functions/-register ~id (fn ~params ~@body) ~opts))
      :dom `(def ~fn-name (solid.server-functions/-reference ~id ~opts))
      `(def ~fn-name (if solid.server-functions/-server?
                       (solid.server-functions/-register ~id (fn ~params ~@body) ~opts)
                       (solid.server-functions/-reference ~id ~opts))))))
