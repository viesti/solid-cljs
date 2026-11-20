(ns demo.server
  "The server: a Hono app that renders the page, answers server function calls and live
  streams, and serves the browser bundle in production. @hono/vite-dev-server serves it in
  development, and @hono/vite-build bundles it into a Node.js server."
  (:require ["@hono/node-server/serve-static" :refer [serveStatic]]
            ["hono" :refer [Hono]]
            [demo.app :refer [app]]
            [demo.db :as db]
            [demo.store :as store]
            [demo.todos]
            [solid.core :as s :refer [$]]
            [solid.server :as server]
            [solid.server-components :as sc]
            [solid.server-functions.server :as sfs]))

(store/install! {:add! db/add! :toggle! db/toggle! :rename! db/rename! :remove! db/remove!
                 :subscribe db/subscribe :track-viewer! db/track-viewer!})

(sc/configure-server!)

(s/configure-server-errors {:on-error (fn [error _] (js/console.error error))})

;; Release builds set goog.DEBUG to false. In development Vite serves the sources themselves,
;; and updates the linked stylesheet in place when it changes.
(defn- asset [dev-path production-path]
  (if ^boolean goog.DEBUG dev-path production-path))

(defn- page-start []
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>solid-cljs demo</title><link rel=\"icon\" href=\"data:,\">"
       "<link rel=\"stylesheet\" href=\"" (asset "/src/demo/style.css" "/assets/style.css") "\">"
       (s/hydration-script) "</head><body><div id=\"root\">"))

(def ^:private page-end
  (str "</div><script type=\"module\" src=\"" (asset "/src/demo/client.cljs" "/assets/app.js")
       "\"></script></body></html>"))

(defn- render-page
  "Returns a response that streams the page for `request`, with the name from its cookie."
  [^js request]
  (let [user-name (get (s/parse-cookie-header (or (.get (.-headers request) "cookie") "")) "name")
        encoder (js/TextEncoder.)]
    (js/Response.
     (js/ReadableStream.
      #js {:start (fn [controller]
                    (.enqueue controller (.encode encoder (page-start)))
                    (s/pipe (server/provide-request-event
                             (server/create-request-event request)
                             #(s/render-to-stream ($ app {:user-name user-name}) {:plugins [sc/plugin]}))
                            #js {:write (fn [chunk] (.enqueue controller (.encode encoder chunk)) true)
                                 :end (fn []
                                        (.enqueue controller (.encode encoder page-end))
                                        (.close controller))}))})
     #js {:headers #js {"content-type" "text/html; charset=utf-8"}})))

(def handler
  "The Hono app, the default export of the server entry."
  (doto (Hono.)
    (.all "/_server/*" (fn [^js c] (sfs/handle-request (.. c -req -raw))))
    (.get "/" (fn [^js c] (render-page (.. c -req -raw))))
    (.get "/assets/*" (serveStatic #js {:root "./dist/client"}))))
