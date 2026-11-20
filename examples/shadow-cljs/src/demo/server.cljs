(ns demo.server
  "Serves the demo: renders the page, answers server function calls and live streams, and serves
  the browser bundle, through Vite in development and from dist/ in production."
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:path" :as path]
            ["node:stream" :refer [Readable]]
            [demo.app :refer [app]]
            [demo.todos]
            [solid.core :as s :refer [$]]
            [solid.server :as server]
            [solid.server-components :as sc]
            [solid.server-functions.server :as sfs]))

(def ^:private production? (= "production" js/process.env.NODE_ENV))

(def ^:private port (or js/process.env.PORT 3000))

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
       "<title>solid-cljs demo</title><link rel=\"icon\" href=\"data:,\"><style>" style "</style>" (s/hydration-script)
       "</head><body><div id=\"root\">"))

(def ^:private page-end
  (str "</div><script type=\"module\" src=\""
       (if production? "/assets/app.js" "/js/main.js")
       "\"></script></body></html>"))

(defn- web-request
  "Returns a fetch `Request` for the Node.js request `req`, aborted with `signal`."
  [^js req signal]
  (let [method (.-method req)]
    (js/Request. (str "http://" (.. req -headers -host) (.-url req))
                 (cond-> #js {:method method :headers (.-headers req) :signal signal}
                   (not (#{"GET" "HEAD"} method))
                   (doto (aset "body" (.toWeb Readable req)) (aset "duplex" "half"))))))

(defn- send!
  "Writes the fetch `Response` `response` to the Node.js response `res`, streaming its body."
  [^js res ^js response]
  (let [headers #js {}]
    (.forEach (.-headers response) (fn [v k] (when-not (= "set-cookie" k) (aset headers k v))))
    (when-let [cookies (seq (.getSetCookie (.-headers response)))]
      (aset headers "set-cookie" (into-array cookies)))
    (.writeHead res (.-status response) headers)
    (if-let [body (.-body response)]
      (let [readable (.fromWeb Readable body)]
        (.on res "close" #(.destroy readable))
        (.pipe readable res))
      (.end res))))

(defn- render-page!
  "Streams the page for `request` to `res`, with the name from its cookie."
  [^js request ^js res]
  (let [user-name (get (s/parse-cookie-header (or (.get (.-headers request) "cookie") "")) "name")]
    (.writeHead res 200 #js {"content-type" "text/html; charset=utf-8"})
    (.write res (page-start))
    (s/pipe (server/provide-request-event
             (server/create-request-event request)
             #(s/render-to-stream ($ app {:user-name user-name}) {:plugins [sc/plugin]}))
            #js {:write (fn [chunk] (.write res chunk) true)
                 :end #(.end res page-end)})))

(def ^:private content-types
  {".js" "text/javascript" ".css" "text/css" ".map" "application/json"})

(defn- serve-file!
  "Serves the file under dist/ for the request path `pathname`, or a 404."
  [pathname ^js res]
  (let [dist (path/resolve "dist")
        file (path/join dist pathname)]
    (if (and (.startsWith file dist) (fs/existsSync file) (.isFile (fs/statSync file)))
      (do (.writeHead res 200 #js {"content-type" (get content-types (path/extname file) "application/octet-stream")})
          (.pipe (fs/createReadStream file) res))
      (doto res (.writeHead 404) (.end "Not found")))))

(defonce ^:private vite (atom nil))

(defn handle!
  "Answers the Node.js request `req` on `res`."
  [^js req ^js res]
  (let [controller (js/AbortController.)
        pathname (.-pathname (js/URL. (.-url req) "http://localhost"))]
    ;; The browser went away before the response ended, such as a closed live stream
    (.on res "close" #(when-not (.-writableFinished res) (.abort controller)))
    (cond
      (.startsWith pathname "/_server")
      (-> (sfs/handle-request (web-request req (.-signal controller)))
          (.then #(send! res %)))

      (= "/" pathname)
      (render-page! (web-request req (.-signal controller)) res)

      production?
      (serve-file! pathname res)

      :else
      (.middlewares ^js @vite req res #(doto res (.writeHead 404) (.end "Not found"))))))

(defn main [& _]
  (sc/configure-server!)
  (s/configure-server-errors {:on-error (fn [error _] (js/console.error error))})
  (-> (if production?
        (js/Promise.resolve nil)
        (.createServer (js/require "vite")
                       #js {:server #js {:middlewareMode true :hmr false} :appType "custom"}))
      (.then (fn [v]
               (reset! vite v)
               ;; Calls handle! through its var, so that a hot reload of this namespace takes effect
               (.listen (http/createServer (fn [req res] (handle! req res))) port
                        #(println (str "solid-cljs demo at http://localhost:" port)))))))
