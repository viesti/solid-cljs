(ns solid.server-functions
  (:require [solid.template :as tpl]))

(defmacro defserverfn
  "Defines a server function: on the server, a function that runs `body`; in the browser, a
  function that calls it over HTTP through Solid's server-functions runtime and returns a
  promise of its result. Both builds give it the id `\"<namespace>/<name>\"`.

  The browser build must not contain `body`, so server functions need the compiler option
  `:solid/render :dom` in browser builds and `:server` in server builds. The server answers
  calls with `solid.server-functions.server/handle-request`.

  `opts` may set `:method :get`, for calls that read and may be cached, and `:encoding :js`,
  which passes arguments and results to Solid's serializer as they are instead of as EDN.
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
      (throw (ex-info (str "defserverfn " fn-name " needs the compiler option :solid/render set "
                           "to :dom in browser builds and :server in server builds, so that the "
                           "function body is left out of the browser build.")
                      {:id id})))))
