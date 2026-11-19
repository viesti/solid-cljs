(ns solid.template
    "Compiles `$` forms with keyword tags into Solid templates. The static HTML of an
  element tree is parsed once and cloned for each instance, and generated code binds
  the dynamic attributes and children of each clone, as Solid's JSX compiler does."
    (:require [clojure.string :as str]
              [cljs.analyzer :as ana]
              [solid.compiler :as sc]
              [solid.css :as css]
              [solid.lint :as lint]))

(def svg-elements
     #{"altGlyph" "altGlyphDef" "altGlyphItem" "animate" "animateColor" "animateMotion"
       "animateTransform" "circle" "clipPath" "color-profile" "cursor" "defs" "desc" "ellipse"
       "feBlend" "feColorMatrix" "feComponentTransfer" "feComposite" "feConvolveMatrix"
       "feDiffuseLighting" "feDisplacementMap" "feDistantLight" "feDropShadow" "feFlood"
       "feFuncA" "feFuncB" "feFuncG" "feFuncR" "feGaussianBlur" "feImage" "feMerge"
       "feMergeNode" "feMorphology" "feOffset" "fePointLight" "feSpecularLighting"
       "feSpotLight" "feTile" "feTurbulence" "filter" "font" "font-face" "font-face-format"
       "font-face-name" "font-face-src" "font-face-uri" "foreignObject" "g" "glyph" "glyphRef"
       "hkern" "image" "line" "linearGradient" "marker" "mask" "metadata" "missing-glyph"
       "mpath" "path" "pattern" "polygon" "polyline" "radialGradient" "rect" "set" "stop"
       "svg" "switch" "symbol" "text" "textPath" "tref" "tspan" "use" "view" "vkern"})

(def delegated-events
     #{"beforeinput" "click" "dblclick" "contextmenu" "focusin" "focusout" "input" "keydown"
       "keyup" "mousedown" "mousemove" "mouseout" "mouseover" "mouseup" "pointerdown"
       "pointermove" "pointerout" "pointerover" "pointerup" "touchend" "touchmove" "touchstart"})

