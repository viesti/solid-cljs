(ns solid.core
    (:refer-clojure :exclude [if when for cond case try if-let when-let if-some when-some or some-> some->> resolve repeat])
    (:require [clojure.core :as core]
              [clojure.walk :as walk]
              [solid.compiler :as sc]
              [solid.lint :as lint]
              [solid.props :as props]
              [solid.template :as tpl]))

(defn- literal?
       "Returns true if the expression is a compile-time literal."
       [x]
       (core/or
        (string? x)
        (number? x)
        (keyword? x)
        (nil? x)
        (true? x)
        (false? x)))

(defn- binding-symbols
  "Returns the symbols that `form` may bind: the patterns of a let-style vector right after a
  form's head symbol (as in `let`, `loop`, `for` and `s/for`), every symbol of a parameter
  vector (after `fn`, after a name, or opening an arity list) or of an odd-length vector after
  a head, and the names that `catch` and `as->` bind. A data vector in those positions counts
  too, which only keeps a prop from being rewritten."
  [form]
  (let [found (volatile! #{})
        collect (fn collect [x]
                  (core/cond
                    (symbol? x) (vswap! found conj x)
                    (coll? x) (run! collect (if (map? x) (concat (keys x) (vals x)) x))))]
    (walk/prewalk (fn [x]
                    (core/when (core/and (seq? x) (symbol? (first x)))
                      (let [[head a b] x]
                        (core/cond
                          (#{'catch 'as->} head) (run! collect [a b])
                          ;; Parameter vectors bind every symbol in them
                          (core/and (vector? a) (#{'fn 'fn*} head)) (collect a)
                          (core/and (symbol? a) (vector? b)) (collect b)
                          ;; let-style vectors bind the patterns at even positions
                          (core/and (vector? a) (even? (count a))) (run! collect (take-nth 2 a))
                          (vector? a) (collect a))
                        (doseq [arity (rest x)]
                          (core/when (core/and (seq? arity) (vector? (first arity)))
                            (collect (first arity))))))
                    x)
                  form)
    @found))

(defn- props-lookups
  "Splits the map destructuring `m` of a component's props. Returns the symbols to rewrite into
  prop lookups, each with its lookup form on `props`, and the destructuring that remains."
  [m props body]
  (let [bound (binding-symbols body)
        defaults (:or m)
        lookup (fn [sym k]
                 (if (contains? defaults sym)
                   `(get ~props ~k ~(get defaults sym))
                   `(get ~props ~k)))
        candidates (concat
                     (core/for [k (:keys m)]
                       [(symbol (name k)) (keyword (namespace k) (name k)) [:keys k]])
                     (core/for [[k v] m :when (core/and (symbol? k) (keyword? v))]
                       [k v [:entry k]]))
        rewritten (remove (fn [[sym]] (contains? bound sym)) candidates)
        remaining (reduce (fn [m [sym _ [kind k]]]
                            (core/case kind
                              :keys (update m :keys #(vec (remove #{k} %)))
                              :entry (dissoc m k)))
                          (dissoc m :as)
                          rewritten)
        remaining (cond-> remaining (empty? (:keys remaining)) (dissoc :keys))]
    {:lookups (into {} (core/for [[sym k] rewritten] [sym (lookup sym k)]))
     :kept (map first (remove (set rewritten) candidates))
     :remaining (core/when (seq (dissoc remaining :or)) remaining)}))

(defmacro defui
          "Defines a Solid UI component. Supports docstrings and metadata.

  Props destructured with a map in the argument list stay reactive: each use of a destructured
  name reads the prop at that point. A name that the body binds again, in a binding vector or
  map, is read once instead, and the compiler warns about it.

  Example:
  ```clojure
  (defui my-component
    \"A component that renders a button.\"
    [{:keys [on-click children]}]
    ($ :button {:on-click on-click} children))
  ```"
          {:arglists '([name docstring? attr-map? [props] & body])}
          [sym & args]
          (let [[docstring args] (if (string? (first args))
                                     [(first args) (rest args)]
                                     [nil args])
                [attr-map args] (if (map? (first args))
                                    [(first args) (rest args)]
                                    [nil args])
                [params & body] args
                props-param (first params)
                meta-map (core/cond-> {}
                                      docstring (assoc :doc docstring)
                                      attr-map (merge (dissoc attr-map :solid/lint)))
                props-sym (if (map? props-param)
                            (core/or (:as props-param) (gensym "props"))
                            (core/or props-param (gensym "props")))
                {:keys [lookups kept remaining]} (core/when (map? props-param)
                                                   (props-lookups props-param props-sym body))
                body (walk/postwalk-replace lookups body)]
               (core/when-not (false? (:solid/lint attr-map))
                 (lint/lint-component-body! sym body &env)
                 (core/when (seq kept) (lint/warn-destructured-props! sym kept &env)))
               `(defn ~(with-meta sym meta-map) [props#]
                      (let [~props-sym (solid.core/-props props#)
                            ~@(core/when remaining [remaining props-sym])]
                           ~@body))))

(defn- wrap-children [children]
       (core/for [x children]
                 (if (literal? x)
                     x  ; Don't wrap literals - they can't be reactive
                     `(fn [] ~x))))

(defn- fn-form?
       "Returns true if the expression is a function literal (fn or fn*)."
       [x]
       (and (seq? x)
            (let [head (first x)]
                 (core/or (= head 'fn)
                          (= head 'fn*)))))

(defn- static-prop?
       "Returns true if a prop value is passed as is: a literal or a function form."
       [v]
       (core/or (sc/literal? v) (fn-form? v)))

(defn- props-form
       "Returns the form that creates a component's props object. Keys are converted with
  `solid.props/prop-name`, and values other than literals and function forms become getters,
  as in Solid's compiled JSX. Without `children`, the object has no children prop."
       [attrs children]
       (let [spread (get attrs :&)
             attrs (dissoc attrs :&)
             entries (cond-> (vec attrs)
                       (= 1 (count children))
                       (conj [:children (first children)])
                       (< 1 (count children))
                       (conj [:children `(cljs.core/array
                                           ~@(core/for [c children]
                                               (if (static-prop? c) c `(fn [] (solid.core/-resolve ~c)))))]))
             getters (remove (fn [[_ v]] (static-prop? v)) entries)
             statics (filter (fn [[_ v]] (static-prop? v)) entries)]
            (cond->> `(solid.core/-props-obj
                        (cljs.core/array ~@(mapcat (fn [[k v]] [(props/prop-name k) `(fn [] (solid.core/-resolve ~v))]) getters))
                        (cljs.core/array ~@(mapcat (fn [[k v]] [(props/prop-name k) v]) statics)))
              spread (list 'solid.core/-merge-spread `(fn [] ~spread)))))

(defn- h-form
  "Returns the form that creates an element or component through Solid's runtime
  hyperscript, `h`."
  [env tag args]
  (if (keyword? tag)
    (if (map? (first args))
      (let [[attrs & children] (sc/compile-attrs args)]
        (lint/lint-element-attrs! tag (first args) env)
        `(create-element ~(name tag) ~attrs ~@(wrap-children children)))
      `(create-element ~(name tag) nil ~@(wrap-children args)))
    (let [[attrs children] (if (map? (first args)) [(first args) (rest args)] [{} args])]
      `(create-element ~tag ~(props-form attrs nil) ~@(wrap-children children)))))

(defn- component-form
  "Returns the form that creates a component with `createComponent`, as Solid's compiled
  JSX does."
  [tag args]
  (let [[attrs children] (if (map? (first args)) [(first args) (rest args)] [{} args])]
    `(solid.core/-component ~tag ~(props-form attrs children))))

(defmacro $
  "Creates an element or a component. Elements with keyword tags compile to templates
  whose static HTML is parsed once and cloned."
  [tag & args]
  (core/cond
    (= tag :<>) `(cljs.core/array ~@(wrap-children args))
    (tpl/compilable? tag args) (tpl/compile-element &env tag args)
    (keyword? tag) (h-form &env tag args)
    :else (component-form tag args)))

(defmacro $h
  "Creates an element or a component like `$`, through Solid's runtime hyperscript
  instead of a compiled template."
  [tag & args]
  (if (= tag :<>)
    `(cljs.core/array ~@(wrap-children args))
    (h-form &env tag args)))

(defmacro if
          ([test then]
           `(solid.core/if ~test ~then nil))
          ([test then else]
           `(-show (fn [] ~test) (fn [] ~then) (fn [] ~else))))

(defmacro when [test then]
          `(solid.core/if ~test ~then))

(defmacro if-let
          "Solid's conditional rendering with binding.
  Binds the test value and renders `then` if truthy, `else` otherwise.
  The bound value is available in the `then` branch.
  
  ```clojure
  (s/if-let [user @current-user]
    ($ :div \"Hello, \" (:name user))
    ($ :div \"Please log in\"))
  ```"
          ([[binding test] then]
           `(solid.core/if-let [~binding ~test] ~then nil))
          ([[binding test] then else]
           `(let [value# (solid.core/memo (fn [] ~test) {:equals =})]
                 (-show-keyed value#
                              (fn [v#]
                                  (let [~binding v#]
                                       ~then))
                              (fn [] ~else)))))

(defmacro when-let
          "Solid's conditional rendering with binding.
  Binds the test value and renders body if truthy.
  
  ```clojure
  (s/when-let [user @current-user]
    ($ :div \"Hello, \" (:name user)))
  ```"
          [[binding test] & body]
          `(solid.core/if-let [~binding ~test]
                              (do ~@body)))

(defmacro if-some
          "Solid's conditional rendering with binding, checking for non-nil (not falsiness).
  Unlike `if-let`, this renders the `then` branch even when value is `false` or `0`.
  
  ```clojure
  (s/if-some [count @item-count]
    ($ :span count \" items\")  ; shows \"0 items\" when count is 0
    ($ :span \"Loading...\"))
  ```"
          ([[binding test] then]
           `(solid.core/if-some [~binding ~test] ~then nil))
          ([[binding test] then else]
   ;; Boxes the value so that `false` counts as present, keyed by the memo
           `(let [value# (solid.core/memo (fn [] ~test) {:equals =})]
                 (-show-keyed (fn []
                                  (let [v# (value#)]
                                       (core/when (some? v#) (cljs.core/array v#))))
                              (fn [box#]
                                  (let [~binding (aget box# 0)]
                                       ~then))
                              (fn [] ~else)))))

(defmacro when-some
          "Solid's conditional rendering with binding, checking for non-nil.
  Unlike `when-let`, this renders even when value is `false` or `0`.
  
  ```clojure
  (s/when-some [count @item-count]
    ($ :span count \" items\"))
  ```"
          [[binding test] & body]
          `(solid.core/if-some [~binding ~test]
                               (do ~@body)))

(defmacro or
          "Solid's fallback rendering. Returns the first truthy value or the last value.
  Useful for providing default values in reactive contexts.
  
  ```clojure
  ($ :h1 (s/or @custom-title \"Default Title\"))
  ```"
          ([] nil)
          ([x] x)
          ([x & next]
           (let [v (gensym "or")]
                `(let [~v ~x]
                      (solid.core/if ~v ~v (solid.core/or ~@next))))))

(defmacro some->
          "Solid's nil-safe threading macro. Threads value through forms, 
  short-circuiting and rendering nil if any step returns nil.
  
  ```clojure
  (s/some-> @user :profile :avatar-url (as-> url ($ :img {:src url})))
  ```"
          [expr & forms]
          (let [g (gensym "some->")
                steps (map (fn [step]
                               `(core/if (nil? ~g)
                                         nil
                                         (-> ~g ~step)))
                           forms)]
               `(let [~g ~expr
                      ~@(interleave (core/repeat g) steps)]
                     ~g)))

(defmacro some->>
          "Solid's nil-safe threading macro. Threads value through forms as last argument,
  short-circuiting and rendering nil if any step returns nil.
  
  ```clojure
  (s/some->> @items (filter :active) seq ($ :ul (s/for [[item] %] ($ :li item))))
  ```"
          [expr & forms]
          (let [g (gensym "some->>")
                steps (map (fn [step]
                               `(core/if (nil? ~g)
                                         nil
                                         (->> ~g ~step)))
                           forms)]
               `(let [~g ~expr
                      ~@(interleave (core/repeat g) steps)]
                     ~g)))

(defn- loop-options
  "Splits the binding vector of `s/for`, `s/index` and `s/repeat` into the binding, the
  collection and an options map."
  [[binding expr & opts]]
  (assert (even? (count opts)) "options after the collection come in pairs, like :fallback x")
  [binding expr (apply hash-map opts)])

(defn- loop-props
  "Returns the getters and statics of a list component's options."
  [{:keys [fallback key]}]
  (core/cond-> {}
    fallback (assoc :fallback `(fn [] ~fallback))
    key (assoc :key key)))

(defmacro for
  "Solid's `For` component in Clojure's `for` syntax. Options follow the collection:
  `:fallback` renders when the collection is empty, and `:key` is a function that returns the
  key of an item. With `:key`, the item is derefable, as in `s/index`.
  ```clojure
  (s/for [[x idx] xs :fallback ($ :li \"none\")]
    ($ :li x))
  ```"
  [bindings body]
  (let [[[v idx] expr opts] (loop-options bindings)
        idx (core/or idx (gensym "_"))
        item (gensym "item")]
    `(-for (fn [] (js/Array.from ~expr))
           (fn [~item idx#]
             (let [~v ~(if (:key opts) `(-wrap ~item) item)
                   ~idx (-wrap idx#)]
               ~body))
           ~(loop-props opts))))

(defmacro index
  "Solid's `For` component without keys, in Clojure's `for` syntax. The item is derefable and
  the index is a number. `:fallback` may follow the collection.
  ```clojure
  (s/index [[x idx] xs]
    ($ :li @x))
  ```"
  [bindings body]
  (let [[[v idx] expr opts] (loop-options bindings)
        idx (core/or idx (gensym "_"))]
    `(-index (fn [] (js/Array.from ~expr))
             (fn [v# ~idx]
               (let [~v (-wrap v#)]
                 ~body))
             ~(loop-props opts))))

(defmacro repeat
  "Solid's `Repeat` component: renders `body` `n` times with `i` bound to the index, without
  diffing a collection. `:fallback` renders when `n` is zero.
  ```clojure
  (s/repeat [i @row-count]
    ($ row {:index i}))
  ```"
  [bindings body]
  (let [[i n opts] (loop-options bindings)]
    `(-repeat (fn [] ~n) (fn [~i] ~body) ~(loop-props opts))))

(defmacro reveal
  "Solid's `Reveal` component, which coordinates when the `s/loading` boundaries among
  `children` reveal their content. `opts` may set `:order` (`:sequential`, `:together` or
  `:natural`) and `:collapsed`.
  ```clojure
  (s/reveal {:order :together}
    (s/loading ($ spinner) ($ profile))
    (s/loading ($ spinner) ($ posts)))
  ```"
  [& args]
  (let [[opts children] (if (map? (first args)) [(first args) (rest args)] [{} args])]
    `(-reveal ~(core/cond-> {}
                 (:order opts) (assoc "order" (name (:order opts)))
                 (contains? opts :collapsed) (assoc "collapsed" (:collapsed opts)))
              ~@(wrap-children children))))

(defmacro cond
          "Solid's `Switch` and `Match` components in Clojure’s `cond` syntax
  ```clojure
  (s/cond
    (= x 1) ($ button {})
    (= y 2) ($ link {})
    :else ($ text {}))
  ```"
          [& pairs]
          (let [else (last pairs)
                marker (last (butlast pairs))]
               (assert (= :else marker) "The fallback condition has to be marked with :else")
               `(-switch (fn [] ~else)
                         ~(->> pairs
                               (drop-last 2)
                               (partition 2)
                               (mapv (fn [[test then]]
                                         [`(fn [] ~test) `(fn [] ~then)]))))))

(defmacro case [value & pairs]
          (let [else (last pairs)
                expr (gensym "test")]
               (assert (odd? (count pairs)) "The fallback condition has to be provided")
               `(let [~expr ~value]
                     (-switch (fn [] ~else)
                              ~(->> pairs
                                    butlast
                                    (partition 2)
                                    (mapv (fn [[test then]]
                                              [`(fn [] (= ~expr ~test)) `(fn [] ~then)])))))))

(defmacro dynamic [expr]
          `(-dynamic (fn [] ~expr)))

(defmacro children [& expr]
          `(-children (fn [] ~@expr)))

(defmacro try
          "Solid's `Errored` component in Clojure’s `try...catch` syntax.
  The binding is either the error, or a vector of the error and a function
  that resets the boundary to retry rendering its children.
  ```clojure
  (s/try
    ($ my-component {})
    (catch err
      ($ error-view {})))

  (s/try
    ($ my-component {})
    (catch [err reset]
      ($ :button {:on-click #(reset)} \"retry\")))
  ```"
          [& exprs]
          (let [[marker binding & body] (last exprs)
                children (butlast exprs)
                [e reset] (if (vector? binding) binding [binding])]
               (assert (= 'catch marker) "should have `catch` marker")
               `(-error-boundary (fn [err# reset#]
                                     (let [~e (err#)
                                           ~(core/or reset (gensym "_")) reset#]
                                          ~@body))
                                 ~@(wrap-children children))))

(defmacro loading
  "Solid's `Loading` component. Renders `fallback` while async reads in `children` are
  pending. Instead of a fallback, the first argument may be a map of `:fallback` and `:on`,
  whose expression the boundary tracks: a change to what it reads shows the fallback again
  while the new content loads.
  ```clojure
  (s/loading ($ spinner)
    ($ profile {:user @user}))

  (s/loading {:fallback ($ spinner) :on @user-id}
    ($ profile {:user @user}))
  ```"
  [fallback & children]
  (let [{:keys [on] :as opts} (if (map? fallback) fallback {:fallback fallback})]
    `(-loading (fn [] ~(:fallback opts))
               ~(core/when (contains? opts :on) `(fn [] ~on))
               ~@(wrap-children children))))
(defmacro batch
          "Runs `body` and applies the signal writes it queued before returning."
          [& body]
          `(solid.core/flush (fn [] ~@body)))

(defmacro reaction [& body]
          (lint/lint-effect-body! 'reaction body &env)
          `(-reaction (fn [] ~@body)))

(defmacro lazy [& body]
          `(-lazy (fn [] ~@body)))

(defmacro pending?
  "Returns true while a change to the value of `body` is in flight, such as an async memo it
  reads refetching after its input changed.
  ```clojure
  ($ :p {:class {:muted (s/pending? (greeting))}} (greeting))
  ```"
  [& body]
  `(solid.core/-pending? (fn [] ~@body)))

(defmacro latest
  "Returns the value of `body` including writes that are still in flight, where a plain read
  returns the committed value."
  [& body]
  `(solid.core/-latest (fn [] ~@body)))

(defmacro resolve
  "Returns a promise of the first settled value of `body`, waiting for the async reads in it."
  [& body]
  `(solid.core/-resolve-async (fn [] ~@body)))
