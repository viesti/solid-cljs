(ns solid.core
    (:refer-clojure :exclude [flush])
    (:require-macros [solid.core])
    (:require ["solid-js" :as solid]
              ["@solidjs/web" :as sw]
              ["@solidjs/h" :default h]
              [solid.compiler :as sc]
              [solid.props :as props]))

(defn element?
      "Returns true if x is an element created with `$`."
      [x]
      (and (fn? x) (true? (.-solid-cljs-element ^js x))))

(defn create-element [tag & args]
      (when (string? tag) (sc/with-numeric-props (first args)))
      (let [el (apply h tag args)]
           (set! (.-solid-cljs-element ^js el) true)
           el))

;; Runtime of the templates that the `$` macro compiles, see solid.template

(defonce ^:private templates (js/Map.))

(defn -template
  "Returns the cached Solid template of `html`, which parses `html` on first use."
  [html flag]
  (let [k (str flag html)]
    (or (.get templates k)
        (let [t (sw/template html flag)]
          (.set templates k t)
          t))))

(defn -compiled
  "Marks `f`, which creates an element, as an element created with `$`."
  [f]
  (set! (.-solid-cljs-element ^js f) true)
  f)

(defn -insert
  ([parent accessor]
   (sw/insert parent accessor))
  ([parent accessor marker]
   (sw/insert parent accessor marker)))

(defn -effect [compute apply]
  (sw/effect compute apply))

(defn -event! [el event-name handler delegate?]
  (when handler
    (sw/addEvent el event-name handler delegate?)
    (when delegate? (sw/delegateEvents #js [event-name])))
  js/undefined)

(defn -attr! [el attr-name v prev]
  (when-not (identical? v prev)
    (sw/setAttribute el attr-name (if (keyword? v) (name v) v)))
  js/undefined)

(defn -class! [el v prev]
  (sw/className el v prev)
  js/undefined)

(defn -style! [el v prev]
  (sw/style el v prev)
  js/undefined)

(defn -prop! [el prop v prev]
  (when-not (identical? v prev)
    (aset el prop (if (and (nil? v) (= "value" prop)) "" v)))
  js/undefined)

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

(defn -resolve
      "Creates the element of `x` if `x` is an element created with `$`, so that the element
  belongs to the reactive owner that is current now. Arrays are resolved item by item."
      [x]
      (cond
        (element? x) (-resolve (x))
        (array? x) (.map x -resolve)
        :else x))

(defn- define-getter! [o k f]
      (js/Object.defineProperty o k #js {:get f :enumerable true :configurable true}))

(defn -props-obj
      "Creates a props object from `getters`, a flat array of names and getter functions, and
  `statics`, a flat array of names and values."
      [getters statics]
      (let [o #js {}]
        (loop [i 0]
          (when (< i (alength getters))
            (define-getter! o (aget getters i) (aget getters (inc i)))
            (recur (+ i 2))))
        (loop [i 0]
          (when (< i (alength statics))
            (aset o (aget statics i) (aget statics (inc i)))
            (recur (+ i 2))))
        o))

(defn -component
      "Returns an element that creates the component `comp` with `props`."
      [comp props]
      (-compiled (fn [] (-resolve (solid/createComponent comp props)))))

(defn- flow
      "Creates the control-flow component `comp`, such as `Show`, with getters for the
  `getters` map of names to functions and the `statics` map of names to values."
      [comp getters statics]
      (let [o #js {}]
        (doseq [[k f] getters] (define-getter! o k #(-resolve (f))))
        (doseq [[k v] statics] (aset o k v))
        (-resolve (solid/createComponent comp o))))

(defn -show [test then else]
      (flow solid/Show {"when" test "children" then "fallback" else} {}))

(defn -show-keyed [test then else]
      (flow solid/Show {"when" test "fallback" else}
            {"keyed" true "children" (fn [v] (-resolve (then v)))}))

(defn -for [coll body]
      (flow solid/For {"each" coll} {"children" (fn [v i] (-resolve (body v i)))}))

(defn -index [coll body]
      (flow solid/For {"each" coll}
            {"keyed" false "children" (fn [v i] (-resolve (body v i)))}))

(defn -switch [else pairs]
      (flow solid/Switch
            {"fallback" else
             "children" (fn []
                          (into-array
                            (for [[test then] pairs]
                              (let [o #js {}]
                                (define-getter! o "when" test)
                                (define-getter! o "children" #(-resolve (then)))
                                (solid/createComponent solid/Match o)))))}
            {}))

(defn -dynamic [f]
      (-resolve (solid/createComponent (sw/dynamic f) #js {})))

(defn -children [f]
      (solid/children f))

(defonce ^:private prop-names (js/Map.))

(defn- prop-name [k]
      (or (.get prop-names k)
          (let [n (props/prop-name k)]
            (.set prop-names k n)
            n)))

(deftype ReactiveProps [^js props children]
  ILookup
  (-lookup [this k]
    (-lookup this k nil))
  (-lookup [_ k not-found]
    (if (= k :children)
      (if children children not-found)
      (let [n (prop-name k)]
        (if (js-in n props) (unchecked-get props n) not-found))))

  ISeqable
  (-seq [_]
    (seq (for [n (js/Object.keys props)]
           (if (= "children" n)
             (MapEntry. :children children nil)
             (MapEntry. (props/prop-key n) (unchecked-get props n) nil)))))

  ICounted
  (-count [_]
    (alength (js/Object.keys props))))

(defn -props
      "Wraps the JavaScript props object of a `defui` component, whose keywords look up props
  by the names `solid.props/prop-name` gives them. Children are resolved once, here."
      [^js props]
      (->ReactiveProps props
                       (when (js-in "children" props)
                         (-children #(.-children props)))))

(defn -error-boundary [fallback & children]
      (flow solid/Errored {"children" #(into-array children)}
            {"fallback" (fn [err reset] (-resolve (fallback err reset)))}))

(defn -loading [fallback & children]
      (flow solid/Loading {"fallback" fallback "children" #(into-array children)} {}))

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
      (sw/render (if (element? root) root (-component root #js {})) node))

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
