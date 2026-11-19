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

(def ^:private replacement-key (js/Symbol "solid.squint/replacement"))

(defn replacement
  "Marks `v` as the value that a store's draft function returns to replace the draft. Other
  return values of draft functions are ignored, since Squint functions return their last
  value."
  [v]
  (let [marker (js-obj)]
    (aset marker replacement-key v)
    marker))

(defn- draft-fn [f]
  (fn [draft]
    (let [r (f draft)]
      (if (and (object? r) (js-in replacement-key r))
        (aget r replacement-key)
        js/undefined))))

(defn store
  "Creates a store from `init` and returns `[store set-store]`, like Solid's `createStore`.
  `set-store` takes a function that mutates the draft, whose return value is ignored unless it
  is `(replacement v)`, or a value that replaces the store.

  Actions need no helper in Squint: pass Solid's `action` a generator function, written with
  `^:gen` and `js-yield`."
  [init]
  (let [[st set-st] (solid/createStore init)]
    [st (fn [x] (set-st (if (fn? x) (draft-fn x) (fn [_] x))))]))
