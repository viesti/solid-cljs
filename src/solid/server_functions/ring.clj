(ns solid.server-functions.ring
  "Answers `defserverfn` calls from the browser on a Clojure server, in the wire protocol of
  Solid's server function runtime (`@solidjs/web` 2.0.3). A .cljc file defines each function
  once: solid-cljs's `defserverfn` compiles to a reference in the browser and, on the JVM, to a
  function registered here. `handler` answers the registered functions in a Ring app.

  Supported: calls (`POST <base>/data/<id>`), `{:method :get}` functions
  (`GET <base>/data/<id>?args=...`), failures with a message, `{:live true}` functions
  (`GET <base>/live/<id>?args=...`, as server-sent events), EDN or a custom `{:encode :decode}`
  encoding, and response keys added with `respond`.

  Not supported: server components, form posts without JavaScript, single-flight data,
  `{:encoding :js}` (Solid's own serializer), `:broadcast` (a live function here is already
  whatever its subscribe function shares), and resuming a live stream from `Last-Event-ID`.

  Solid documents the mapping from a call to a request as not contractual. A Solid release may
  change it, and this namespace then breaks until solid-cljs follows. Pin Solid's version in
  the app.

  Needs ring-core on the classpath, for streaming live responses."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [ring.core.protocols :refer [StreamableResponseBody]])
  (:import (java.io IOException OutputStream)
           (java.net URI URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

;; Registry

;; Server function id ("<namespace>/<name>") to {:f var :opts map}
(defonce ^:private registry (atom {}))

(defn -register!
  "Registers `f` as the server function `id`. Called by `defserverfn` on the JVM."
  [id f opts]
  (when (= :js (:encoding opts))
    (throw (ex-info (str "Server function " id ": {:encoding :js} uses Solid's own serializer, "
                         "which the JVM does not implement")
                    {:id id :opts opts})))
  (swap! registry assoc id {:f f :opts opts})
  nil)

(defn registered
  "Returns the ids of the registered server functions."
  []
  (set (keys @registry)))

;; Encodings

(def edn
  "The default encoding: EDN, as the browser side uses unless told otherwise. `#inst` reads as
  `java.util.Date`, which prints as `#inst` again."
  {:encode pr-str
   :decode edn/read-string})

(defonce ^:private default-encoding (atom edn))

(defn set-default-encoding!
  "Sets the encoding of every function that does not name one, `{:encode f :decode g}`, where
  `encode` turns a value into a string and `decode` reverses it. It must match the browser's
  `solid.server-functions/set-default-encoding!`."
  [encoding]
  (reset! default-encoding encoding))

(defn- codec [encoding]
  (cond
    (nil? encoding) @default-encoding
    (= :edn encoding) edn
    (map? encoding) encoding
    :else (throw (ex-info "Unknown encoding" {:encoding encoding}))))

;; Calls

(def ^:dynamic *request*
  "The Ring request of the call being answered. Not bound while a live function streams."
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
  "Answers with `result` encoded, in Solid's string format (1)."
  [encode result]
  (let [[value extra] (if (and (map? result) (contains? result ::value))
                        [(::value result) (::response result)]
                        [result nil])]
    (-> {:status 200
         :headers (assoc base-headers
                         "content-type" "text/plain;charset=utf-8"
                         "x-server-function-format" "1")
         :body (encode value)}
        (merge (dissoc extra :headers))
        (update :headers merge (:headers extra)))))

;; Solid's serialized format (seroval)

(defn- json-string
  "Returns `s` as a JSON string literal."
  [s]
  (str \" (str/escape s (fn [c]
                          (cond
                            (= c \") "\\\""
                            (= c \\) "\\\\"
                            (< (int c) 0x20) (format "\\u%04x" (int c)))))
       \"))

;; Seroval escapes these in the text of a string node and unescapes them when it reads one
(def ^:private seroval-escapes
  {\" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \backspace "\\b" \tab "\\t"
   \formfeed "\\f" \< "\\x3C"   "\\u2028"   "\\u2029"})

(defn- seroval-string
  "Returns `s` as the JSON text of a seroval string node's value."
  [s]
  (json-string (str/escape s seroval-escapes)))

(defn- error-header-value
  "Encodes `message` as Solid's `encodeErrorHeaderValue` does."
  [message]
  (let [stripped (str/replace (str message) #"[\r\n]+" "")]
    (if (and (not (re-find #"[^\x20-\x7e\xa0-\xff]" stripped))
             (not (str/starts-with? stripped "=?1?"))
             (= stripped (str/trim stripped)))
      stripped
      (str "=?1?" (-> (java.net.URLEncoder/encode stripped StandardCharsets/UTF_8)
                      (str/replace "+" "%20"))))))

(defn- error-response
  "Answers with a failure carrying `message`: a seroval error node, framed by its length in
  bytes, in Solid's serialized format (0), and the message in the error header."
  [status message]
  (let [node (str "{\"t\":13,\"i\":0,\"s\":0,\"m\":" (seroval-string message) ",\"p\":{\"k\":[],\"v\":[]}}")]
    {:status status
     :headers (assoc base-headers
                     "content-type" "text/plain;charset=utf-8"
                     "x-server-function-format" "0"
                     "x-server-function-error" (error-header-value message))
     :body (str ";0x" (format "%08x" (alength (.getBytes node StandardCharsets/UTF_8))) ";" node)}))

;; Live functions

;; The first event of a live answer: a seroval node declaring an async iterable (node 4), whose
;; values the following events fill. Copied from Solid's handler, which always sends it.
(def ^:private live-preamble
  (str "data: {\"t\":10,\"i\":0,\"p\":{\"k\":[{\"t\":17,\"i\":1,\"s\":0}],\"v\":[{\"t\":30,\"a\":[{\"t\":29,\"i\":2,\"a\":[{\"t\":26,\"i\":3,\"s\":1},{\"t\":4,\"i\":1}]},{\"t\":31,\"i\":4,\"a\":[],\"f\":{\"t\":26,\"i\":5,\"s\":4}}]}]},\"o\":0}"
       "\n\n"))

(defn- live-event
  "Returns the encoded value `s` as an event of a live answer: the next value of node 4."
  [s]
  (str "data: {\"t\":32,\"i\":4,\"f\":{\"t\":1,\"s\":" (seroval-string s) "}}\n\n"))

(defn- live-body
  "Returns a response body that writes the preamble, then an event for every value `subscribe`
  sends, until the browser goes away. Each stream blocks the thread that writes it, so a server
  with many open streams wants virtual threads. A write is how the server notices a closed
  connection, so a heartbeat goes out after `heartbeat-seconds` without a value."
  [subscribe encode heartbeat-seconds]
  (reify StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (let [^OutputStream out out
            queue (LinkedBlockingQueue.)
            write! (fn [^String s]
                     (.write out (.getBytes s StandardCharsets/UTF_8))
                     (.flush out))
            stop (subscribe #(.offer queue [%]))]
        (try
          (write! live-preamble)
          (loop []
            (let [item (.poll queue heartbeat-seconds TimeUnit/SECONDS)]
              (write! (if (nil? item) ":\n\n" (live-event (encode (first item)))))
              (recur)))
          (catch IOException _)
          (finally
            (when (fn? stop) (stop))))))))

(defn- live-response [subscribe encode heartbeat-seconds]
  (when-not (fn? subscribe)
    (throw (ex-info "A live server function must return a subscribe function, (fn [send] stop)"
                    {:returned subscribe})))
  {:status 200
   :headers (assoc base-headers
                   "content-type" "text/event-stream"
                   "x-accel-buffering" "no"
                   "x-server-function-format" "0")
   :body (live-body subscribe encode heartbeat-seconds)})

;; The handler

(defn same-origin?
  "The default `:allow?` check: false when the request carries an `Origin` for another host
  than its `Host` header, as a cross-site request would. Solid's own handler refuses those."
  [request]
  (if-let [origin (get-in request [:headers "origin"])]
    (= (try (.getAuthority (URI. origin)) (catch Exception _ nil))
       (get-in request [:headers "host"]))
    true))

(defn- query-param [request k]
  (some (fn [pair]
          (let [[pk pv] (str/split pair #"=" 2)]
            (when (= k (URLDecoder/decode ^String pk StandardCharsets/UTF_8))
              (URLDecoder/decode ^String (or pv "") StandardCharsets/UTF_8))))
        (some-> (:query-string request) (str/split #"&"))))

(defn- encoded-arguments
  "Returns the encoded argument vector of a call: the body of a POST, or the first element of
  the JSON array in the `args` query parameter of a GET. JSON.stringify writes an array of
  strings as text that EDN reads the same way."
  [request]
  (if (= :get (:request-method request))
    (some-> (query-param request "args") edn/read-string first)
    (slurp (:body request) :encoding "UTF-8")))

(defn- answer [request id live? {:keys [heartbeat-seconds allow?]}]
  (let [{:keys [f opts]} (get @registry id)
        get? (= :get (:request-method request))
        {:keys [encode decode]} (codec (:encoding opts))]
    (cond
      (not (allow? request)) (error-response 403 "Cross-site call refused")
      (or (nil? f) (not= live? (boolean (:live opts)))) (error-response 404 (str "No server function " id))
      (and (not live?) (not= get? (= :get (:method opts)))) (error-response 405 "Method not allowed")
      (and (not get?) (not= "1" (get-in request [:headers "x-server-function-format"])))
      (error-response 415 "Only string bodies (format 1) are supported")
      :else
      (binding [*request* request]
        (let [text (encoded-arguments request)
              result (apply f (if (str/blank? text) [] (decode text)))]
          (if live?
            (live-response result encode heartbeat-seconds)
            (result-response encode result)))))))

(defn handler
  "Returns a Ring handler that answers the server functions defined with `defserverfn` under
  `:base` (\"/_server\", the default of Solid's client), and returns nil for other requests, so
  that it composes with other handlers. An `ex-info` thrown by a function sends its message,
  which the browser's call rejects with, and other exceptions send \"Internal Server Error\"
  after `:on-error` (by default printing the stack trace).

  Options:
  - `:base`, the path the browser's `configure-client` `:base` names.
  - `:allow?`, a function of the request that refuses a call when it returns false, by default
    `same-origin?`. Behind a proxy that rewrites `Host`, check the forwarded host instead.
  - `:heartbeat-seconds`, how long a live stream stays quiet before a heartbeat, 5 by default.
  - `:on-error`, called with an exception that is not an `ex-info`."
  ([] (handler {}))
  ([{:keys [base on-error] :or {base "/_server"} :as opts}]
   (let [opts (merge {:heartbeat-seconds 5 :allow? same-origin?} opts)
         on-error (or on-error #(.printStackTrace ^Throwable %))
         data (str base "/data/")
         live (str base "/live/")]
     (fn [{:keys [uri] :as request}]
       (when-let [[live? encoded-id] (cond
                                       (str/starts-with? uri data) [false (subs uri (count data))]
                                       (str/starts-with? uri live) [true (subs uri (count live))])]
         (let [id (URLDecoder/decode ^String encoded-id StandardCharsets/UTF_8)]
           (try
             (answer request id live? opts)
             (catch clojure.lang.ExceptionInfo e
               (error-response 400 (ex-message e)))
             (catch Exception e
               (on-error e)
               (error-response 500 "Internal Server Error")))))))))
