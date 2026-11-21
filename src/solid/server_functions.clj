(ns solid.server-functions
  "The `defserverfn` macro. This namespace loads on a plain JVM too, without ClojureScript, so
  that a .cljc file can define its server functions for the browser and a Clojure server at
  once: it reads the ClojureScript compiler's options only when it expands for ClojureScript.")

(defn- render-target []
  ((requiring-resolve 'solid.template/render-target)))

(defmacro defserverfn
  "Defines a server function: on the server, a function that runs `body`; in the browser, a
  function that calls it over HTTP through Solid's server-functions runtime and returns a
  promise of its result. Both builds give it the id `\"<namespace>/<name>\"`.

  With the compiler option `:solid/render :dom` the browser build leaves `body` out, and with
  `:server` the server build leaves out the browser side. Without the option, as in a build
  that compiles once for both, such as Clojurific's, both are compiled and Solid's `isServer`
  chooses at runtime, so the browser code contains `body`. The server answers calls with
  `solid.server-functions.server/handle-request`.

  `opts` may set `:method :get`, for calls that read and may be cached, `:encoding`, and
  `:meta`, a map that `:prepare-request` of `configure-client` receives as `(.-meta context)`,
  with camelCase keys. `invoke` calls it with per-call options, such as an abort signal.

  Arguments and results travel as EDN unless `:encoding` names another encoding: a map of
  `:encode`, which turns the argument vector or a result into a value for Solid's serializer,
  such as a string, and `:decode`, which reverses it. `solid.server-functions/set-default-encoding!`
  sets the one used when `:encoding` is left out. `:encoding :js` passes arguments and results
  to Solid's serializer as they are.

  With `:live true` the body returns an async iterable, such as `(s/from subscribe)`, and the
  browser's call returns an async iterable of its values, which reconnects when the connection
  drops. Each value is encoded on its own. `:broadcast true` is live with one source for every caller
  with the same arguments.

  In Clojure on the JVM it defines a function with `defn` and registers it under the same id
  for `solid.server-functions.ring/handler`, which answers the browser in the wire protocol of
  Solid's server function runtime. A live function's body returns a subscribe function there,
  `(fn [send] stop)`. See that namespace for what it supports.
  ```clojure
  (defserverfn get-user {:method :get} [id]
    (db/find-user id))
  ```"
  {:arglists '([name opts? [params*] & body])}
  [fn-name & args]
  (let [[opts [params & body]] (if (map? (first args)) [(first args) (rest args)] [{} args])
        id (str (if (:ns &env) (-> &env :ns :name) (ns-name *ns*)) "/" fn-name)]
    (cond
      ;; Clojure on the JVM: a function, registered for solid.server-functions.ring/handler,
      ;; which loads only when a server function is defined, since it needs ring-core
      (not (:ns &env))
      `(do (defn ~fn-name ~params ~@body)
           ((requiring-resolve 'solid.server-functions.ring/-register!) ~id (var ~fn-name) ~opts)
           (var ~fn-name))

      :else
      (case (render-target)
        :server `(def ~fn-name (solid.server-functions/-register ~id (fn ~params ~@body) ~opts))
        :dom `(def ~fn-name (solid.server-functions/-reference ~id ~opts))
        `(def ~fn-name (if solid.server-functions/-server?
                         (solid.server-functions/-register ~id (fn ~params ~@body) ~opts)
                         (solid.server-functions/-reference ~id ~opts)))))))
