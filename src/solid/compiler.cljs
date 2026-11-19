(ns solid.compiler
  (:require [solid.css :as css]))

(declare convert-style-prop-value)

(defn- kv-style-conv [o k v]
  (aset o (if (keyword? k) (-name ^not-native k) k) (convert-style-prop-value v))
  o)

(defn- js-val? [x]
  (not (identical? "object" (goog/typeOf x))))

(defn- convert-style-prop-value [x]
  (cond
    (js-val? x) x
    (keyword? x) (-name ^not-native x)
    (map? x) (reduce-kv kv-style-conv #js {} x)
    (coll? x) (clj->js x)
    :else (clj->js x)))

(defn numeric-style
  "Appends `px` to the numeric values of the style object `style` whose
  property takes a length, mutating and returning `style`."
  [^js style]
  (when (object? style)
    (doseq [key (js/Object.keys style)]
      (let [v (aget style key)]
        (when (and (number? v)
                   (not= 0 v)
                   (not (contains? css/unit-less-props key)))
          (aset style key (str v "px"))))))
  style)

(defn interpret-style-map [m]
  (numeric-style (convert-style-prop-value m)))

(defn with-numeric-props [^js props]
  (when (object? props)
    (numeric-style (.-style props))))

(defn class-value
  "Converts a ClojureScript class value into one Solid accepts: a string, an
  object of class names to booleans, or an array of those."
  [x]
  (cond
    (keyword? x) (-name ^not-native x)
    (map? x) (reduce-kv (fn [o k v]
                          (aset o (if (keyword? k) (-name ^not-native k) (str k)) v)
                          o)
                        #js {}
                        x)
    (coll? x) (into-array (map class-value x))
    :else x))
