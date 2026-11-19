(ns solid.core
    (:refer-clojure :exclude [if when for cond case try if-let when-let if-some when-some or some-> some->>])
    (:require [clojure.core :as core]
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

(defmacro defui
          "Defines a Solid UI component. Supports docstrings and metadata.
  
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
                props (first params)
                meta-map (core/cond-> {}
                                      docstring (assoc :doc docstring)
                                      attr-map (merge attr-map))]
    ;; Lint the component body for reactivity issues
               (lint/lint-component-body! sym body &env)
               `(defn ~(with-meta sym meta-map) [props#]
                      (let [~(core/or props (gensym "props")) (solid.core/-props props#)]
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
       (let [entries (cond-> (vec attrs)
                       (= 1 (count children))
                       (conj [:children (first children)])
                       (< 1 (count children))
                       (conj [:children `(cljs.core/array
                                           ~@(core/for [c children]
                                               (if (static-prop? c) c `(fn [] (solid.core/-resolve ~c)))))]))
             getters (remove (fn [[_ v]] (static-prop? v)) entries)
             statics (filter (fn [[_ v]] (static-prop? v)) entries)]
            `(solid.core/-props-obj
               (cljs.core/array ~@(mapcat (fn [[k v]] [(props/prop-name k) `(fn [] (solid.core/-resolve ~v))]) getters))
               (cljs.core/array ~@(mapcat (fn [[k v]] [(props/prop-name k) v]) statics)))))

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
                      ~@(interleave (repeat g) steps)]
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
                      ~@(interleave (repeat g) steps)]
                     ~g)))

(defmacro for
          "Solid's `For` component in Clojure’s `for` syntax
  ```clojure
  (s/for [[x idx] xs]
    ($ :li x))
  ```"
          [[[v idx] expr] body]
          (let [idx (core/or idx (gensym "_"))]
               `(-for (fn [] (js/Array.from ~expr))
                      (fn [~v idx#]
                          (let [~idx (-wrap idx#)]
                               ~body)))))

(defmacro index
          "Solid's `Index` component in Clojure’s `for` syntax
  ```clojure
  (s/index [[x idx] xs]
    ($ :li @x))
  ```"
          [[[v idx] expr] body]
          (let [idx (core/or idx (gensym "_"))]
               `(-index (fn [] (js/Array.from ~expr))
                        (fn [v# ~idx]
                            (let [~v (-wrap v#)]
                                 ~body)))))

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
          "Solid's `Loading` component. Renders `fallback` while async reads in
  `children` are pending.
  ```clojure
  (s/loading ($ spinner)
    ($ profile {:user @user}))
  ```"
          [fallback & children]
          `(-loading (fn [] ~fallback) ~@(wrap-children children)))

(defmacro batch
          "Runs `body` and applies the signal writes it queued before returning."
          [& body]
          `(solid.core/flush (fn [] ~@body)))

(defmacro reaction [& body]
          (lint/lint-effect-body! 'reaction body &env)
          `(-reaction (fn [] ~@body)))

(defmacro lazy [& body]
          `(-lazy (fn [] ~@body)))
