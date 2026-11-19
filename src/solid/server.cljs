(ns solid.server
  "Server-only utilities: the request scope, which runs on Node.js's AsyncLocalStorage."
  (:require ["@solidjs/web" :as sw]
            ["@solidjs/web/storage" :as storage]))

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
