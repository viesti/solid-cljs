(ns solid.core
    (:refer-clojure :exclude [if when for cond case try if-let when-let if-some when-some or some-> some->>])
    (:require [clojure.core :as core]
              [solid.compiler :as sc]
              [solid.lint :as lint]))

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

(defn- wrap-component-props
       "Wraps component prop values in reactive-prop for fine-grained reactivity.
  Does not wrap:
  - Literals (strings, numbers, keywords, nil, booleans)
  - Function forms (fn, fn*/#())"
       [props]
       (if (map? props)
           (reduce-kv
            (fn [m k v]
                (assoc m k
                       (core/cond
                 ;; Literals don't need wrapping
                        (sc/literal? v) v
                 ;; Function forms are callbacks, don't wrap
                        (fn-form? v) v
                 ;; Everything else gets wrapped in reactive-prop
                        :else `(solid.core/reactive-prop (fn [] ~v)))))
            {}
            props)
           props))

(defmacro $ [tag & args]
          (if (keyword? tag)
              (if (= tag :<>)
                  `(cljs.core/array ~@(wrap-children args))
                  (if (map? (first args))
                      (let [[attrs & children] (sc/compile-attrs args)]
                           (lint/lint-element-attrs! tag (first args) &env)
                           `(create-element ~(name tag) ~attrs ~@(wrap-children children)))
                      `(create-element ~(name tag) nil ~@(wrap-children args))))
              (let [[attrs & children] args]
                   (if (map? attrs)
                       `(create-element ~tag (cljs.core/js-obj "props" ~(wrap-component-props attrs)) ~@(wrap-children children))
                       `(create-element ~tag ~@(wrap-children args))))))

(defmacro if
          ([test then]
           `(solid.core/if ~test ~then nil))
          ([test then else]
           `(-show (fn [] ~test) ~then ~else)))

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
                              ~else))))

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
                              ~else))))

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
               `(-switch ~else
                         ~(->> pairs
                               (drop-last 2)
                               (partition 2)
                               (mapv (fn [[test then]]
                                         [`(fn [] ~test) then]))))))

(defmacro case [value & pairs]
          (let [else (last pairs)
                expr (gensym "test")]
               (assert (odd? (count pairs)) "The fallback condition has to be provided")
               `(let [~expr ~value]
                     (-switch ~else
                              ~(->> pairs
                                    butlast
                                    (partition 2)
                                    (mapv (fn [[test then]]
                                              [`(fn [] (= ~expr ~test)) then])))))))

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
          `(-loading ~fallback ~@(wrap-children children)))

(defmacro batch
          "Runs `body` and applies the signal writes it queued before returning."
          [& body]
          `(solid.core/flush (fn [] ~@body)))

(defmacro reaction [& body]
          (lint/lint-effect-body! 'reaction body &env)
          `(-reaction (fn [] ~@body)))

(defmacro lazy [& body]
          `(-lazy (fn [] ~@body)))
