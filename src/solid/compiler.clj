(ns solid.compiler
  (:require [clojure.string :as str]))

(defn literal?
  "Returns true if the expression is a compile-time literal that cannot be reactive.
  Literals include: strings, numbers, keywords, nil, booleans."
  [expr]
  (or (string? expr)
      (number? expr)
      (keyword? expr)
      (nil? expr)
      (true? expr)
      (false? expr)))

(defn- wrap-reactive
  "Wraps an expression in a function unless it's a literal.
  This ensures fine-grained reactivity in Solid.js - only the specific
  attribute will update when signals change, not the entire element.
  Mirrors the behavior of Solid's JSX compiler."
  [expr]
  (if (literal? expr)
    expr
    `(fn [] ~expr)))

(defn- wrap-reactive-attrs
  "Wraps attribute values in functions for fine-grained reactivity.
  Excludes:
  - Event handlers (on-* attributes) since they are already functions
  - :ref attribute since it's handled specially by compile-directives
  - :style attribute since it has special map handling with its own reactivity
  - :class attribute since it has special map handling with its own reactivity
  - Literal values (strings, numbers, keywords, nil, booleans), except for directives,
    which always receive an accessor"
  [attrs]
  (if (map? attrs)
    (reduce-kv
     (fn [m k v]
       (let [k-name (if (keyword? k) (name k) (str k))]
         (assoc m k
                (cond
                  (symbol? k) `(fn [] ~v)
                  (or (str/starts-with? k-name "on-")
                      (= k :ref)
                      (= k :style)
                      (= k :class))
                  v  ; Don't wrap these - they have special handling
                  :else (wrap-reactive v)))))
     {}
     attrs)
    attrs))

(defmulti to-js
  (fn [x]
    (cond
      (map? x) :map
      (vector? x) :vector
      (keyword? x) :keyword
      :else (class x))))

(defn- to-js-map [m shallow?]
  (cond
    (nil? m) nil
    (empty? m) `(cljs.core/js-obj)
    :else (let [kvs-str (->> (mapv to-js (keys m))
                             (mapv #(-> (str \' % "':~{}")))
                             (interpose ",")
                             (apply str))]
            (vary-meta
             (list* 'js* (str "{" kvs-str "}")
                    (if shallow?
                      (vals m)
                      (mapv to-js (vals m))))
             assoc :tag 'object))))

(defmethod to-js :keyword [x] (name x))

(defmethod to-js :map [m] (to-js-map m false))

(defmethod to-js :vector [xs]
  (apply list 'cljs.core/array (mapv to-js xs)))

(defmethod to-js :default [x] x)

(def ^:private dom-properties
  "Attribute names that Solid sets as DOM properties, which are camel-cased."
  {"inner-html" "innerHTML"
   "text-content" "textContent"
   "inner-text" "innerText"
   "default-value" "defaultValue"
   "default-checked" "defaultChecked"
   "default-selected" "defaultSelected"
   "default-muted" "defaultMuted"})

(defn- camel-case [s]
  (let [[first-word & words] (str/split s #"-")]
    (str/join (cons first-word (map str/capitalize words)))))

(defn- attr-name
  "Camel-cases event handlers such as `:on-click` and DOM properties such as
  `:inner-html`. Other attribute names are kept as written, since Solid 2 sets
  them with `setAttribute`."
  [k]
  (if (keyword? k)
    (let [n (name k)]
      (cond
        (str/starts-with? n "on-") (keyword (camel-case n))
        (dom-properties n) (keyword (dom-properties n))
        :else k))
    k))

(defn- rename-attrs [m]
  (reduce-kv #(assoc %1 (attr-name %2) %3) {} m))

(defn- class-name [x]
  (if (keyword? x) (name x) (str x)))

(defn- compile-class [attrs]
  (if-not (contains? attrs :class)
    attrs
    (let [v (:class attrs)]
      (assoc attrs :class
             (cond
               ;; Map of class -> boolean expression (conditional classes)
               (map? v)
               (let [m (to-js (reduce-kv #(assoc %1 (class-name %2) %3) {} v))]
                 (if (every? literal? (vals v))
                   m
                   `(fn [] ~m)))

               ;; Vector of class names: [:foo :bar "baz"] -> "foo bar baz"
               (vector? v)
               (if (every? literal? v)
                 (str/join " " (map class-name (remove nil? v)))
                 `(fn [] (solid.compiler/class-value ~v)))

               (keyword? v) (name v)
               (literal? v) v
               :else `(fn [] (solid.compiler/class-value ~v)))))))

(defn- compile-style [attrs]
  (if-not (contains? attrs :style)
    attrs
    (let [v (:style attrs)]
      (assoc attrs :style
             (cond
               ;; Numeric values of a static map get units in create-element
               (map? v)
               (if (every? literal? (vals v))
                 (to-js v)
                 `(fn [] (solid.compiler/numeric-style ~(to-js v))))

               (literal? v) v
               :else `(fn [] (solid.compiler/interpret-style-map ~v)))))))

(defn- compile-directives [attrs]
  (let [ref (:ref attrs)
        directives (filterv (fn [[k _]] (symbol? k)) attrs)]
    (if (and (nil? ref) (empty? directives))
      attrs
      (-> (into {} (filter (fn [[k _]] (not (symbol? k))) attrs))
          (assoc :ref `(fn [el#]
                         (do
                           (solid.core/-ref! el# ~ref)
                           (doseq [[f# v#] ~directives]
                             (f# el# v#)))))))))

(defn compile-attrs [args]
  (let [[attrs & children] args]
    (if (map? attrs)
      (let [attrs (-> attrs
                      wrap-reactive-attrs
                      compile-style
                      compile-directives
                      compile-class
                      rename-attrs
                      to-js)]
        (into [attrs] children))
      args)))
