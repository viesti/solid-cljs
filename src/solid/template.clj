(ns solid.template
    "Compiles `$` forms with keyword tags into Solid templates. The static HTML of an
  element tree is parsed once and cloned for each instance, and generated code binds
  the dynamic attributes and children of each clone, as Solid's JSX compiler does."
    (:require [clojure.string :as str]
              [cljs.analyzer :as ana]
              [cljs.env]
              [solid.compiler :as sc]
              [solid.css :as css]
              [solid.lint :as lint]
              [solid.props :as props]))

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

(def ^:private dom-properties props/dom-properties)

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
  "Returns true if a `$` form with this tag compiles to a template."
  [tag _args]
  (and (keyword? tag) (not= tag :<>)))

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

(def ^:private server-attributes
  "Attribute names under which DOM properties are rendered as HTML on the server."
  {"value" "value" "checked" "checked" "selected" "selected" "muted" "muted"
   "defaultValue" "value" "defaultChecked" "checked" "defaultSelected" "selected"
   "defaultMuted" "muted"})

(defn- compile-attrs
  "Splits an element's attributes into static HTML attributes, operations on the element for
  the client (each a function of the element's symbol), forms that render attributes on the
  server, and content that replaces the element's children on the server."
  [tag-info attrs]
  (let [{:keys [id classes]} tag-info
        class-v (get attrs :class)
        static-class? (or (not (contains? attrs :class)) (class-literal? class-v))
        dynamic-class (if (seq classes) [(str/join " " classes) class-v] class-v)]
    (reduce-kv
      (fn [acc k v]
        (let [n (if (keyword? k) (name k) (str k))]
          (cond
            (symbol? k)
            (update acc :ops conj (fn [el] `(~k ~el (fn [] ~v))))

            (= k :ref)
            (update acc :ops conj (fn [el] `(solid.core/-ref! ~el ~v)))

            (event-name n)
            (let [ev (event-name n)]
              (update acc :ops conj (fn [el] `(solid.core/-event! ~el ~ev ~v ~(contains? delegated-events ev)))))

            (#{:class :id} k)
            acc

            (= k :&)
            (let [explicit (set (keep #(when (keyword? %) (props/attr-name (name %))) (keys attrs)))]
              (-> acc
                  (update :ops conj (fn [el]
                                      `(solid.core/-spread! ~el (fn [] (solid.core/-element-attrs ~v ~explicit)))))
                  (update :ssr conj `(solid.core/-ssr-spread ~v ~explicit))))

            (= k :style)
            (cond
              (nil? v) acc
              (string? v) (update acc :html conj (literal-attr "style" v))
              (and (map? v) (every? sc/literal? (vals v)))
              (let [text (style-text v)]
                (cond-> acc (seq text) (update :html conj (literal-attr "style" text))))
              :else
              (let [compute (if (map? v)
                              `(solid.compiler/numeric-style ~(sc/to-js v))
                              `(solid.compiler/interpret-style-map ~v))]
                (-> acc
                    (update :ops conj (fn [el]
                                        `(solid.core/-effect (fn [] ~compute)
                                                             (fn [v# p#] (solid.core/-style! ~el v# p#)))))
                    (update :ssr conj `(solid.core/-ssr-style ~compute)))))

            (dom-properties n)
            (let [prop (dom-properties n)
                  acc (update acc :ops conj
                              (if (sc/literal? v)
                                (fn [el] `(solid.core/-prop! ~el ~prop ~v nil))
                                (fn [el] `(solid.core/-effect (fn [] ~v)
                                                              (fn [v# p#] (solid.core/-prop! ~el ~prop v# p#))))))]
              (case prop
                "innerHTML" (assoc acc :content {:raw? true :expr v})
                ("textContent" "innerText") (assoc acc :content {:raw? false :expr v})
                (let [attr (server-attributes prop)]
                  (if (sc/literal? v)
                    (cond-> acc (literal-attr attr v) (update :ssr-html conj (literal-attr attr v)))
                    (update acc :ssr conj `(solid.core/-ssr-attr ~attr ~v))))))

            (sc/literal? v)
            (if-let [html (literal-attr n v)]
              (update acc :html conj html)
              acc)

            :else
            (-> acc
                (update :ops conj (fn [el]
                                    `(solid.core/-effect (fn [] ~v)
                                                         (fn [v# p#] (solid.core/-attr! ~el ~n v# p#)))))
                (update :ssr conj `(solid.core/-ssr-attr ~n ~v))))))
      {:html (cond-> []
               (contains? attrs :id) (cond-> (sc/literal? (:id attrs)) (conj (literal-attr "id" (:id attrs))))
               (and id (not (contains? attrs :id))) (conj (literal-attr "id" id))
               (and static-class? (seq (concat classes (class-names class-v))))
               (conj (literal-attr "class" (str/join " " (concat classes (class-names class-v))))))
       :ssr-html []
       :ssr (cond-> []
              (and (contains? attrs :id) (not (sc/literal? (:id attrs))))
              (conj `(solid.core/-ssr-attr "id" ~(:id attrs)))
              (not static-class?)
              (conj `(solid.core/-ssr-class ~dynamic-class)))
       :ops (cond-> []
              (and (contains? attrs :id) (not (sc/literal? (:id attrs))))
              (conj (fn [el]
                      `(solid.core/-effect (fn [] ~(:id attrs))
                                           (fn [v# p#] (solid.core/-attr! ~el "id" v# p#)))))
              (not static-class?)
              (conj (fn [el]
                      `(solid.core/-effect (fn [] (solid.compiler/class-value ~dynamic-class))
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
                        :else {:type :dynamic
                               :expr c
                               :component? (boolean (and (dollar-form? env c) (not (keyword? (second c)))))}))
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

(def ^:private claimed-attributes
  "The attribute whose element a router claims, by tag: links and forms."
  {"a" :href "form" :action})

(defn- claim-attrs
  "Adds the claim of an anchor or a form to its compiled attributes, as Solid's compiler does,
  so that a router can mark active links and intercept navigation. The client claims the
  element once it exists, and again with its owner whenever a dynamic `href` or `action`
  changes. On the server, an anchor renders the attributes the router's link claim adds."
  [tag attrs compiled]
  (if-let [k (claimed-attributes tag)]
    (let [v (get attrs k)
          dynamic? (and (contains? attrs k) (not (sc/literal? v)))]
      (cond-> compiled
        (not dynamic?)
        (update :ops conj (fn [el] `(solid.core/-claim! ~el nil)))
        dynamic?
        (update :ops conj (fn [el]
                            `(let [owner# (solid.core/get-owner)]
                               (solid.core/-effect (fn [] ~v)
                                                   (fn [_# _#] (solid.core/-claim! ~el owner#))))))
        (= "a" tag)
        (update :ssr conj `(solid.core/-ssr-link-claim ~v ~(get attrs :aria-current)))))
    compiled))

(defn- parse-element [env tag args]
  (let [tag-info (parse-tag tag)
        [attrs children] (if (map? (first args)) [(first args) (rest args)] [{} args])
        _ (when (map? (first args)) (lint/lint-element-attrs! tag attrs env))
        {:keys [html ops ssr ssr-html content]} (claim-attrs (:name tag-info) attrs (compile-attrs tag-info attrs))]
    {:type :element
     :tag (:name tag-info)
     :attrs html
     :ops ops
     :ssr ssr
     :ssr-attrs ssr-html
     :content content
     :children (parse-children env children)}))

(defn- node-html
  "Returns the client template HTML of `node`. A dynamic child is a `<!>` placeholder, or a
  pair of `$` and `/` comments around the server HTML it hydrates."
  [{:keys [type tag attrs children text only?]} hydratable?]
  (case type
    :text (escape-text text)
    :dynamic (cond only? "" hydratable? "<!--$--><!--/-->" :else "<!>")
    :element (str "<" tag (str/join (map #(str " " %) attrs)) ">"
                  (when-not (void-elements tag)
                    (str (str/join (map #(node-html % hydratable?) children)) "</" tag ">")))))

(defn- needs-ref? [{:keys [type ops children only?]}]
  (case type
    :text false
    :dynamic (not only?)
    :element (boolean (or (seq ops) (some needs-ref? children) (some :only? children)))))

(defn- form-head? [sym form]
  (and (seq? form) (= sym (first form))))

(defn- effect-form?
  "Returns true if an operation on an element creates an effect."
  [form]
  (or (form-head? 'solid.core/-effect form)
      (form-head? 'solid.core/-spread! form)
      (and (seq? form) (= 'clojure.core/let (first form))
           (some (partial form-head? 'solid.core/-effect) (drop 2 form)))))

(defn- insert-form
  "Returns the form that inserts the dynamic child `c` into `el`. A component is created
  now, as Solid's compiled JSX does, and other children are inserted through an accessor."
  [el c marker current hydratable?]
  (let [value (cond
                (:component? c) `(solid.core/-resolve ~(:expr c))
                hydratable? `(solid.core/-scope (fn [] (solid.core/-child ~(:expr c))))
                :else `(fn [] (solid.core/-child ~(:expr c))))]
    (cond
      current `(solid.core/-insert ~el ~value ~marker ~current)
      marker `(solid.core/-insert ~el ~value ~marker)
      :else `(solid.core/-insert ~el ~value))))

(defn- emit-node
  "Returns the let bindings that walk to the nodes of `node`, held in `el`, and the operations
  on them, split into `:ops` (events, refs and directives), `:inserts` and `:effects`. The
  client runs them in that order, as Solid's compiled JSX does, so that hydration creates
  reactive owners in the order the server did."
  [node el hydratable?]
  (let [children (vec (:children node))
        last-needed (last (keep-indexed (fn [i c] (when (needs-ref? c) i)) children))
        step (fn [prev] (if prev `(.-nextSibling ~prev) `(.-firstChild ~el)))
        walked (loop [i 0 prev nil acc {:bindings [] :ops [] :inserts [] :effects []}]
                 (if (or (nil? last-needed) (> i last-needed))
                   acc
                   (let [c (children i)]
                     (cond
                       (and (= :dynamic (:type c)) hydratable?)
                       (let [start (gensym "start") marker (gensym "marker") current (gensym "current")]
                         (recur (inc i) marker
                                (-> acc
                                    (update :bindings into [start (step prev)
                                                            [marker current] `(solid.core/-next-marker (.-nextSibling ~start))])
                                    (update :inserts conj (insert-form el c marker current true)))))

                       (= :dynamic (:type c))
                       (let [marker (gensym "marker")]
                         (recur (inc i) marker
                                (-> acc
                                    (update :bindings into [marker (step prev)])
                                    (update :inserts conj (insert-form el c marker nil false)))))

                       :else
                       (let [sym (gensym (str "n" i "_"))
                             acc (update acc :bindings into [sym (step prev)])]
                         (recur (inc i) sym
                                (if (and (= :element (:type c)) (needs-ref? c))
                                  (merge-with into acc (emit-node c sym hydratable?))
                                  acc)))))))
        only (some #(when (:only? %) (insert-form el % nil nil hydratable?)) children)
        own (map #(% el) (:ops node))]
    (-> walked
        (update :ops #(into (vec (remove effect-form? own)) %))
        (update :effects #(into (vec (filter effect-form? own)) %))
        (update :inserts #(cond->> % only (into [only]))))))

(defn- server-child [{:keys [expr component? only?]} hydratable?]
  (let [value (cond
                component? `(solid.core/-ssr-child ~expr)
                hydratable? `(solid.core/-scope (fn [] (solid.core/-ssr-child ~expr)))
                :else `(fn [] (solid.core/-ssr-child ~expr)))]
    (if (and hydratable? (not only?))
      ["<!--$-->" value "<!--/-->"]
      [value])))

(defn- server-segments
  "Returns the server HTML of `node` as a sequence of strings and forms whose values are
  rendered between them. The root of a hydratable template carries a hydration key."
  [{:keys [type tag attrs ssr ssr-attrs content children text] :as node} hydratable? root?]
  (case type
    :text [(escape-text text)]
    :dynamic (server-child node hydratable?)
    :element
    (concat
      [(str "<" tag)]
      (when (and hydratable? root?) [`(solid.core/-ssr-key)])
      [(str/join (map #(str " " %) (concat attrs ssr-attrs)))]
      ssr
      [">"]
      (when-not (void-elements tag)
        (concat
          (cond
            (nil? content) (mapcat #(server-segments % hydratable? false) children)
            (string? (:expr content)) [(if (:raw? content) (:expr content) (escape-text (:expr content)))]
            (:raw? content) [`(fn [] (solid.core/-ssr-raw ~(:expr content)))]
            :else [`(fn [] (solid.core/-ssr-child ~(:expr content)))])
          [(str "</" tag ">")])))))

(defn- server-form [node hydratable?]
  (let [[parts values] (reduce (fn [[parts values] seg]
                                 (if (string? seg)
                                   [(conj (pop parts) (str (peek parts) seg)) values]
                                   [(conj parts "") (conj values seg)]))
                               [[""] []]
                               (server-segments node hydratable? true))]
    `(solid.core/-compiled
       (fn [] (solid.core/-ssr (cljs.core/array ~@parts) ~@values)))))

(defn- client-form [node hydratable?]
  (let [svg? (and (svg-elements (:tag node)) (not= "svg" (:tag node)))
        html (cond->> (node-html node hydratable?) svg? (format "<svg>%s</svg>"))
        template `(solid.core/-template ~html ~(when svg? 2))
        el (gensym "el")
        {:keys [bindings ops inserts effects]} (emit-node node el hydratable?)
        events? (some (partial form-head? 'solid.core/-event!) ops)]
    `(solid.core/-compiled
       (fn []
         (solid.core/untrack
           (fn []
             (let [~el ~(if hydratable? `(solid.core/-next-element ~template) `(~template))
                   ~@bindings]
               ~@ops
               ~@inserts
               ~@effects
               ~@(when (and hydratable? events?) [`(solid.core/-run-hydration-events)])
               ~el)))))))

(defn render-target
  "Returns the output the build asks for with the compiler option `:solid/render`: `:dom` for
  the browser, `:server` for server rendering, or `:both` (the default), which chooses at
  runtime with Solid's `isServer`."
  []
  (get-in @cljs.env/*compiler* [:options :solid/render] :both))

(defn hydratable?
  "Returns true if the build sets the compiler option `:solid/hydratable`, which compiles
  server HTML with hydration keys and markers, and client code that hydrates it."
  []
  (boolean (get-in @cljs.env/*compiler* [:options :solid/hydratable])))

(defn compile-element
  "Returns the form that creates the element of a `$` form with a keyword tag."
  [env tag args]
  (let [node (parse-element env tag args)
        hy? (hydratable?)]
    (case (render-target)
      :dom (client-form node hy?)
      :server (server-form node hy?)
      `(if solid.core/server? ~(server-form node hy?) ~(client-form node hy?)))))

(defn server-element
  "Returns the form that renders the element of a `$` form with a keyword tag to server HTML."
  [env tag args]
  (server-form (parse-element env tag args) (hydratable?)))
