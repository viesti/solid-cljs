(ns demo.api
  "Calls the JVM's todo API. Bodies travel as EDN, so the timestamps arrive as `js/Date`
  (EDN's #inst)."
  (:require [cljs.reader :as reader]))

(defn- request
  "Returns a promise of the EDN body of the answer to `method` on `path`, sending `body` as EDN.
  Rejects with the server's error message when the status is 400 or more."
  ([method path] (request method path nil))
  ([method path body]
   (-> (js/fetch path (clj->js (cond-> {:method method :headers {"accept" "application/edn"}}
                                 body (-> (assoc :body (pr-str body))
                                          (assoc-in [:headers "content-type"] "application/edn")))))
       (.then (fn [^js response]
                (.then (.text response)
                       (fn [text]
                         (let [data (when (seq text) (reader/read-string text))]
                           (if (.-ok response)
                             data
                             (throw (js/Error. (or (:error data)
                                                   (str "Request failed with status " (.-status response))))))))))))))

(defn add-todo
  "Resolves to the new todo."
  [title]
  (request "POST" "/api/todos" {:title title}))

(defn toggle-todo [id]
  (request "POST" (str "/api/todos/" id "/toggle")))

(defn rename-todo [id title]
  (request "PUT" (str "/api/todos/" id) {:title title}))

(defn remove-todo [id]
  (request "DELETE" (str "/api/todos/" id)))

(defn sign-in
  "Sets the name cookie and resolves to the name."
  [user-name]
  ;; A function, not the keyword :name, which a promise's then would ignore
  (.then (request "POST" "/api/session" {:name user-name}) #(:name %)))

(defn subscribe
  "Sends `{:todos [...] :viewers n}` to `next` on every change, read from the server's event
  stream, until the returned function is called. A subscribe function for `s/from`. The
  browser's `EventSource` reconnects by itself when the connection drops."
  [next _ _]
  (let [source (js/EventSource. "/api/events")]
    (.addEventListener source "message" #(next (reader/read-string (.-data ^js %))))
    #(.close source)))
