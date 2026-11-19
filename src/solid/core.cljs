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
      (flow solid/Reveal {"children" #(into-array children)} opts))

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
        (for [[k v] src] [(if (keyword? k) (name k) (str k)) v])
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
                              (not (#{"ref" "innerHTML" "textContent" "innerText"} attr)))]
               (case attr
                 "class" (-ssr-class v)
                 "style" (-ssr-style (sc/interpret-style-map v))
                 (-ssr-attr (get {"defaultValue" "value" "defaultChecked" "checked"
                                  "defaultSelected" "selected" "defaultMuted" "muted"}
                                 attr attr)
                            v)))))

(defn -error-boundary [fallback & children]
      (flow solid/Errored {"children" #(into-array children)}
            {"fallback" (fn [err reset] (-resolve (fallback err reset)))}))

(defn -loading [fallback on & children]
      (flow solid/Loading
            (cond-> {"fallback" fallback "children" #(into-array children)} on (assoc "on" on))
            {}))

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

(defn hydrate
      "Hydrates the HTML that the server rendered into the DOM `node` from `root`, an element
  created with `$` or a component, and returns a function that disposes it. The client and
  the server must compile with the compiler option `:solid/hydratable`, and the page must
  include `hydration-script`."
      ([root node] (hydrate root node nil))
      ([root node {:keys [render-id]}]
       (sw/hydrate (root-thunk root) node (render-options {"renderId" render-id}))))

(defn hydration-script
      "Returns the script element, as HTML, that the head of a server-rendered page needs for
  `hydrate`."
      ([] (hydration-script nil))
      ([{:keys [nonce]}]
       (sw/generateHydrationScript (render-options {"nonce" nonce}))))

(defn render-to-string
      "Renders `root`, an element created with `$` or a component, to an HTML string on the
  server. Async reads that are still pending render their `s/loading` fallbacks."
      ([root] (render-to-string root nil))
      ([root {:keys [nonce render-id]}]
       (sw/renderToString (root-thunk root) (render-options {"nonce" nonce "renderId" render-id}))))

(defn render-to-string-async
      "Returns a promise of the HTML of `root` once every async read has settled."
      ([root] (render-to-string-async root nil))
      ([root {:keys [nonce render-id]}]
       (js/Promise.resolve
         (sw/renderToStream (root-thunk root) (render-options {"nonce" nonce "renderId" render-id})))))

(defn render-to-stream
      ([root] (render-to-stream root nil))
      ([root {:keys [nonce render-id on-complete-shell on-complete-all]}]
       (sw/renderToStream (root-thunk root) (render-options {"nonce" nonce
                                                             "renderId" render-id
                                                             "onCompleteShell" on-complete-shell
                                                             "onCompleteAll" on-complete-all}))))

(defn pipe [stream res]
      (.pipe ^js stream res))

(defn pipe-to [stream res]
      (.pipeTo ^js stream res))

(def server?
     sw/isServer)

(defn dev? []
      solid/DEV)
