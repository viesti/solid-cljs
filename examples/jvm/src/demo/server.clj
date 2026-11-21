(ns demo.server
  "Serves the todo API with reitit, streams changes to every open page as server-sent events,
  answers the same operations as Solid server functions (demo.todos), and in production serves
  the browser bundle from the classpath."
  (:require [clojure.string :as str]
            [demo.db :as db]
            [demo.live :as live]
            [demo.todos]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.util.codec :as codec]
            [solid.server-functions.ring :as sfr])
  (:import (java.util.concurrent Executors)
           (org.eclipse.jetty.http UriCompliance UriCompliance$Violation)
           (org.eclipse.jetty.server Connector HttpConnectionFactory Server)
           (org.eclipse.jetty.util.thread QueuedThreadPool))
  (:gen-class))

;; Live updates

(defn- events
  "Opens an event stream for the API's clients. Each event holds every todo and the viewer
  count as EDN, which prints strings with their newlines escaped, so the data fits on one line."
  [_]
  {:status 200
   :headers {"content-type" "text/event-stream" "cache-control" "no-cache"}
   :body (live/event-stream live/subscribe #(str "data: " (pr-str %) "\n\n") nil)})

;; The API

(defn- author [request]
  (get-in request [:cookies "name" :value] "someone"))

(defn- id-of [request]
  (parse-long (get-in request [:path-params :id])))

(defn- found [result]
  (if result {:status 204} {:status 404 :body {:error "No such todo"}}))

(defn- encode-cookie
  "Encodes a cookie value as `encodeURIComponent` does (a space as %20, not +), which
  `s/parse-cookie-header` decodes in the browser."
  [cookies]
  (str/join "; " (for [[k v] cookies] (str (codec/url-encode k) "=" (codec/url-encode v)))))

(def ^:private routes
  [["/api"
    ["/todos" {:get (fn [_] {:status 200 :body {:todos (db/todos)}})
               :post (fn [{{:keys [title]} :body-params :as request}]
                       {:status 201 :body (db/add! title (author request))})}]
    ["/todos/:id" {:put (fn [{{:keys [title]} :body-params :as request}]
                          (found (some-> (id-of request) (db/rename! title))))
                   :delete (fn [request] (found (some-> (id-of request) db/remove!)))}]
    ["/todos/:id/toggle" {:post (fn [request] (found (some-> (id-of request) db/toggle!)))}]
    ["/session" {:post (fn [{{user-name :name} :body-params}]
                         (if (str/blank? user-name)
                           {:status 400 :body {:error "A name is needed"}}
                           {:status 200
                            :body {:name user-name}
                            :cookies {"name" {:value user-name :path "/" :max-age 31536000
                                              :same-site :lax}}}))}]
    ["/events" {:get events}]]
   ;; The same operations as server functions, which the page uses with ?via=server-functions.
   ;; Inside the router, so that the cookies middleware applies.
   ["/_server/*path" {:handler (sfr/handler)}]])

(defn- wrap-errors
  "Answers invalid input from demo.db with 400."
  [handler]
  (fn [request]
    (try (handler request)
         (catch clojure.lang.ExceptionInfo e
           (if (= ::db/invalid (:type (ex-data e)))
             {:status 400 :body {:error (ex-message e)}}
             (throw e))))))

(def app
  (ring/ring-handler
   (ring/router routes {:data {:muuntaja m/instance
                               ;; EDN, transit and JSON, chosen by Content-Type and Accept
                               :middleware [muuntaja/format-middleware
                                            wrap-errors
                                            [wrap-cookies {:encoder encode-cookie}]]}})
   (ring/routes
    ;; The release bundle, which the uberjar holds under public/
    (ring/create-resource-handler {:path "/" :root "public"})
    (ring/create-default-handler
     {:not-found (constantly {:status 404 :headers {"content-type" "text/plain"}
                              :body "Not found. In development the page is served by Vite."})}))))

;; Starting and stopping

(defn- allow-encoded-slashes!
  "Lets Jetty accept %2F in a path, which it refuses by default. Solid's client puts the server
  function id, \"demo.todos/add-todo\", in the path with its slash encoded."
  [^Server server]
  (doseq [^Connector connector (.getConnectors server)
          factory (.getConnectionFactories connector)
          :when (instance? HttpConnectionFactory factory)]
    (.setUriCompliance (.getHttpConfiguration ^HttpConnectionFactory factory)
                       (.with UriCompliance/DEFAULT "server function ids"
                              (into-array UriCompliance$Violation
                                          [UriCompliance$Violation/AMBIGUOUS_PATH_SEPARATOR])))))

(defonce ^:private running (atom nil))

(defn stop! []
  (when-let [{:keys [^Server server unsubscribe]} @running]
    (unsubscribe)
    (.stop server)
    (reset! running nil)))

(defn start!
  "Starts the server on `port`. It calls `app` through its var, so re-evaluating the handler
  at the REPL takes effect at once."
  [port]
  (stop!)
  (db/migrate!)
  (reset! running {:server (jetty/run-jetty #'app {:port port
                                                   :join? false
                                                   :configurator allow-encoded-slashes!
                                                   ;; Every request runs on a virtual thread, so
                                                   ;; an open event stream costs no platform thread
                                                   :thread-pool (doto (QueuedThreadPool.)
                                                                  (.setVirtualThreadsExecutor
                                                                   (Executors/newVirtualThreadPerTaskExecutor)))})
                   :unsubscribe (db/subscribe live/broadcast!)})
  (println (str "Todo API at http://localhost:" port)))

(defn -main [& _]
  (start! (parse-long (or (System/getenv "PORT") "8080"))))
