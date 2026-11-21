(ns solid.core
  (:refer-clojure :exclude [flush])
  (:require-macros [solid.core])
  (:require ["solid-js" :as solid]
            ["@solidjs/web" :as sw]
            ["@solidjs/h" :default h]
            [solid.compiler :as sc]
            [solid.props :as props]
            ["solid-js/refresh" :as solid-refresh]))

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

;; Server rendering of the same templates, see solid.template/server-form

(declare -resolve)

(defn -ssr [parts & values]
  (if values
    (.apply sw/ssr nil (into-array (cons parts values)))
    (sw/ssr (aget parts 0))))

(defn -ssr-attr [attr-name v]
  (sw/ssrAttribute attr-name (sw/escape (if (keyword? v) (name v) v) true)))

(defn- ssr-quoted [attr-name v]
  (if (seq v) (str " " attr-name "=\"" v "\"") ""))

(defn -ssr-class [v]
  (ssr-quoted "class" (sw/ssrClassName (sc/class-value v))))

(defn -ssr-style [v]
  (ssr-quoted "style" (sw/ssrStyle v)))

(defn -ssr-child
  "Returns the escaped server HTML of a dynamic child."
  [x]
  (let [x (-resolve x)]
    (sw/escape (if (keyword? x) (name x) x))))

(defn -ssr-key []
  (sw/ssrHydrationKey))

(defn -ssr-raw [x]
  (if (nil? x) "" (str x)))

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
   (sw/insert parent accessor marker))
  ([parent accessor marker current]
   (sw/insert parent accessor marker current)))

(defn -child
  "Returns the value of a dynamic child as Solid renders it: a keyword renders its name, as on
  the server."
  [x]
  (if (keyword? x) (name x) x))

(defn -scope
  "Marks the accessor `f` of a dynamic child as a hydration scope, as Solid's compiled JSX
  does on both the server and the client."
  [f]
  (sw/scope f))

(defn -next-element
  "Claims the element that the server rendered for this template while hydrating, and
  clones `template` otherwise."
  [template]
  (sw/getNextElement template))

(defn -next-marker [start]
  (sw/getNextMarker start))

(defn -run-hydration-events []
  (sw/runHydrationEvents))

(defn -ref!
  "Calls the ref `r` with `el`. `r` is a function or a vector or array of functions."
  [el r]
  (when r
    (sw/applyRef (if (vector? r) (into-array r) r) el))
  js/undefined)

(defn -effect [compute apply]
  (sw/effect compute apply))

