(ns solid.server-components
  (:require [clojure.string]
            [solid.template :as tpl]))

(defmacro defservercomponent
  "Defines a server component: a server function whose body returns a component, made with
  `s/ui` or `defui`. The browser receives the component's markup as a frame and never its code.
  Its props are slots, client components that the browser passes in and the markup places with
  `$`, such as `($ editor {:$key id :id id})`. The id is the namespace and the name joined with slashes, such
  as `\"app/todos/todo-list\"` for `app.todos/todo-list`, since Solid's frames take an id with a
  dot for something else.

  In the browser, calling it returns a promise of a component, which `server-component` mounts.
  On the server, calling it during a render renders the component into the document, where the
  browser adopts it. As with `defserverfn`, the compiler option `:solid/render :dom` leaves the
  body out of browser builds, and without the option both sides are compiled and chosen at
  runtime.

  `opts` may set `:method :get` and `:encoding`, as for `defserverfn`; the encoding applies
  to the arguments only, and must encode equal arguments the same way, since the encoded
  arguments are part of the frame's address. With `:live true` the response stays open, and the browser updates the
  markup in place as the async sources that the component reads change on the server.
  `:broadcast true` declares a live component whose markup every viewer shares, rendered once
  per set of arguments.
  ```clojure
  (defservercomponent todo-list [filter]
    (.then (db/todos filter)
           (fn [todos]
             (s/ui [{:keys [editor]}]
               ($ :ul (s/for [t todos]
                        ($ :li {:$key (:id t)} (:title t) ($ editor {:id (:id t)}))))))))
  ```"
  {:arglists '([name opts? [params*] & body])}
  [fn-name & args]
  (let [[opts [params & body]] (if (map? (first args)) [(first args) (rest args)] [{} args])
        id (str (clojure.string/replace (str (-> &env :ns :name)) "." "/") "/" fn-name)]
    (case (tpl/render-target)
      :server `(def ~fn-name (solid.server-components/-register ~id (fn ~params ~@body) ~opts))
      :dom `(def ~fn-name (solid.server-components/-reference ~id ~opts))
      `(def ~fn-name (if solid.server-components/-server?
                       (solid.server-components/-register ~id (fn ~params ~@body) ~opts)
                       (solid.server-components/-reference ~id ~opts))))))

(defmacro server-component
  "Returns a component that renders the server component that `expr` calls, such as
  `(todo-list @filter)`, with the props it is given as slot fills. When `expr` calls it again,
  with other arguments or after a change on the server, the frame is updated in place and the
  fills keep their state."
  [expr]
  `(solid.server-components/-server-component (fn [] ~expr)))
