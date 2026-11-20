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

(def ^:private live-source-key
  "The key that brands a live server function's answer, which memos follow as a live source."
  (js/Symbol.for "solid.LiveSource"))

(def ^:private edn-key
  "The key that marks a function whose arguments travel as one EDN string."
  (js/Symbol.for "solid.cljs.EdnArguments"))

(defn- share-identity!
  "Gives `f` the id, metadata and EDN mark of the reference `reference`, which Solid's helpers
  that take a server function read."
  [f reference edn?]
  (set! (.-id f) (.-id reference))
  (unchecked-set f metadata-key (unchecked-get reference metadata-key))
  (when edn? (unchecked-set f edn-key true))
  f)

(defn- declare-reference
  "Declares the reference `f` as `opts` ask: live, broadcast to every viewer or as a `GET`,
  and with the metadata `:meta`."
  [f {:keys [method live broadcast meta]}]
  (cond-> (cond broadcast (sf/broadcast f)
                live (sf/live f)
                (= :get method) (sf/GET f)
                :else f)
    meta (sf/withMeta (js-options meta))))

(defn- map-values
  "Returns an async iterable of the values of the async iterable `source` passed through `f`.
  It keeps the live source brand of `source` and passes `onstatus`, the hook a live answer
  reports its connection on, through to it."
  [^js source f]
  (let [mapped #js {}]
    (unchecked-set mapped js/Symbol.asyncIterator
                   (fn []
                     (let [^js it (.call (unchecked-get source js/Symbol.asyncIterator) source)]
                       #js {:next (fn []
                                    (.then (.next it)
                                           (fn [^js r] (if (.-done r) r #js {:done false :value (f (.-value r))}))))
                            :return (fn [v]
                                      (if (.-return it)
                                        (.return it v)
                                        (js/Promise.resolve #js {:done true :value v})))})))
    (when (unchecked-get source live-source-key)
      (unchecked-set mapped live-source-key true))
    (js/Object.defineProperty mapped "onstatus"
                              #js {:get (fn [] (.-onstatus source))
                                   :set (fn [h] (set! (.-onstatus source) h))})
    mapped))

(defn -edn-caller
  "Returns a function that calls the server function reference `reference` with its
  arguments as EDN, and resolves to the EDN result read back. It shares the reference's
  metadata and invocation channel, so Solid's `invoke` and `withMeta` work on it."
  [reference]
  (let [encode #(pr-str (vec %))
        caller (fn [& args]
                 (.then (reference (encode args)) reader/read-string))]
    (share-identity! caller reference true)
    (unchecked-set caller sf/SERVER_FUNCTION_INVOKE
                   (fn [args options]
                     (.then ((unchecked-get reference sf/SERVER_FUNCTION_INVOKE) #js [(encode args)] options)
                            reader/read-string)))
    caller))

(defn -edn-live-caller
  "Returns a function that calls the live server function reference `reference` with its
  arguments as EDN, and returns an async iterable of its values read back."
  [reference]
  (let [encode #(pr-str (vec %))
        caller (fn [& args]
                 (map-values (reference (encode args)) reader/read-string))]
    (share-identity! caller reference true)
    (unchecked-set caller sf/SERVER_FUNCTION_INVOKE
                   (fn [args options]
                     (map-values ((unchecked-get reference sf/SERVER_FUNCTION_INVOKE) #js [(encode args)] options)
                                 reader/read-string)))
    caller))

(defn -reference
  "Returns the browser side of the server function `id`."
  [id {:keys [encoding live broadcast] :as opts}]
  (let [reference (declare-reference (sf/createServerReference id) opts)]
    (cond
      (= :js encoding) reference
      (or live broadcast) (-edn-live-caller reference)
      :else (-edn-caller reference))))

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
  [f reference edn?]
  (share-identity! f reference edn?)
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
  [id f {:keys [encoding live broadcast] :as opts}]
  (let [transport (cond
                    (= :js encoding)
                    f

                    (or live broadcast)
                    (fn [edn-args]
                      (-> (js/Promise.resolve (apply f (reader/read-string edn-args)))
                          (.then #(map-values % pr-str))))

                    :else
                    (fn [& args]
                      (if (and (= 1 (count args)) (string? (first args)))
                        (-> (js/Promise.resolve (apply f (reader/read-string (first args))))
                            (.then edn-result))
                        ;; A form post: the arguments bound in its url, then the form's FormData
                        (apply f args))))]
    ;; As a compiled "use server" function does, so that GET can declare it
    (in-process! f
                 (declare-reference (sf/createServerReference (sf/registerServerReference id transport (name-of id)))
                                    opts)
                 (not= :js encoding))))

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

(defn action-url
  "Returns the url a `<form method=\"post\">` posts to for the server function `f`, with
  `bound-args`, which must be JSON-safe, in its query. Without JavaScript the browser posts the
  form there, and the server calls `f` with the bound arguments, as JavaScript values, and then
  the form's `FormData`. The server redirects back to the page, where
  `solid.server-functions.server/flash` reads the outcome."
  [f & bound-args]
  (apply sf/serverFunctionActionUrl f (map clj->js bound-args)))

(defn url
  "Returns the url that a call of the server function `f` with `args` requests, for
  `{:method :get}` functions, such as to preload it with `<link rel=\"preload\" as=\"fetch\">`."
  [f & args]
  (if (unchecked-get f edn-key)
    (sf/serverFunctionUrl f (pr-str (vec args)))
    (apply sf/serverFunctionUrl f args)))
