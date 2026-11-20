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

(def ^:private style
  "body { margin: 0; font: 16px system-ui, sans-serif; background: #f4f4f5; color: #18181b; }
   main { max-width: 560px; margin: 0 auto; padding: 24px 16px; display: grid; gap: 16px; }
   section { background: #fff; border-radius: 12px; padding: 16px; box-shadow: 0 1px 2px rgb(0 0 0 / 0.08); }
   h1 { font-size: 24px; margin: 0; }
   h2 { font-size: 16px; margin: 0 0 12px; }
   button { font: inherit; padding: 4px 12px; border-radius: 6px; border: 1px solid #d4d4d8; background: #fff; cursor: pointer; }
   input[type=\"text\"] { font: inherit; padding: 4px 8px; border-radius: 6px; border: 1px solid #d4d4d8; }
   ul { list-style: none; padding: 0; margin: 12px 0 0; display: grid; gap: 4px; }
   li { display: flex; gap: 8px; align-items: center; }
   small { margin-right: auto; }
   .inline { display: inline-flex; gap: 4px; }
   .done { text-decoration: line-through; color: #a1a1aa; }
   .muted { color: #71717a; }")

(defn- page-start []
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>solid-cljs demo</title><link rel=\"icon\" href=\"data:,\"><style>" style "</style>"
       (s/hydration-script) "</head><body><div id=\"root\">"))

;; Release builds set goog.DEBUG to false. In development Vite compiles the source itself.
(def ^:private page-end
  (str "</div><script type=\"module\" src=\""
       (if ^boolean goog.DEBUG "/src/demo/client.cljs" "/assets/app.js")
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
