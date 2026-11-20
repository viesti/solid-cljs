(ns solid.server-functions.server
  "The server side of server functions: the HTTP handler and its configuration. Server builds
  only, since it runs the request scope on Node.js's AsyncLocalStorage."
  (:require ["@solidjs/web/server-functions" :as sf]
            ["@solidjs/web/storage" :as storage]
            [solid.server-functions :as sfn]))

(defn- provide-event [event f]
  (storage/provideRequestEvent event f))

(defn handle-request
  "Answers a server function call, a fetch `Request` for the server functions' path
  (`/_server` by default), with a promise of a `Response`. Server functions run with the
  request event, which `s/request-event` returns, unless `opts` sets another
  `:provide-event`."
  ([request] (handle-request request nil))
  ([request opts]
   (sf/handleServerFunctionRequest request (sfn/js-options (merge {:provide-event provide-event} opts)))))

(defn configure-server
  "Configures the server functions' handler with the options of Solid's
  `configureServerFunctionsServer`, such as `:base` and `:csrf`."
  [opts]
  (sf/configureServerFunctionsServer (sfn/js-options opts)))

(defn flash
  "Returns a promise of the outcome of the form post that redirected to the request `request`,
  read from its flash cookie, or of nil: a map of `:url`, `:input`, and `:result` or `:error`.
  The cookie is encrypted with the `:secret` of `configure-server`. `clear-flash-cookie` is the
  `Set-Cookie` value that removes it."
  [^js request]
  (.then (sf/decodeFlashCookie (.get (.-headers request) "cookie"))
         (fn [^js submission]
           (when submission
             {:url (.-url submission)
              :input (vec (.-input submission))
              :result (.-result submission)
              :error (.-error submission)}))))

(defn clear-flash-cookie
  "Returns the `Set-Cookie` value that removes the flash cookie once it has been read."
  []
  (sf/clearFlashCookie))

(defn invocation
  "Returns how the server function running now was called, or nil outside one: a map of `:id`,
  `:live` (true for a live connection) and `:broadcast` (true when its answer is shared)."
  []
  (when-let [^js inv (sf/getServerFunctionInvocation)]
    {:id (.-id inv) :live (boolean (.-live inv)) :broadcast (boolean (.-broadcast inv))}))
