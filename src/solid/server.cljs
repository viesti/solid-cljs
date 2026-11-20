(ns solid.server
  "Server-only utilities: the request scope, which runs on Node.js's AsyncLocalStorage, page
  responses, and HTML from hiccup for the document around a page."
  (:require ["@solidjs/web" :as sw]
            ["@solidjs/web/storage" :as storage]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [solid.core :as s]))

(defn create-request-event
  "Returns Solid's request event for the fetch `Request` `request`: the request, a `locals`
  object and a response head."
  [request]
  (sw/createRequestEvent request))

(defn provide-request-event
  "Runs `f` with `event` as the request event, which `s/request-event` returns in the code
  that `f` runs, also across async steps. A server wraps each request's rendering in it."
  [event f]
  (storage/provideRequestEvent event f))

;; HTML from hiccup

(deftype RawHtml [html])

(defn raw
  "Marks the string `html` for `html` to insert as it is, without escaping, such as the
  output of `s/hydration-script`."
  [html]
  (RawHtml. html))

(def ^:private void-elements
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"})

(defn- escape [x]
  (-> (str x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- parse-tag
  "Returns the tag name, id and classes of a hiccup tag such as `:div#root.app`."
  [tag]
  (let [[_ tag-name id classes] (re-matches #"([^#.]+)(?:#([^.]+))?((?:\.[^.#]+)*)" (name tag))]
    [tag-name id (when (seq classes) (str/split (subs classes 1) #"\."))]))

(defn- attributes
  "Returns the HTML attributes of an element, the id and classes of its tag first."
  [id classes attrs]
  (let [id (or id (:id attrs))
        class (str/join " " (cond-> (vec classes) (:class attrs) (conj (:class attrs))))
        attrs (concat (when id [[:id id]])
                      (when (seq class) [[:class class]])
                      (dissoc attrs :id :class))]
    (apply str (for [[k v] attrs
                     :when (and (some? v) (not (false? v)))]
                 (if (true? v)
                   (str " " (name k))
                   (str " " (name k) "=\"" (escape (if (keyword? v) (name v) v)) "\""))))))

(defn- html* [x]
  (cond
    (nil? x) ""
    (instance? RawHtml x) (.-html x)
    (vector? x) (let [[tag & more] x
                      [tag-name id classes] (parse-tag tag)
                      [attrs children] (if (map? (first more)) [(first more) (rest more)] [nil more])]
                  (str "<" tag-name (attributes id classes attrs) ">"
                       (when-not (void-elements tag-name)
                         (str (apply str (map html* children)) "</" tag-name ">"))))
    (seq? x) (apply str (map html* x))
    (keyword? x) (escape (name x))
    :else (escape x)))

(defn html
  "Returns the HTML of the hiccup form `x`: vectors of a tag keyword (with optional `#id` and
  `.class` parts), an optional attribute map and children, with strings escaped, `raw` strings
  inserted as they are and seqs spliced in. An `:html` element starts with a doctype."
  [x]
  (str (when (and (vector? x) (= "html" (first (parse-tag (first x))))) "<!doctype html>")
       (html* x)))

;; Page responses

(def ^:private content-marker "\u0000solid-content\u0000")

(defn- document-parts
  "Returns the HTML of the hiccup `document` before and after its `:solid/content`."
  [document]
  (if document
    (let [page (html (walk/postwalk #(if (= :solid/content %) (raw content-marker) %) document))
          i (.indexOf page content-marker)]
      (when (neg? i)
        (throw (ex-info "The document has no :solid/content placeholder" {:document document})))
      [(subs page 0 i) (subs page (+ i (count content-marker)))])
    ["" ""]))

(defn- within
  "Returns a render stream that writes `before` ahead of the render's HTML and `after` once it
  ends. The writable it is piped to stays the one the render sees, so that what Solid keeps on
  it reaches the response."
  [^js stream before after]
  #js {:pipe (fn [^js writable]
               (let [started (atom false)
                     start! (fn [^js target]
                              (when-not @started
                                (reset! started true)
                                (.write target before)))]
                 (.pipe stream
                        (js/Proxy. writable
                                   #js {:get (fn [^js target k]
                                               (case k
                                                 "write" (fn [chunk] (start! target) (.write target chunk))
                                                 "end" (fn [] (start! target) (.write target after) (.end target))
                                                 (js/Reflect.get target k)))}))))})

(defn render-to-response
  "Renders `root` for the fetch `Request` `request` and returns a promise of a streamed
  `Response`, with the status and headers that the render set with `s/http-status` and
  `s/http-header`. A `Location` header set before the first HTML is sent makes the response a
  redirect. The render runs with the request event in scope.

  `opts` takes the options of `s/render-to-stream`, and `:document`, a hiccup form of the page
  around the render with `:solid/content` where the render goes. `:response-init` is a map of
  the status and headers to start from, which the render's override."
  ([request root] (render-to-response request root nil))
  ([request root {:keys [document response-init nonce] :as opts}]
   (let [event (create-request-event request)
         [before after] (document-parts document)]
     (provide-request-event
      event
      (fn []
        (sw/createSSRResponse
         (within (s/render-to-stream root (dissoc opts :document :response-init)) before after)
         event
         (let [o #js {}]
           (when response-init
             (set! (.-responseInit o) (clj->js response-init)))
           (when nonce
             (set! (.-nonce o) nonce))
           o)))))))
