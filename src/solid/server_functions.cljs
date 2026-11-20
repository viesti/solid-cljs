(ns solid.server-functions
  "Server functions over Solid's server-functions runtime, which resolves to its server or
  browser module by the build's export conditions."
  (:require-macros [solid.server-functions])
  (:require ["@solidjs/web" :as sw]
            ["@solidjs/web/server-functions" :as sf]
            [cljs.reader :as reader]
            [solid.props :as props]))

(defn js-options
  "Converts an options map to a JavaScript object with camelCase keys."
  [opts]
  (let [o #js {}]
    (doseq [[k v] opts] (aset o (props/prop-name k) (if (map? v) (js-options v) v)))
    o))

(def ^boolean -server?
  "True in Solid's server build."
  sw/isServer)

(def ^:private metadata-key
  "The key of a server function's metadata, which `withMeta` and `GET` write."
  (js/Symbol.for "solid.ServerFunctionMetadata"))

(defn- declare-reference
  "Declares the reference `f` as `opts` ask: as a `GET`, and with the metadata `:meta`."
  [f {:keys [method meta]}]
  (cond-> (if (= :get method) (sf/GET f) f)
    meta (sf/withMeta (js-options meta))))

(defn -edn-caller
  "Returns a function that calls the server function reference `reference` with its
  arguments as EDN, and resolves to the EDN result read back. It shares the reference's
  metadata and invocation channel, so Solid's `invoke` and `withMeta` work on it."
  [reference]
  (let [encode #(pr-str (vec %))
        caller (fn [& args]
                 (.then (reference (encode args)) reader/read-string))]
    (unchecked-set caller metadata-key (unchecked-get reference metadata-key))
    (unchecked-set caller sf/SERVER_FUNCTION_INVOKE
                   (fn [args options]
                     (.then ((unchecked-get reference sf/SERVER_FUNCTION_INVOKE) #js [(encode args)] options)
                            reader/read-string)))
    caller))

(defn -reference
  "Returns the browser side of the server function `id`."
  [id {:keys [encoding] :as opts}]
  (let [reference (declare-reference (sf/createServerReference id) opts)]
    (if (= :js encoding) reference (-edn-caller reference))))

(defn- edn-result
  "Returns `result` as EDN. A response from `s/respond` keeps its status and headers, with its
  value as EDN."
  [result]
  (if (sw/isResponseEnvelope result)
    (sw/respond (pr-str (.-value result)) #js {:status (.-status result) :headers (.-headers result)})
    (pr-str result)))

(defn- name-of [id]
  (subs id (inc (.lastIndexOf id "/"))))

(defn- in-process!
  "Gives `f`, which server code calls directly, the metadata of `reference` and an invocation
  channel, so that Solid's `invoke` calls it in process. A `:signal` that aborts rejects the
  call, as it would over HTTP, though `f` runs on."
  [f reference]
  (unchecked-set f metadata-key (unchecked-get reference metadata-key))
  (unchecked-set f sf/SERVER_FUNCTION_INVOKE
                 (fn [args ^js options]
                   (let [^js signal (some-> options .-signal)]
                     (cond
                       (nil? signal) (.apply f nil args)
                       (.-aborted signal) (js/Promise.reject (.-reason signal))
                       :else (js/Promise.race
                              #js [(js/Promise.resolve (.apply f nil args))
                                   (js/Promise. (fn [_ reject]
                                                  (.addEventListener signal "abort" #(reject (.-reason signal))
                                                                     #js {:once true})))])))))
  f)

(defn -register
  "Registers `f` as the server function `id` for `handle-request`, and returns `f`, which
  server code calls directly."
  [id f {:keys [encoding] :as opts}]
  (let [transport (if (= :js encoding)
                    f
                    (fn [edn-args]
                      (-> (js/Promise.resolve (apply f (reader/read-string edn-args)))
                          (.then edn-result))))]
    ;; As a compiled "use server" function does, so that GET can declare it
    (in-process! f (declare-reference (sf/createServerReference (sf/registerServerReference id transport (name-of id)))
                                      opts))))

(defn configure-client
  "Configures how the browser calls server functions: `:base` (the path or URL of the
  handler), `:prepare-request` and other options of Solid's `configureServerFunctionsClient`."
  [opts]
  (sf/configureServerFunctionsClient (js-options opts)))

(defn invoke
  "Calls the server function `f` once with `args` and the per-call options `opts`: `:signal`,
  an `AbortSignal` whose abort rejects the call and cancels its request, `:keepalive` and
  `:priority`. Solid's `invoke`."
  [f opts & args]
  (apply sf/invoke f (js-options opts) args))
