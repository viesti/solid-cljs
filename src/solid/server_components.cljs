(ns solid.server-components
  "Server components over Solid's frames runtime, `@solidjs/web/frames`, which resolves to its
  server or browser module by the build's export conditions."
  (:require-macros [solid.server-components])
  (:require ["@solidjs/web/frames" :as frames]
            ["@solidjs/web/server-functions" :as sf]
            ["solid-js" :as solid]
            [cljs.reader :as reader]))

(defn- declare-call
  "Wraps the reference `f` as `opts` declare it: live, broadcast to every viewer, or as a `GET`."
  [f {:keys [method live broadcast]}]
  (cond broadcast (sf/broadcast f)
        live (sf/live f)
        (= :get method) (sf/GET f)
        :else f))

(defn -edn-arguments
  "Returns a function that calls `f` with its arguments as one EDN string. Both sides call
  server components this way, so a call has the same frame address in the document and in the
  browser."
  [f]
  (fn [& args] (f (pr-str (vec args)))))

(defn -reference
  "Returns the browser side of the server component `id`."
  [id {:keys [encoding] :as opts}]
  (let [reference (declare-call (sf/createServerReference id) opts)]
    (if (= :js encoding) reference (-edn-arguments reference))))

(defn- name-of [id]
  (subs id (inc (.lastIndexOf id "/"))))

(defn -register
  "Registers `f` as the server component `id`, and returns the function that server code calls
  during a render."
  [id f {:keys [encoding] :as opts}]
  (let [transport (if (= :js encoding) f (fn [edn-args] (apply f (reader/read-string edn-args))))
        reference (declare-call (sf/createServerReference (sf/registerServerReference id transport (name-of id))) opts)]
    (if (= :js encoding) reference (-edn-arguments reference))))

(defn -server-component [f]
  (solid/dynamicComponent f))

(defn async-arg
  "Marks a promise or an async iterable passed to a slot, which the slot's component reads as
  its settled value, suspending until the first one arrives."
  [value]
  (frames/asyncArg value))

(defn install!
  "Lets the browser render server components, both those that responses stream and those that
  the document rendered. Call it once in the browser entry, before hydrating."
  []
  (frames/installServerComponents))

(defn configure-server!
  "Answers calls of server components with frame streams, and renders the server components
  that a document render calls into the document. Call it once in the server entry."
  []
  (sf/configureServerFunctionsServer
    #js {:transformResult frames/frameTransformResult
         :transformFlightResult frames/frameTransformFlightResult
         :transformDirectResult frames/frameTransformDirectResult}))

(def plugin
  "The serialization plugin that a document render needs for the server components it renders,
  given to `s/render-to-stream` as `{:plugins [plugin]}`."
  frames/ServerComponentPlugin)
