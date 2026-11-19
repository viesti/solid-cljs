(ns solid.core
    (:refer-clojure :exclude [flush])
    (:require-macros [solid.core])
    (:require ["solid-js" :as solid]
              ["@solidjs/web" :as sw]
              ["@solidjs/h" :default h]
              [solid.compiler :as sc]))

;; Marker type for reactive prop values
;; This distinguishes reactive getters from regular callbacks
(deftype ReactiveProp [getter])

(defn reactive-prop
      "Wraps a function as a reactive prop getter.
  Used internally by the $ macro for component props."
      [f]
      (->ReactiveProp f))

(defn reactive-prop?
      "Returns true if x is a reactive prop wrapper."
      [x]
      (instance? ReactiveProp x))

(defn element?
      "Returns true if x is an element created with `$`."
      [x]
      (and (fn? x) (true? (.-solid-cljs-element ^js x))))

(defn create-element [tag & args]
      (when (string? tag) (sc/with-numeric-props (first args)))
      (let [el (apply h tag args)]
           (set! (.-solid-cljs-element ^js el) true)
           el))

(defn -wrap [value]
      (reify
       IDeref
       (-deref [this]
               (value))))

(defn signal
      "Creates an atom-like signal. Reads with `deref` are tracked.

  Writes with `reset!` and `swap!` are queued: `deref` keeps returning the
  previous value until Solid flushes its queue on the next microtask, or until
  `flush` is called. Consecutive `swap!` calls compose on the queued value."
      ([v]
       (signal v =))
      ([v {:keys [equals]}]
       (let [[value set-value] (solid/createSignal v #js {:equals equals})]
            (reify
             IDeref
             (-deref [this]
                     (value))
             IReset
             (-reset! [this v]
         ;; Setter treats a function argument as an updater
                      (set-value (fn [_] v)))
             ISwap
             (-swap! [this f]
                     (set-value (fn [old] (f old))))
             (-swap! [this f a]
                     (set-value (fn [old] (f old a))))
             (-swap! [this f a b]
                     (set-value (fn [old] (f old a b))))
             (-swap! [this f a b xs]
                     (set-value (fn [old] (apply f old a b xs))))))))

(defn ref []
      (let [value (volatile! nil)
            f #(vreset! value %)]
           (specify! f
                     IDeref
                     (-deref [this]
                             @value))))

(defn flush
      "Applies queued signal writes synchronously. With `f`, runs `f` and then
  drains the queue before returning the result of `f`."
      ([]
       (solid/flush))
      ([f]
       (solid/flush f)))

(defn root
      "Runs `f` with a new reactive owner and returns its result. `f` receives a
  function that disposes the owner."
      [f]
      (solid/createRoot f))

(defn- cleanup
      "Returns `x` if it is a function, which Solid then runs as a cleanup.
  Other return values of ClojureScript callbacks become `undefined`, since
  Solid's development build rejects any other value, `null` included."
      [x]
      (if (fn? x) x js/undefined))

(defn- with-cleanup [f]
      (fn [v prev] (cleanup (f v prev))))

(defn effect
      "Creates an effect.

  With `compute` and `apply`, `compute` tracks its reads and returns a value,
  and `apply` receives that value and performs the side effects. `apply` may
  return a cleanup function. `opts` accepts `:defer` to skip the initial run.

  With a single function, the function both tracks and performs side effects.
  This form uses Solid's deprecated `createTrackedEffect` and may run more than
  once per change."
      ([f]
       (solid/createTrackedEffect #(cleanup (f))))
      ([compute apply]
       (solid/createEffect compute (with-cleanup apply)))
      ([compute apply {:keys [defer]}]
       (solid/createEffect compute (with-cleanup apply) #js {:defer defer})))

(defn render-effect
      "Creates an effect that runs during rendering. Takes the same `compute` and
  `apply` functions as `effect`. With a single function, the function runs in
  the tracked compute phase."
      ([f]
       (solid/createRenderEffect f (fn [_])))
      ([compute apply]
       (solid/createRenderEffect compute (with-cleanup apply)))
      ([compute apply {:keys [defer]}]
       (solid/createRenderEffect compute (with-cleanup apply) #js {:defer defer})))

(defn on-settled
      "Runs `f` once the current reactive activity settles. `f` may return a
  cleanup function that runs when the owner is disposed."
      [f]
      (solid/onSettled #(cleanup (f))))

(defn on-mount
      "Runs `f` after the component's first render settles. Same as `on-settled`."
      [f]
      (solid/onSettled (fn [] (f) js/undefined)))

(defn on-cleanup [f]
      (solid/onCleanup f))

(defn memo
      ([f]
       (solid/createMemo f))
      ([f {:keys [equals]}]
       (solid/createMemo f #js {:equals equals})))

(defn -show [test then else]
      (h solid/Show
         #js {:when test
              :fallback else}
         then))

(defn -show-keyed [test then else]
      (h solid/Show
         #js {:when test
              :keyed true
              :fallback else}
         then))

(defn -for [coll body]
      (h solid/For
         #js {:each coll}
         body))

(defn -index [coll body]
      (h solid/For
         #js {:each coll
              :keyed false}
         body))

(defn -switch [else pairs]
      (apply h solid/Switch
             #js {:fallback else}
             (for [[test then] pairs]
                  (h solid/Match
                     #js {:when test}
                     then))))

(defn -dynamic [f]
      (h (sw/dynamic f)))

(defn -children [f]
      (solid/children f))

(deftype ReactiveProps [props-map]
         ILookup
         (-lookup [this k]
                  (-lookup this k nil))
         (-lookup [this k not-found]
                  (let [v (get props-map k not-found)]
                       (if (reactive-prop? v)
                           ((.-getter ^ReactiveProp v))  ; Call the reactive getter
                           v)))            ; Pass through as-is (callbacks, literals, etc.)

         ISeqable
         (-seq [this]
               (seq props-map))

         ICounted
         (-count [this]
                 (count props-map)))

(defn -props [^js props]
      (when props
            (let [clj-props (.-props props)
                  children (-children #(.-children props))]
                 (->ReactiveProps
                  (cond-> clj-props
                          children (assoc :children children))))))

(defn -error-boundary [fallback & children]
      (apply h solid/Errored
             #js {:fallback fallback}
             children))

(defn -loading [fallback & children]
      (apply h solid/Loading
             #js {:fallback fallback}
             children))

(defn -lazy [f]
      (solid/lazy f))

(defn -reaction [f]
      (solid/createReaction (with-cleanup f)))

(defn store [v]
      (solid/createStore v))

(defn external-store [subscribe get-snapshot]
      (let [s (signal (get-snapshot))
            unsub (subscribe (fn [] (reset! s (get-snapshot))))]
           (on-cleanup unsub)
           s))

(defn atom-signal [ref]
      (external-store (fn [f]
                          (let [key (+ (Math/random) (.now js/Date))]
                               (add-watch ref key (fn [_ _ o n]
                                                      (when (not= o n)
                                                            (f))))
                               #(remove-watch ref key)))
                      #(deref ref)))

(defn deferred
      ([f]
       (deferred f nil))
      ([f {:keys [equals]}]
       (solid/createDeferred f #js {:equals equals})))

(def uid solid/createUniqueId)

(def portal sw/Portal)

(def error-boundary solid/Errored)

(defn untrack [f]
      (solid/untrack f))

(defn create-context [value]
      (solid/createContext value))

(defn use-context [context]
      (solid/useContext context))

(defn render
      "Renders `root` into the DOM `node` and returns a function that disposes it.
  `root` is either an element created with `$` or a component."
      [root node]
      (sw/render (if (element? root) root (h root)) node))

(defn render-to-string [el {:keys [nonce render-id]}]
      (sw/renderToString (fn [] el) #js {:nonce nonce :renderId render-id}))

(defn render-to-string-async
      "Returns a promise of the HTML once every async read has settled."
      [el {:keys [nonce render-id]}]
      (js/Promise.resolve
       (sw/renderToStream (fn [] el) #js {:nonce nonce
                                          :renderId render-id})))

(defn render-to-stream [el {:keys [nonce render-id on-complete-shell on-complete-all]}]
      (sw/renderToStream (fn [] el) #js {:nonce nonce
                                         :renderId render-id
                                         :onCompleteShell on-complete-shell
                                         :onCompleteAll on-complete-all}))

(defn pipe [stream res]
      (.pipe ^js stream res))

(defn pipe-to [stream res]
      (.pipeTo ^js stream res))

(def server?
     sw/isServer)

(defn dev? []
      solid/DEV)
