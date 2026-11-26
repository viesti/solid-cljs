(ns hooks.directives
  "Leaves out a \"use server\" or \"use client\" directive that starts the body of a `defn` or
  `fn`, which Squint emits as a JavaScript directive for Solid's compiler."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private directives #{"use server" "use client"})

(defn- directive? [node]
  (and (api/string-node? node) (contains? directives (api/sexpr node))))

(defn defn-or-fn
  "Removes a directive that follows the argument vector and comes before more body forms."
  [{:keys [node]}]
  (let [[head & args] (:children node)
        [before [params & body]] (split-with (complement api/vector-node?) args)]
    (if (and params (directive? (first body)) (next body))
      {:node (assoc node :children (concat [head] before [params] (rest body)))}
      {:node node})))