(def ^:private void-elements
     #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"})

(def ^:private dom-properties
     "Attribute names that are set as DOM properties, to their property names."
     {"value" "value"
      "checked" "checked"
      "selected" "selected"
      "muted" "muted"
      "default-value" "defaultValue"
      "default-checked" "defaultChecked"
      "default-selected" "defaultSelected"
      "default-muted" "defaultMuted"
      "inner-html" "innerHTML"
      "text-content" "textContent"
      "inner-text" "innerText"})

(defn- escape-text [s]
       (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- escape-attr [s]
       (-> s (str/replace "&" "&amp;") (str/replace "\"" "&quot;")))

(defn- parse-tag
       "Splits a tag such as `:li.item#main` into its name, id and classes."
       [tag]
       (let [[tag-name & parts] (re-seq #"[.#]?[^.#]+" (name tag))]
            {:name tag-name
             :id (some #(when (str/starts-with? % "#") (subs % 1)) parts)
             :classes (vec (keep #(when (str/starts-with? % ".") (subs % 1)) parts))}))

(defn- dollar-form?
       "Returns true if `x` is a call of the `solid.core/$` macro."
       [env x]
       (and (seq? x)
            (symbol? (first x))
            (when-let [v (ana/get-expander (first x) env)]
                      (= 'solid.core/$ (symbol (str (:ns (meta v))) (str (:name (meta v))))))))

(defn compilable?
      "Returns true if a `$` form with this tag and args compiles to a template."
      [tag args]
      (and (keyword? tag)
           (not= tag :<>)
           (let [{:keys [id]} (parse-tag tag)
                 attrs (first args)]
                (not (and id (map? attrs) (contains? attrs :id))))))

(defn- event-name
       "Returns the DOM event name of an `:on-*` attribute, or nil."
       [n]
       (cond
        (str/starts-with? n "on-") (str/replace (subs n 3) "-" "")
        (re-matches #"on[A-Z].*" n) (str/lower-case (subs n 2))))

(defn- literal-attr
       "Returns the HTML of an attribute with a literal value, or nil to leave it out."
       [n v]
       (cond
        (true? v) n
        (or (false? v) (nil? v)) nil
        :else (str n "=\"" (escape-attr (if (keyword? v) (name v) (str v))) "\"")))

(defn- style-text
       "Returns the CSS text of a style map whose values are all literals."
       [m]
       (->> m
            (keep (fn [[k v]]
                      (when (some? v)
                            (let [prop (name k)
                                  value (cond
                                         (keyword? v) (name v)
                                         (and (number? v) (not= 0 v) (not (css/unit-less-props prop))) (str v "px")
                                         :else (str v))]
                                 (str prop ":" value)))))
            (str/join ";")))

(defn- class-literal?
       [v]
       (or (sc/literal? v)
           (and (vector? v) (every? sc/literal? v))
           (and (map? v) (every? sc/literal? (vals v)))))

(defn- class-names
       "Returns the class names of a literal class value."
       [v]
       (cond
        (nil? v) []
        (keyword? v) [(name v)]
        (string? v) (remove str/blank? (str/split v #"\s+"))
        (vector? v) (mapcat class-names v)
        (map? v) (for [[k on] v :when on] (if (keyword? k) (name k) (str k)))
        :else [(str v)]))

(defn- compile-attrs
       "Splits an element's attributes into static HTML attributes and operations on the
  element, each a function of the element's symbol."
       [tag-info attrs]
       (let [{:keys [id classes]} tag-info
             class-v (get attrs :class)
             static-class? (or (not (contains? attrs :class)) (class-literal? class-v))]
            (reduce-kv
             (fn [acc k v]
                 (let [n (if (keyword? k) (name k) (str k))]
                      (cond
                       (symbol? k)
                       (update acc :ops conj (fn [el] `(~k ~el (fn [] ~v))))

                       (= k :ref)
                       (update acc :ops conj (fn [el] `(when-let [r# ~v] (r# ~el))))

                       (event-name n)
                       (let [ev (event-name n)]
                            (update acc :ops conj (fn [el] `(solid.core/-event! ~el ~ev ~v ~(contains? delegated-events ev)))))

                       (= k :class)
                       acc

                       (= k :style)
                       (cond
                        (nil? v) acc
                        (string? v) (update acc :html conj (literal-attr "style" v))
                        (and (map? v) (every? sc/literal? (vals v)))
                        (let [text (style-text v)]
                             (cond-> acc (seq text) (update :html conj (literal-attr "style" text))))
                        (map? v)
                        (update acc :ops conj (fn [el]
                                                  `(solid.core/-effect (fn [] (solid.compiler/numeric-style ~(sc/to-js v)))
                                                                       (fn [v# p#] (solid.core/-style! ~el v# p#)))))
                        :else
                        (update acc :ops conj (fn [el]
                                                  `(solid.core/-effect (fn [] (solid.compiler/interpret-style-map ~v))
                                                                       (fn [v# p#] (solid.core/-style! ~el v# p#))))))

                       (dom-properties n)
                       (let [prop (dom-properties n)]
                            (update acc :ops conj
                                    (if (sc/literal? v)
                                        (fn [el] `(solid.core/-prop! ~el ~prop ~v nil))
                                        (fn [el] `(solid.core/-effect (fn [] ~v)
                                                                      (fn [v# p#] (solid.core/-prop! ~el ~prop v# p#)))))))

                       (sc/literal? v)
                       (if-let [html (literal-attr n v)]
                               (update acc :html conj html)
                               acc)

                       :else
                       (update acc :ops conj (fn [el]
                                                 `(solid.core/-effect (fn [] ~v)
                                                                      (fn [v# p#] (solid.core/-attr! ~el ~n v# p#))))))))
             {:html (cond-> []
                            id (conj (literal-attr "id" id))
                            (and static-class? (seq (concat classes (class-names class-v))))
                            (conj (literal-attr "class" (str/join " " (concat classes (class-names class-v))))))
              :ops (cond-> []
                           (not static-class?)
                           (conj (fn [el]
                                     `(solid.core/-effect (fn [] (solid.compiler/class-value
                                                                  ~(if (seq classes) [(str/join " " classes) class-v] class-v)))
                                                          (fn [v# p#] (solid.core/-class! ~el v# p#))))))}
             (dissoc attrs :class))))

(declare parse-element)

(defn- parse-children [env children]
       (let [nodes (keep (fn [c]
                             (cond
                              (or (nil? c) (boolean? c)) nil
                              (string? c) (when (seq c) {:type :text :text c})
                              (number? c) {:type :text :text (str c)}
                              (keyword? c) {:type :text :text (name c)}
                              (and (dollar-form? env c) (compilable? (second c) (nnext c)))
                              (parse-element env (second c) (nnext c))
                              :else {:type :dynamic :expr c}))
                         children)
        ;; Adjacent strings become one text node in the parsed template
             merged (reduce (fn [acc node]
                                (if (and (= :text (:type node)) (= :text (:type (peek acc))))
                                    (conj (pop acc) (update (peek acc) :text str (:text node)))
                                    (conj acc node)))
                            []
                            nodes)]
            (if (and (= 1 (count merged)) (= :dynamic (:type (first merged))))
                [(assoc (first merged) :only? true)]
                merged)))

(defn- parse-element [env tag args]
       (let [tag-info (parse-tag tag)
             [attrs children] (if (map? (first args)) [(first args) (rest args)] [{} args])
             _ (when (map? (first args)) (lint/lint-element-attrs! tag attrs env))
             {:keys [html ops]} (compile-attrs tag-info attrs)]
            {:type :element
             :tag (:name tag-info)
             :attrs html
             :ops ops
             :children (parse-children env children)}))

(defn- node-html [{:keys [type tag attrs children text only?]}]
       (case type
             :text (escape-text text)
             :dynamic (if only? "" "<!>")
             :element (str "<" tag (str/join (map #(str " " %) attrs)) ">"
                           (when-not (void-elements tag)
                                     (str (str/join (map node-html children)) "</" tag ">")))))

(defn- needs-ref? [{:keys [type ops children only?]}]
       (case type
             :text false
             :dynamic (not only?)
             :element (boolean (or (seq ops) (some needs-ref? children) (some :only? children)))))

(defn- emit-node
       "Returns the let bindings and operations that bind the element `node` held in `el`."
       [node el]
       (let [children (:children node)
             last-needed (last (keep-indexed (fn [i c] (when (needs-ref? c) i)) children))
             syms (when last-needed
                        (vec (for [i (range (inc last-needed))]
                                  (gensym (str "n" i "_")))))
             walk (mapcat (fn [i sym]
                              [sym (if (zero? i)
                                       `(.-firstChild ~el)
                                       `(.-nextSibling ~(syms (dec i))))])
                          (range) syms)
             child-results (for [[i c] (map-indexed vector children)]
                                (case (:type c)
                                      :element (when (and syms (< i (count syms)) (needs-ref? c))
                                                     (emit-node c (syms i)))
                                      :dynamic {:ops [(if (:only? c)
                                                          `(solid.core/-insert ~el (fn [] ~(:expr c)))
                                                          `(solid.core/-insert ~el (fn [] ~(:expr c)) ~(syms i)))]}
                                      nil))]
            {:bindings (vec (concat walk (mapcat :bindings child-results)))
             :ops (vec (concat (map #(% el) (:ops node)) (mapcat :ops child-results)))}))

(defn compile-element
      "Returns the form that creates the element of a `$` form with a keyword tag."
      [env tag args]
      (let [node (parse-element env tag args)
            svg? (and (svg-elements (:tag node)) (not= "svg" (:tag node)))
            html (cond->> (node-html node) svg? (format "<svg>%s</svg>"))
            el (gensym "el")
            {:keys [bindings ops]} (emit-node node el)]
           `(solid.core/-compiled
             (fn []
                 (solid.core/untrack
                  (fn []
                      (let [~el ((solid.core/-template ~html ~(when svg? 2)))
                            ~@bindings]
                           ~@ops
                           ~el)))))))
