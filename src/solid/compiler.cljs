(ns solid.compiler)

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

(def unit-less-css-props
  #{"animation-iteration-count" "aspect-ratio" "border-image-outset" "border-image-slice"
    "border-image-width" "box-flex" "box-flex-group" "box-ordinal-group" "column-count"
    "columns" "flex" "flex-grow" "flex-positive" "flex-shrink" "flex-negative" "flex-order"
    "grid-area" "grid-row" "grid-row-end" "grid-row-span" "grid-row-start" "grid-column"
    "grid-column-end" "grid-column-span" "grid-column-start" "font-weight" "line-clamp"
    "line-height" "opacity" "order" "orphans" "tab-size" "widows" "z-index" "zoom"
    "fill-opacity" "flood-opacity" "stop-opacity" "stroke-dasharray" "stroke-dashoffset"
    "stroke-miterlimit" "stroke-opacity" "stroke-width"})

(defn numeric-style
  "Appends `px` to the numeric values of the style object `style` whose
  property takes a length, mutating and returning `style`."
  [^js style]
  (when (object? style)
    (doseq [key (js/Object.keys style)]
      (let [v (aget style key)]
        (when (and (number? v)
                   (not= 0 v)
                   (not (contains? unit-less-css-props key)))
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
