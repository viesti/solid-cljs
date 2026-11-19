(ns solid.squint
  "Atom-like signals and effects over Solid 2's API, for Squint, which
  compiles Solid components through Solid's own JSX compiler instead of the solid-cljs
  macros. Signals support `deref`, `reset!` and `swap!` as solid-cljs signals do."
  (:require ["solid-js" :as solid]))

(defn signal
  "Creates a signal that supports `deref`, `reset!` and `swap!`. Writes are
  queued until Solid flushes, as with `createSignal`."
  ([v] (signal v nil))
  ([v opts]
   (let [[value set-value] (solid/createSignal v opts)]
     (reify
       IDeref
       (-deref [_] (value))
       IReset
       ;; The setter treats a function argument as an updater
       (-reset! [_ v] (set-value (fn [_] v)))
       ISwap
       (-swap! [_ f] (set-value (fn [old] (f old))))
       (-swap! [_ f a] (set-value (fn [old] (f old a))))
       (-swap! [_ f a b] (set-value (fn [old] (f old a b))))
       (-swap! [_ f a b xs] (set-value (fn [old] (apply f old a b xs))))))))

(defn- cleanup
  "Returns `x` if it is a function, which Solid runs as a cleanup, and
  `undefined` otherwise, since Solid's development build rejects other values."
  [x]
  (if (fn? x) x js/undefined))

(defn effect
  "Runs `apply` with the value of `compute` whenever the reads in `compute`
  change. `apply` may return a cleanup function; other return values are
  dropped."
  [compute apply]
  (solid/createEffect compute (fn [v prev] (cleanup (apply v prev)))))