(defn -event! [el event-name handler delegate?]
  (when handler
    ;; A [handler data] pair calls handler with data and the event
    (sw/addEvent el event-name (if (vector? handler) (into-array handler) handler) delegate?)
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

(defn- signal-ref
  "Wraps the getter and setter of a Solid signal in an atom-like value."
  [value set-value]
  (reify
    IDeref
    (-deref [_]
      (value))
    IReset
    (-reset! [_ v]
          ;; The setter treats a function argument as an updater
      (set-value (fn [_] v)))
    ISwap
    (-swap! [_ f]
      (set-value (fn [old] (f old))))
    (-swap! [_ f a]
      (set-value (fn [old] (f old a))))
    (-swap! [_ f a b]
      (set-value (fn [old] (f old a b))))
    (-swap! [_ f a b xs]
      (set-value (fn [old] (apply f old a b xs))))))

(defn- reactive-options
  "Converts an options map of a signal, memo or effect to Solid's options object, with keys
  named as props are (`:owned-write` is `ownedWrite`).

  Callers bind the result before calling Solid. A call whose arguments need statements of
  their own makes Closure read the function off the imported module first, which aliases the
  module object and keeps the bundler from tree-shaking it."
  [opts]
  (let [o #js {}]
    (doseq [[k v] opts :when (some? v)]
      (aset o (props/prop-name k) v))
    o))

(defn signal
  "Creates an atom-like signal. Reads with `deref` are tracked.

  Writes with `reset!` and `swap!` are queued: `deref` keeps returning the
  previous value until Solid flushes its queue on the next microtask, or until
  `flush` is called. Consecutive `swap!` calls compose on the queued value.

  Options are `:equals` (a function, or false to notify on every write; `=` by default),
  `:owned-write` (allow writes while a component body or computation runs), `:unobserved` (a
  function to call when nothing reads the signal any more) and `:name` (for diagnostics)."
  ([v]
   (signal v nil))
  ([v opts]
   (let [o (reactive-options (merge {:equals =} opts))
         [value set-value] (solid/createSignal v o)]
     (signal-ref value set-value))))

(defn optimistic
  "Creates an atom-like optimistic signal. A write inside an action shows its value at
  once and reverts when the action settles, so the action refreshes the source of truth
  before it ends. With a function, the signal derives its value from the function."
  ([v]
   (optimistic v nil))
  ([v opts]
   (let [o (reactive-options opts)
         [value set-value] (solid/createOptimistic v o)]
     (signal-ref value set-value))))

(declare ->js source-fn wrap-setter)

(defn optimistic-store
  "Creates an optimistic store from `source`, a value or a function computing it (which may
  return a promise), and returns `[store set-store]`. Writes inside an action show at once
  and revert when the action settles. `set-store` works as the one of `store` does."
  ([source]
   (let [[st set-store] (solid/createOptimisticStore (if (fn? source) (source-fn source) (->js source)))]
     [st (wrap-setter set-store)]))
  ([source initial]
   (let [[st set-store] (solid/createOptimisticStore (source-fn source) (->js initial))]
     [st (wrap-setter set-store)])))

;; Actions, driven by an iterator that Solid's action steps like a generator

(defn -yield [value k]
  #js {:yielded true :value value :k k})

(defn -action-iterator
  "Returns an iterator over the steps of an action. `k` runs the next step: it receives the
  settled value of the previous yield and returns `-yield` for the next one, or the result."
  [k]
  (let [next-k (volatile! k)
        step (fn [input]
               (let [r (@next-k input)]
                 (if (and (object? r) (true? (.-yielded ^js r)))
                   (do (vreset! next-k (.-k ^js r))
                       #js {:value (.-value ^js r) :done false})
                   #js {:value r :done true})))]
    #js {:next step
         :throw (fn [e] (throw e))
         :return (fn [v] #js {:value v :done true})}))

(defn -action [f]
  (solid/action f))

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
  once per change.

  Options are `:defer` (skip the initial run), `:error` (a function of the error and a
  cleanup function, called when `compute` throws or an async value rejects, instead of the
  nearest `s/try`), `:schedule`, `:sync` and `:name`."
  ([f]
   (solid/createTrackedEffect #(cleanup (f))))
  ([compute apply]
   (solid/createEffect compute (with-cleanup apply)))
  ([compute apply {:keys [error] :as opts}]
   (let [effect-fn (if error
                     #js {:effect (with-cleanup apply)
                          :error (fn [err cleanup-fn] (error err cleanup-fn) js/undefined)}
                     (with-cleanup apply))
         o (reactive-options (dissoc opts :error))]
     (solid/createEffect compute effect-fn o))))

(defn render-effect
  "Creates an effect that runs during rendering. Takes the same `compute` and
  `apply` functions as `effect`. With a single function, the function runs in
  the tracked compute phase."
  ([f]
   (solid/createRenderEffect f (fn [_])))
  ([compute apply]
   (solid/createRenderEffect compute (with-cleanup apply)))
  ([compute apply opts]
   (let [effect-fn (with-cleanup apply)
         o (reactive-options opts)]
     (solid/createRenderEffect compute effect-fn o))))

(defn on-settled
  "Runs `f` once the current reactive activity settles. `f` may return a
  cleanup function that runs when the owner is disposed."
  [f]
  (solid/onSettled #(cleanup (f))))

(defn on-mount
  "Runs `f` after the component's first render settles. Same as `on-settled`."
  [f]
  (solid/onSettled (fn [] (f) js/undefined)))

(defn on-flush
  "Runs `f` once, right after the pending updates are applied. For event handlers and
  effect callbacks."
  [f]
  (solid/onFlush #(do (f) js/undefined)))

(defn -pending? [f]
  (solid/isPending f))

(defn -latest [f]
  (solid/latest f))

(defn -resolve-async [f]
  (solid/resolve f))

(defn refresh
  "Computes the async memo `target` again. The new value replaces the current one quietly,
  unless `affects` declared the change first."
  [target]
  (solid/refresh target))

(defn affects
  "Declares that work in flight will change `target`, a memo or a store (or the key `k` of a
  store), so that reads of it are pending until the work settles."
  ([target] (solid/affects target))
  ([target k] (solid/affects target k)))

(defn get-owner []
  (solid/getOwner))

(defn run-with-owner
  "Runs `f` with the reactive owner `owner`, or with no owner when `owner` is nil."
  [owner f]
  (solid/runWithOwner owner f))

(defn from
  "Returns an async iterable of the values of `source`: a subscribe function
  `(fn [next error complete] unsubscribe)`, an observable or an async iterable. Read it in a
  memo, `(s/memo #(s/from source))`."
  [source]
  (solid/from source))

(defn map-array
  "Maps the reactive list `list`, a function returning an array, with `f` called once per
  item, and returns an accessor of the mapped array."
  ([list f] (solid/mapArray list f))
  ([list f opts] (solid/mapArray list f (clj->js opts))))

(defn on-cleanup [f]
  (solid/onCleanup f))

(defn memo
  "Creates a derived value from `f`, cached until what `f` reads changes, and returns a
  function that reads it. `f` may return a promise or an async iterable, which `s/loading`
  waits for. Options are `:equals`, `:lazy` (compute on first read, and dispose when
  unobserved), `:unobserved` and `:name`."
  ([f]
   (solid/createMemo f))
  ([f opts]
   (let [o (reactive-options opts)]
     (solid/createMemo f o))))

(defn -resolve
  "Creates the element of `x` if `x` is an element created with `$`, so that the element
  belongs to the reactive owner that is current now. Arrays are resolved item by item."
  [x]
  (cond
    (element? x) (-resolve (x))
    (array? x) (.map x -resolve)
    :else x))

(defn -memo-child
  "Creates the child that `f` returns in a memo, owned by the component that reads its
  children, as Solid's compiled JSX does with dynamic children. Components and boundaries in
  the child then see that component's context."
  [f]
  (solid/createMemo #(-resolve (f))))

(defn- memo-children
  "Returns the children of a control-flow component, each created in a memo by the reader."
  [children]
  (into-array (map #(if (fn? %) (-memo-child %) %) children)))

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

;; Hot reloading that keeps state, through solid-js/refresh

(defonce ^:private refresh-state #js {})

(defn -refresh-component
  "Returns a proxy of the component `f` that Solid's refresh runtime swaps to the latest
  definition of `id` when a hot reload evaluates the component again, keeping the state of the
  components around it. Stands in for the hot API that bundlers give compiled JSX modules,
  keeping each component's registry across reloads."
  ([id f loc] (-refresh-component id f loc nil))
  ([id f loc signature]
   (let [^js state (or (unchecked-get refresh-state id)
                       (let [st #js {:disposers #js []}]
                         (unchecked-set refresh-state id st)
                         st))
         disposers (.-disposers state)
         data (when (pos? (alength disposers))
                (let [d #js {}]
                  (doseq [cb disposers] (cb d))
                  d))
         _ (set! (.-disposers state) #js [])
            ;; Only components with Solid's development marker follow updates through a proxy
         _ (unchecked-set f solid/$DEVCOMP true)
         registry (solid-refresh/$$registry)
         proxy (solid-refresh/$$component registry id f #js {:location (or loc js/undefined)
                                                             :signature (or signature js/undefined)})
         hot #js {:data (or data js/undefined)
                  :accept (fn [& _])
                  :dispose (fn [cb] (.push (.-disposers state) cb))
                  :invalidate (fn [] (.reload js/location))}]
     (solid-refresh/$$refresh "standard" hot registry)
     proxy)))

(defn -component
  "Returns an element that creates the component `comp` with `props`, named
  `component-name` at `loc` in Solid's diagnostics."
  ([comp props]
   (-compiled (fn [] (-resolve (solid/createComponent comp props)))))
  ([comp props component-name loc]
   (-compiled (fn [] (-resolve (solid/createComponent comp props component-name (or loc js/undefined)))))))

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

(defn -for [coll body {:keys [fallback key]}]
  (flow solid/For
        (cond-> {"each" coll} fallback (assoc "fallback" fallback))
        (cond-> {"children" (fn [v i] (-resolve (body v i)))}
          key (assoc "keyed" (if (fn? key) key #(key %))))))

(defn -index [coll body {:keys [fallback]}]
  (flow solid/For
        (cond-> {"each" coll} fallback (assoc "fallback" fallback))
        {"keyed" false "children" (fn [v i] (-resolve (body v i)))}))

(defn -repeat [n body {:keys [fallback]}]
  (flow solid/Repeat
        (cond-> {"count" n} fallback (assoc "fallback" fallback))
        {"children" (fn [i] (-resolve (body i)))}))

(defn -reveal [opts & children]
  (flow solid/Reveal {"children" #(memo-children children)} opts))

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
                   ;; Untracked, since the props of a server component's slot fill are reactive
                   (when (solid/untrack #(js-in "children" props))
                     (-children #(.-children props)))))

;; Spreading props onto components and attributes onto elements, with `:&`

(defn- spread-source
  "Returns the JavaScript props object of a spread source: a `defui` props value, a
  ClojureScript map whose keys become prop names, or a JavaScript object."
  [src]
  (cond
    (instance? ReactiveProps src) (.-props ^js src)
    (map? src) (let [o #js {}]
                 (doseq [[k v] src] (aset o (props/prop-name k) v))
                 o)
    :else (or src #js {})))

(defn -merge-spread
  "Merges the spread source that `src-fn` returns under the props object `own`, keeping
  both reactive. Props in `own` win."
  [src-fn own]
  (solid/merge (fn [] (spread-source (src-fn))) own))

(defn merge-props
  "Merges props sources (`defui` props, maps or JavaScript objects) into props that a
  component can read, the later sources winning. Getters stay reactive."
  [& sources]
  (-props (apply solid/merge (map spread-source sources))))

(defn omit-props
  "Returns `props` without the props named by the keywords `ks`, staying reactive."
  [props & ks]
  (-props (apply solid/omit (spread-source props) (map props/prop-name ks))))

(defn- source-entries
  "Returns the [name value] pairs of a spread source, with names as written in keywords or
  in JavaScript."
  [src]
  (if (map? src)
    (for [[k v] src] [(props/entry-name k) v])
    (let [^js o (spread-source src)]
      (for [n (js/Object.keys o)] [n (unchecked-get o n)]))))

(defn -element-attrs
  "Returns the attributes of the spread source `src` for Solid's `spread`, leaving out
  `explicit`, the attribute names the element sets itself."
  [src explicit]
  (let [o #js {}]
    (doseq [[n v] (source-entries src)
            :let [attr (props/attr-name n)]
            :when (and attr (not (contains? explicit attr)))]
      (aset o attr (case attr
                     "class" (sc/class-value v)
                     "style" (sc/interpret-style-map v)
                     (if (keyword? v) (name v) v))))
    o))

(declare render-options)

(defn -claim!
  "Lets a router claim the anchor or form `el`, to mark active links or intercept
  navigation."
  [el owner]
  (sw/claimElement el (or owner js/undefined))
  js/undefined)

(defn -ssr-link-claim
  "Returns the attributes that a router's link claim adds to an anchor on the server."
  [href aria-current]
  (sw/ssrLinkClaim (render-options {"href" href "aria-current" aria-current})))

(defn -spread! [el accessor]
  (sw/spread el accessor true)
  js/undefined)

(defn -ssr-spread
  "Returns the server HTML of the attributes of the spread source `src`, leaving out
  `explicit`. Events, refs and DOM properties without an attribute are left out."
  [src explicit]
  (apply str
         (for [[n v] (source-entries src)
               :let [attr (props/attr-name n)]
               :when (and attr
                          (not (contains? explicit attr))
                          (not (re-matches #"on[A-Z].*" attr))
                          (not (.startsWith attr "prop:"))
                          (not (#{"ref" "innerHTML" "textContent" "innerText"} attr)))]
           (case attr
             "class" (-ssr-class v)
             "style" (-ssr-style (sc/interpret-style-map v))
             (-ssr-attr (get {"defaultValue" "value" "defaultChecked" "checked"
                              "defaultSelected" "selected" "defaultMuted" "muted"}
                             attr attr)
                        v)))))

(defn -error-boundary [fallback & children]
  (flow solid/Errored {"children" #(memo-children children)}
        {"fallback" (fn [err reset] (-resolve (fallback err reset)))}))

(defn -loading [fallback on & children]
  (flow solid/Loading
        (cond-> {"fallback" fallback "children" #(memo-children children)} on (assoc "on" on))
        {}))

(defn -lazy
  "Wraps Solid's `lazy`, accepting a promise of a module with a default export, as from a
  dynamic import, or of a component, as from `shadow.lazy/load`."
  [f]
  (solid/lazy (fn []
                (.then (f) (fn [m] (if (fn? m) #js {:default m} m))))))

(defn -reaction [f]
  (solid/createReaction (with-cleanup f)))

;; Stores

(deftype Replacement [value])

(defn replacement
  "Marks `v` as the value that a store's draft function returns to replace the draft.
  Draft functions' other return values are ignored, since ClojureScript functions return their
  last value."
  [v]
  (->Replacement v))

(defn- ->js
  "Converts ClojureScript collections to the plain JavaScript objects and arrays that stores
  hold, and leaves other values as they are."
  [v]
  (if (coll? v) (clj->js v) v))

(defn- draft-fn
  "Wraps the draft function `f` of a store so that only a `replacement` return value
  replaces the draft."
  [f]
  (fn [draft]
    (let [r (f draft)]
      (if (instance? Replacement r) (->js (.-value r)) js/undefined))))

(defn- source-fn
  "Wraps the source function of a derived store: a draft function of one argument, or a
  function of none that returns the value."
  [f]
  (if (zero? (.-length f)) (fn [] (->js (f))) (draft-fn f)))

(defn- wrap-setter [set-store]
  (fn [x]
    (set-store (if (fn? x) (draft-fn x) (constantly (->js x))))))

(defn store
  "Creates a store and returns `[store set-store]`. `init` is a map or JavaScript object, or
  a function that derives the store: of no arguments, returning its value (which may be a
  promise), or of a draft, mutating it, with `seed` as the starting value.

  `set-store` takes a function that mutates the draft, or a value that replaces the store.
  A draft function replaces the draft only when it returns `(s/replacement v)`."
  ([init]
   (if (fn? init)
     (store init nil nil)
     (let [[st set-store] (solid/createStore (->js init))]
       [st (wrap-setter set-store)])))
  ([init seed] (store init seed nil))
  ([init seed opts]
   (let [[st set-store] (solid/createStore (source-fn init) (->js seed) (clj->js opts))]
     [st (wrap-setter set-store)])))

(defn projection
  "Creates a read-only derived store from `f`, a function of no arguments returning its value,
  or of a draft that it mutates starting from `seed`. Items keep their identity across
  recomputations, keyed by `(:key opts)` (default \"id\")."
  ([f] (solid/createProjection (source-fn f)))
  ([f seed] (projection f seed nil))
  ([f seed opts] (solid/createProjection (source-fn f) (->js seed) (clj->js opts))))

(defn- prop-key-name [k]
  (if (keyword? k) (name k) k))

(defn sget
  "Reads the value at `path` in a store, a sequence of keywords and indexes. Reads are
  tracked."
  [store & path]
  (reduce (fn [o k] (when (some? o) (unchecked-get o (prop-key-name k)))) store path))

(defn- parent-of [draft path]
  (reduce (fn [o k] (unchecked-get o (prop-key-name k))) draft (butlast path)))

(defn sset!
  "Sets the value at `path` in a store through its `set-store`."
  [set-store path v]
  (set-store (fn [draft]
               (unchecked-set (parent-of draft path) (prop-key-name (last path)) (->js v))
               nil)))

(defn supdate!
  "Updates the value at `path` in a store with `(apply f value args)` through its
  `set-store`."
  [set-store path f & args]
  (set-store (fn [draft]
               (let [parent (parent-of draft path)
                     k (prop-key-name (last path))]
                 (unchecked-set parent k (->js (apply f (unchecked-get parent k) args))))
               nil)))

(defn reconcile!
  "Reconciles `value` into the draft subtree `target`, keeping the identity of items whose
  `key` (default \"id\") matches."
  ([target value] (reconcile! target value "id"))
  ([target value key]
   ((solid/reconcile (->js value) (prop-key-name key)) target)
   nil))

(defn snapshot
  "Returns a plain, non-reactive copy of a store."
  [store]
  (solid/snapshot store))

(defn store->clj
  "Returns the data of a store as ClojureScript data with keyword keys."
  [store]
  (js->clj (solid/snapshot store) :keywordize-keys true))

(defn deep
  "Reads every value nested in a store, so that an effect's compute function that calls it
  tracks the whole store."
  [store]
  (solid/deep store))

(defn track-keys
  "Reads which keys the store `store` has, so that a computation that calls it re-runs when
  keys are added or removed, but not when their values change. Returns `store`."
  [^js store]
  (unchecked-get store solid/$TRACK)
  store)

(defn wrappable?
  "Returns true if `value` has a shape that stores wrap in a reactive proxy: a plain object, an
  array, or an instance of a class that was not given to `mark-raw`. A single value given to
  `mark-raw` is kept raw although it has such a shape."
  [value]
  (solid/isWrappable value))

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

(defn create-context
  "Creates a context, which is also its provider component: `($ ctx {:value v} ...)`.
  Without a default value, `use-context` throws when no provider is above it. Use a default
  only for values with a natural fallback, such as a theme."
  ([] (solid/createContext))
  ([default-value] (solid/createContext default-value)))

(defn use-context [context]
  (solid/useContext context))

(defn render
  "Renders `root` into the DOM `node` and returns a function that disposes it.
  `root` is either an element created with `$` or a component."
  [root node]
  (sw/render (if (element? root) root (-component root #js {})) node))

(defn- root-thunk
  "Returns a function that creates `root`, an element created with `$` or a component."
  [root]
  (let [el (if (element? root) root (-component root #js {}))]
    (fn [] (-resolve el))))

(defn- render-options
  "Returns a JavaScript options object without the options that are nil, so that Solid
  applies its defaults, which a null value would replace."
  [m]
  (let [o #js {}]
    (doseq [[k v] m :when (some? v)] (aset o k v))
    o))

;; ClojureScript data in hydration data. Seroval cannot serialize ClojureScript collections, so
;; a plugin writes each one as a call of a global function, `self._$CLJS(type, contents)`, with
;; the contents (map entries, items, a keyword's name) serialized by seroval itself. The
;; serialized values run as inline scripts that may run before the ClojureScript bundle has
;; loaded: until `hydrate` installs the constructor, `self._$CLJS` returns placeholder objects,
;; which `hydrate` then turns into the data they stand for, in place, before anything reads them.

(def ^:private cljs-data-runtime
  "The JavaScript of `self._$CLJS` until `hydrate` replaces it: queues a placeholder."
  (str "(self._$CLJS||(self._$CLJS=function(t,a){var o={t:t,a:a};"
       "(self._$CLJSQ||(self._$CLJSQ=[])).push(o);return o}))"))

(defn- cljs-data?
  "Returns true for the values that the plugin serializes: ClojureScript collections, keywords,
  symbols and UUIDs."
  [v]
  (or (coll? v) (keyword? v) (symbol? v) (uuid? v)))

(defn- cljs-data-parts
  "Returns the type letter of `v` and its contents as a string or a JavaScript array. Records
  and sorted maps become maps, other sequential collections lists."
  [v]
  (cond
    (keyword? v) #js ["k" (subs (str v) 1)]
    (symbol? v) #js ["y" (str v)]
    (uuid? v) #js ["u" (str v)]
    (map? v) #js ["m" (let [a #js []]
                        (doseq [[k x] v] (.push a k x))
                        a)]
    (vector? v) #js ["v" (to-array v)]
    (set? v) #js ["s" (to-array v)]
    :else #js ["l" (to-array v)]))

(defn- cljs-data-info [^js ctx v]
  (let [[t a] (cljs-data-parts v)]
    #js {:t t :a (.parse ctx a)}))

(defn- cljs-data-plugin
  "Returns a serialization plugin for ClojureScript data, for one render. Its first value
  carries the definition of `self._$CLJS`, and later ones call it by name, since the scripts of
  a render run in the order they were written."
  []
  (let [defined? (volatile! false)]
    #js {:tag "solid-cljs/data"
         :test cljs-data?
         :skipPlainObjects true
         :parse #js {:sync (fn [v ctx] (cljs-data-info ctx v))
                     :stream (fn [v ctx] (cljs-data-info ctx v))
                     :async (fn [v ^js ctx]
                              (let [[t a] (cljs-data-parts v)]
                                (.then (.parse ctx a) (fn [n] #js {:t t :a n}))))}
         :serialize (fn [^js node ^js ctx]
                      (str (if @defined?
                             "_$CLJS"
                             (do (vreset! defined? true) cljs-data-runtime))
                           "(\"" (.-t node) "\"," (.serialize ctx (.-a node)) ")"))}))

(defn- with-cljs-data-plugin
  "Returns the array of serialization plugins for a render: `plugins` and one for ClojureScript
  data."
  [plugins]
  (into-array (cons (cljs-data-plugin) plugins)))

(defn- cljs-data
  "Returns the ClojureScript value of type `t` with contents `a`, as the plugin wrote them."
  [t a]
  (case t
    "k" (keyword a)
    "y" (symbol a)
    "u" (uuid a)
    "m" (loop [i 0 m (transient {})]
          (if (< i (alength a))
            (recur (+ i 2) (assoc! m (aget a i) (aget a (inc i))))
            (persistent! m)))
    "v" (vec (aclone a))
    "s" (set a)
    (apply list a)))

(defn- become!
  "Turns the placeholder object `o` into `v`, a ClojureScript object, in place, so that every
  reference to the placeholder in the hydration data sees `v`."
  [o v]
  (js-delete o "t")
  (js-delete o "a")
  (js/Object.setPrototypeOf o (js/Object.getPrototypeOf v))
  (js/Object.assign o v))

(defn- install-cljs-data!
  "Turns the placeholders that inline scripts created into ClojureScript data, and makes
  `self._$CLJS` build the data directly for the scripts that run later. The queue holds
  contents before the collections that hold them, so they are turned first."
  []
  (let [q (unchecked-get js/self "_$CLJSQ")]
    (unchecked-set js/self "_$CLJS" cljs-data)
    (unchecked-set js/self "_$CLJSQ" nil)
    (when q
      (doseq [o q]
        (become! o (cljs-data (unchecked-get o "t") (unchecked-get o "a")))))))

(defn hydrate
  "Hydrates the HTML that the server rendered into the DOM `node` from `root`, an element
  created with `$` or a component, and returns a function that disposes it. The client and
  the server must compile with the compiler option `:solid/hydratable`, and the page must
  include `hydration-script`."
  ([root node] (hydrate root node nil))
  ([root node {:keys [render-id]}]
   (install-cljs-data!)
   (sw/hydrate (root-thunk root) node (render-options {"renderId" render-id}))))

(defn hydration-script
  "Returns the script element, as HTML, that the head of a server-rendered page needs for
  `hydrate`."
  ([] (hydration-script nil))
  ([{:keys [nonce]}]
   (sw/generateHydrationScript (render-options {"nonce" nonce}))))

(defn render-to-string
  "Renders `root`, an element created with `$` or a component, to an HTML string on the
  server. Async reads that are still pending render their `s/loading` fallbacks.

  Options are `:nonce`, `:render-id`, `:no-scripts`, `:on-head`, a function that receives
  the HTML of the head tags (see `use-head`) when the rendered HTML has no `</head>`, and
  `:plugins`, serialization plugins such as `solid.server-components/plugin`. ClojureScript
  data in async values (collections, keywords, symbols and UUIDs) is serialized for `hydrate`,
  with records and sorted maps as maps and other sequences as lists, without metadata."
  ([root] (render-to-string root nil))
  ([root {:keys [nonce render-id no-scripts on-head plugins]}]
   (sw/renderToString (root-thunk root) (render-options {"nonce" nonce "renderId" render-id
                                                         "noScripts" no-scripts "onHead" on-head
                                                         "plugins" (with-cljs-data-plugin plugins)}))))

(defn render-to-string-async
  "Returns a promise of the HTML of `root` once every async read has settled."
  ([root] (render-to-string-async root nil))
  ([root {:keys [nonce render-id no-scripts on-head plugins]}]
   (js/Promise.resolve
    (sw/renderToStream (root-thunk root) (render-options {"nonce" nonce "renderId" render-id
                                                          "noScripts" no-scripts "onHead" on-head
                                                          "plugins" (with-cljs-data-plugin plugins)})))))

(defn render-to-stream
  ([root] (render-to-stream root nil))
  ([root {:keys [nonce render-id no-scripts on-head on-complete-shell on-complete-all plugins]}]
   (sw/renderToStream (root-thunk root) (render-options {"nonce" nonce
                                                         "renderId" render-id
                                                         "noScripts" no-scripts
                                                         "onHead" on-head
                                                         "plugins" (with-cljs-data-plugin plugins)
                                                         "onCompleteShell" on-complete-shell
                                                         "onCompleteAll" on-complete-all}))))

;; More of Solid's API

(defn create-error-boundary
  "Returns an accessor of `(f)`, or of `(fallback error reset)` once something `f` runs throws,
  where `error` is an accessor of the error and `reset` tries `f` again. The primitive behind
  `s/try`, for components that make their own boundaries."
  [f fallback]
  (solid/createErrorBoundary f fallback))

(defn create-loading-boundary
  "Returns an accessor of `(f)`, or of `(fallback)` while async reads under `f` are pending. The
  primitive behind `s/loading`. `opts` takes `:on`, a function whose reads restart the pending
  state when they change."
  ([f fallback] (solid/createLoadingBoundary f fallback))
  ([f fallback opts] (solid/createLoadingBoundary f fallback (reactive-options opts))))

(defn create-reveal-order
  "Runs `f`, coordinating when the loading boundaries under it reveal. The primitive behind
  `s/reveal`. `opts` takes `:order` and `:collapsed`, functions returning the order (such as
  \"together\" or \"sequential\") and whether to collapse pending fallbacks."
  ([f] (solid/createRevealOrder f))
  ([f opts] (solid/createRevealOrder f (reactive-options opts))))

(defn mark-raw
  "Marks `value` so that stores hold it as it is, without making it reactive."
  [value]
  (solid/markRaw value))

(defn -until [f opts]
  (solid/until f (when opts (reactive-options opts))))

(def NotReadyError
  "Solid's error for a read whose value is not ready yet. Throwing it from a computation
  makes the nearest `s/loading` show its fallback."
  solid/NotReadyError)

(def TimeoutError
  "Solid's error for an `s/until` that timed out."
  solid/TimeoutError)

(defn create-owner
  "Creates a reactive owner, with an optional map of `:id` and `:transparent`."
  ([] (solid/createOwner))
  ([opts] (let [o (reactive-options opts)] (solid/createOwner o))))

(defn disposed?
  "Returns true if the reactive owner `owner` is disposed."
  [owner]
  (solid/isDisposed owner))

(defn repeat-map
  "Returns an accessor of an array of `(f i)` for each index below the number that `count`
  returns, calling `f` once per index."
  [count f]
  (solid/repeat count f))

(defn enable-external-source
  "Runs Solid's computations inside another reactive system, given its `:factory` and
  `:untrack` functions."
  [{:keys [factory untrack]}]
  (solid/enableExternalSource (render-options {"factory" factory "untrack" untrack})))

(defn configure-client-errors
  "Configures how the browser reports errors that no `s/try` catches."
  [opts]
  (solid/configureClientErrors (reactive-options opts)))

(defn reset-error-halt!
  "Resumes the reactive system after an uncaught error halted it."
  []
  (solid/resetErrorHalt))

(defn hydrating?
  "Returns true while hydration is in progress."
  []
  (solid/isHydrating))

(defn on-hydration-end
  "Runs `f` once hydration has finished."
  [f]
  (solid/onHydrationEnd #(do (f) js/undefined)))

(defn lazy-module
  "Returns a loader of the module of `f`, a function returning a promise of it, that
  hydration can find again by `module-url`, for components that `s/lazy` loads from one
  module."
  ([f] (solid/lazyModule f))
  ([f module-url] (solid/lazyModule f module-url)))

;; Assets and trusted types

(defn- asset-descriptor [{:keys [type href id content attrs] :as descriptor}]
  (if (map? descriptor)
    (render-options {"type" (name type) "href" href "id" id "content" content
                     "attrs" (when attrs (clj->js attrs))})
    descriptor))

(defn warm-asset
  "Starts loading an asset before it is needed: a map of `:type` (`:style`, `:inline-style`
  or `:module`) and `:href`, or `:id` and `:content` for inline styles, with optional
  `:attrs`."
  [descriptor]
  (sw/warmAsset (asset-descriptor descriptor)))

(defn acquire-asset
  "Adds an asset to the document while something uses it, and returns a function that
  releases it. The asset is removed once every user has released it."
  [descriptor]
  (sw/acquireAsset (asset-descriptor descriptor)))

(defn wait-asset
  "Keeps the surrounding `s/loading` boundary pending, when called in a computation, until
  `promise` resolves, as Solid does while a stylesheet that a component acquired loads."
  [promise]
  (sw/waitAsset promise))

(defn set-trusted-types-policy!
  "Makes Solid parse templates and HTML through the Trusted Types policy `policy`, an object
  with a `createHTML` function, as pages with a `require-trusted-types-for` policy need."
  [policy]
  (sw/setTrustedTypesPolicy policy))

(defn trusted-html
  "Returns `html` as the policy that `set-trusted-types-policy!` set makes it, or as is."
  [html]
  (sw/trustedHTML html))

(defn dispatch-as-interaction
  "Calls `f` as a handler of the DOM event `e`, so that in development and observe builds
  its work joins the interaction record of Solid's own handlers for `e`. Returns what `f`
  returns. Wrap listeners that Solid did not attach and that respond to the user, such as a
  `click` listener on `document`."
  [e f]
  (sw/dispatchAsInteraction e f))

;; Server and document utilities

(def no-hydration
  "Solid's `NoHydration` component: its children render on the server only and are not
  hydrated."
  solid/NoHydration)

(def hydration
  "Solid's `Hydration` component, which resumes hydration inside `no-hydration`."
  solid/Hydration)

(defn- head-tag [{:keys [tag props key]}]
  (render-options {"tag" (name tag)
                   "props" (clj->js props)
                   "key" key}))

(defn use-head
  "Adds tags to the document head: a map of `:tag` (`:title`, `:meta`, `:link`, `:style`,
  `:script` or `:base`), `:props` and an optional `:key`, a vector of them, or a function
  returning either. The server renders them into the head, and the client keeps them in
  sync."
  [tags]
  (let [->js (fn [t] (if (sequential? t) (into-array (map head-tag t)) (head-tag t)))]
    (sw/useHead (if (fn? tags) #(->js (tags)) (->js tags)))))

(defn client-only
  "Returns a component that loads the module of `f` (a function returning a promise of a
  module with a default export) and renders it on the client only."
  [f]
  (sw/clientOnly f))

(defn safe-error
  "Marks `error` as safe to show, so that Solid's server keeps its message instead of
  replacing it with \"Internal Server Error\"."
  [error]
  (sw/markSafeError error))

(defn request-event
  "Returns the request event of the current server render, or nil."
  []
  (sw/getRequestEvent))

(defn redirect
  ([url] (sw/redirect url))
  ([url init] (sw/redirect url (if (map? init) (clj->js init) init))))

(defn- response-init
  "Converts a map of response options, such as `:status` and a `:headers` map, to Solid's."
  [init]
  (if (map? init)
    (reactive-options (cond-> init (map? (:headers init)) (update :headers clj->js)))
    init))

(defn respond
  "Returns `value` with the response status and headers of `init`, for server functions."
  ([value] (sw/respond value))
  ([value init] (sw/respond value (response-init init))))

(defn reload
  "Returns a response that tells the client to reload its data."
  ([] (sw/reload))
  ([init] (sw/reload (response-init init))))

(defn serialize-cookie
  "Returns a `Set-Cookie` header value for the cookie `cookie-name`, with options such as
  `:max-age`, `:path`, `:http-only` and `:same-site`."
  ([cookie-name value] (sw/serializeCookie cookie-name value))
  ([cookie-name value opts] (sw/serializeCookie cookie-name value (reactive-options opts))))

(defn parse-cookie-header
  "Returns the cookies of a `Cookie` header as a map of names to values."
  [header]
  (js->clj (sw/parseCookieHeader header)))

(defn configure-server-errors
  "Configures how the server reports and shows errors."
  [opts]
  (sw/configureServerErrors (reactive-options opts)))

(defn http-status
  ([code] (sw/httpStatus code))
  ([code text] (sw/httpStatus code text)))

(defn http-header
  ([header-name value] (sw/httpHeader header-name value))
  ([header-name value opts] (sw/httpHeader header-name value (clj->js opts))))

(defn pipe [stream res]
  (.pipe ^js stream res))

(defn pipe-to [stream res]
  (.pipeTo ^js stream res))

(def server?
  sw/isServer)

(defn dev? []
  solid/DEV)
