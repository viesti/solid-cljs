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

(defn- with-method [f {:keys [method]}]
  (if (= :get method) (sf/GET f) f))

(defn -edn-caller
  "Returns a function that calls the server function reference `reference` with its
  arguments as EDN, and resolves to the EDN result read back."
  [reference]
  (fn [& args]
    (.then (reference (pr-str (vec args))) reader/read-string)))

(defn -reference
  "Returns the browser side of the server function `id`."
  [id {:keys [encoding] :as opts}]
  (let [reference (with-method (sf/createServerReference id) opts)]
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
    (with-method (sf/createServerReference (sf/registerServerReference id transport (name-of id))) opts)
    f))

(defn configure-client
  "Configures how the browser calls server functions: `:base` (the path or URL of the
  handler), `:prepare-request` and other options of Solid's `configureServerFunctionsClient`."
  [opts]
  (sf/configureServerFunctionsClient (js-options opts)))
