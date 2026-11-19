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
