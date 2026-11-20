(ns solid.kondo
  "clj-kondo hooks for the solid-cljs macros whose syntax differs from clojure.core's."
  (:require [clj-kondo.hooks-api :as api]))

(defn if-form
  "Analyzes `(s/if test then else)` as `(if test then else)`."
  [{:keys [node]}]
  (let [[_ & args] (:children node)]
    {:node (api/list-node (list* (api/token-node 'if) args))}))

(defn loop-form
  "Analyzes `(s/for [binding coll & opts] body)`, and `s/index` and `s/repeat`, as a `let` of
  the binding, followed by the collection, the option values and the body."
  [{:keys [node]}]
  (let [[_ bindings & body] (:children node)
        [binding coll & opts] (:children bindings)]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node [binding coll])
                   (concat (take-nth 2 (rest opts)) body)))}))

(defn try-form
  "Analyzes `(s/try body... (catch e handler...))`, where the binding may also be
  `[e reset]`, as the body followed by a `let` that binds the catch names."
  [{:keys [node]}]
  (let [[_ & exprs] (:children node)
        catch-node (last exprs)
        [_ binding & handler] (:children catch-node)
        names (if (api/vector-node? binding) (:children binding) [binding])]
    {:node (api/list-node
            (list* (api/token-node 'do)
                   (concat (butlast exprs)
                           [(api/list-node
                             (list* (api/token-node 'let)
                                    (api/vector-node (mapcat (fn [n] [n (api/token-node nil)]) names))
                                    handler))])))}))

(defn- yield? [node]
  (and (api/list-node? node)
       (let [head (first (:children node))]
         (and (api/token-node? head)
              (= "yield" (name (api/sexpr head)))))))

(defn- action-body
  "Rewrites the forms of an action so that `(s/yield [r expr])` binds `r` in the forms after it."
  [forms]
  (if-let [i (first (keep-indexed (fn [i f] (when (yield? f) i)) forms))]
    (let [[before [y & after]] (split-at i forms)
          arg (second (:children y))]
      (concat before
              [(if (api/vector-node? arg)
                 (api/list-node (list* (api/token-node 'let) arg (action-body after)))
                 (api/list-node (list* (api/token-node 'do) arg (action-body after))))]))
    forms))

(defn action-form
  "Analyzes `(s/action [args] body...)` as a `fn` whose top-level yields bind their names."
  [{:keys [node]}]
  (let [[_ params & body] (:children node)]
    {:node (api/list-node (list* (api/token-node 'fn) params (action-body body)))}))
