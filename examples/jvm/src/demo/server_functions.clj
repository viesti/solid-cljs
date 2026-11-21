(ns demo.server-functions
  "Answers calls from solid-cljs's `defserverfn` in the browser, speaking the wire protocol of
  Solid's server function runtime (`@solidjs/web` 2.0.3) with EDN arguments and results.

  Supported: calls (`POST <base>/data/<id>`), `{:method :get}` functions
  (`GET <base>/data/<id>?args=...`), errors with a message, and `{:live true}` functions
  (`GET <base>/live/<id>?args=...`, server-sent events). Not supported: server components,
  form posts without JavaScript, single-flight data and encodings other than EDN.

  Solid's client says that the mapping from a call to a request is not contractual, so this
  may break with a Solid release. test/demo/server_functions_test.clj runs Solid's own client
  against it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [demo.live :as live]
            [jsonista.core :as json]
            [ring.util.codec :as codec])
  (:import (java.net URI)
           (java.nio.charset StandardCharsets)))

;; Server function id ("<namespace>/<name>") to {:f var :opts map}
(defonce registry (atom {}))

(defmacro defserverfn
  "Defines a function, as `defn` does, and registers it as the server function
  `\"<namespace>/<name>\"`, the id solid-cljs's `defserverfn` gives the same name in the browser.
  `opts` may set `:method :get` or `:live true`. A live function returns a subscribe function,
  which takes a callback for its values and returns a function that stops it."
  {:arglists '([name opts? [params*] & body])}
  [fn-name & args]
  (let [[opts [params & body]] (if (map? (first args)) [(first args) (rest args)] [{} args])
        id (str (ns-name *ns*) "/" fn-name)]
    (when-not (contains? #{nil :edn} (:encoding opts))
      (throw (ex-info "Only the EDN encoding is supported on the JVM" {:id id :opts opts})))
    `(do (defn ~fn-name ~params ~@body)
         (swap! registry assoc ~id {:f (var ~fn-name) :opts ~opts})
         (var ~fn-name))))

(def ^:dynamic *request*
  "The Ring request of the call being answered."
  nil)

(defn respond
  "Returns `value` as the result of a call, with Ring response keys such as `:headers`,
  `:cookies` or `:status` merged into the response, as `s/respond` does in Node.js."
  [value response]
  {::value value ::response response})

(def ^:private base-headers
  {"cache-control" "no-store"
   "x-content-type-options" "nosniff"})

(defn- result-response
  "Answers with `result` as EDN, in Solid's string format (1)."
  [result]
  (let [[value extra] (if (and (map? result) (contains? result ::value)) [(::value result) (::response result)] [result nil])]
    (-> {:status 200
         :headers (assoc base-headers
                         "content-type" "text/plain;charset=utf-8"
                         "x-server-function-format" "1")
         :body (pr-str value)}
        (merge (dissoc extra :headers))
        (update :headers merge (:headers extra)))))

(defn- error-header-value
  "Encodes `message` as Solid's `encodeErrorHeaderValue` does."
  [message]
  (let [stripped (str/replace (str message) #"[\r\n]+" "")]
    (if (and (not (re-find #"[^\x20-\x7e\xa0-\xff]" stripped))
             (not (str/starts-with? stripped "=?1?"))
             (= stripped (str/trim stripped)))
      stripped
      (str "=?1?" (str/replace (codec/url-encode stripped) "+" "%2B")))))

;; Seroval escapes these in the text of a string node and unescapes them when it reads one
(def ^:private seroval-escapes
  {\" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \backspace "\\b" \tab "\\t"
   \formfeed "\\f" \< "\\x3C" \u2028 "\\u2028" \u2029 "\\u2029"})

(defn- seroval-string
  "Returns `s` escaped as seroval's `serializeString` escapes the text of a string node."
  [s]
  (str/escape s seroval-escapes))

(defn- error-response
  "Answers with a failure carrying `message`: a seroval error node, framed by its length, in
  Solid's serialized format (0), and the message in the error header."
  [status message]
  (let [node (json/write-value-as-string {"t" 13 "i" 0 "s" 0 "m" (seroval-string message) "p" {"k" [] "v" []}})]
    {:status status
     :headers (assoc base-headers
                     "content-type" "text/plain;charset=utf-8"
                     "x-server-function-format" "0"
                     "x-server-function-error" (error-header-value message))
     ;; The frame's length is in bytes of UTF-8
     :body (str ";0x" (format "%08x" (alength (.getBytes ^String node StandardCharsets/UTF_8))) ";" node)}))

;; The first event of a live answer: a seroval node declaring an async iterable (node 4), whose
;; values the following events fill. Copied from Solid's handler, which always sends it.
(def ^:private live-preamble
  (str "data: {\"t\":10,\"i\":0,\"p\":{\"k\":[{\"t\":17,\"i\":1,\"s\":0}],\"v\":[{\"t\":30,\"a\":[{\"t\":29,\"i\":2,\"a\":[{\"t\":26,\"i\":3,\"s\":1},{\"t\":4,\"i\":1}]},{\"t\":31,\"i\":4,\"a\":[],\"f\":{\"t\":26,\"i\":5,\"s\":4}}]}]},\"o\":0}"
       "\n\n"))

(defn- live-event
  "Returns `value` as an event of a live answer: the next value of node 4, a string node
  holding EDN."
  [value]
  (str "data: " (json/write-value-as-string {"t" 32 "i" 4 "f" {"t" 1 "s" (seroval-string (pr-str value))}})
       "\n\n"))

(defn- live-response [subscribe]
  {:status 200
   :headers (assoc base-headers
                   "content-type" "text/event-stream"
                   "x-accel-buffering" "no"
                   "x-server-function-format" "0")
   :body (live/event-stream subscribe live-event live-preamble)})

(defn- same-origin?
  "False when the request carries an `Origin` for another host, as a cross-site request would.
  Solid's handler checks the same, which a JVM handler must do itself."
  [request]
  (if-let [origin (get-in request [:headers "origin"])]
    (= (some-> (URI. origin) .getAuthority) (get-in request [:headers "host"]))
    true))

(defn- arguments
  "Returns the argument vector of a call: the EDN string that is the body of a POST, or the
  first element of the JSON array in the `args` query parameter of a GET."
  [request]
  (let [text (if (= :get (:request-method request))
               (some-> (:query-string request) codec/form-decode (get "args") json/read-value first)
               (slurp (:body request)))]
    (if (str/blank? text) [] (edn/read-string text))))

(defn- answer [request id live?]
  (let [{:keys [f opts]} (get @registry id)
        get? (= :get (:request-method request))]
    (cond
      (not (same-origin? request)) (error-response 403 "Cross-site call refused")
      (nil? f) (error-response 404 (str "No server function " id))
      (not= live? (boolean (:live opts))) (error-response 404 (str "No server function " id))
      (and (not live?) (not= get? (= :get (:method opts)))) (error-response 405 "Method not allowed")
      (and (not get?) (not= "1" (get-in request [:headers "x-server-function-format"])))
      (error-response 415 "Only string bodies (format 1) are supported")
      :else
      (binding [*request* request]
        (let [result (apply f (arguments request))]
          (if live? (live-response result) (result-response result)))))))

(defn- handler [live?]
  (fn [request]
    (let [id (codec/url-decode (get-in request [:path-params :id]))]
      (try
        (answer request id live?)
        (catch clojure.lang.ExceptionInfo e
          (error-response 400 (ex-message e)))
        (catch Exception e
          (.printStackTrace e)
          (error-response 500 "Internal Server Error"))))))

(defn routes
  "Returns reitit routes for the server functions under `base`, \"/_server\" by default, which
  is also the default base of Solid's client."
  ([] (routes "/_server"))
  ([base]
   [base
    ["/data/{*id}" {:get (handler false) :post (handler false)}]
    ["/live/{*id}" {:get (handler true)}]]))
