(ns app.todos
  "Server functions and the live server component of the shared todo list, as `\"use server\"`
  functions that Solid's compiler turns into calls in the browser. They import app.db inside
  their bodies, so the browser never loads it."
  (:require ["solid-js" :refer [createMemo from For]]
            ["@solidjs/web" :refer [getRequestEvent parseCookieHeader respond serializeCookie]]
            ["@solidjs/web/server-functions" :refer [broadcast GET]]))

(defn- db []
  (js/import "./db.jsx"))

(defn- subscribe
  "Subscribes `next` to the database's changes once app.db has loaded. A subscribe function for
  Solid's `from`."
  [next]
  (let [unsubscribe (atom nil)
        closed (atom false)]
    (.then (db) (fn [m] (when-not @closed (reset! unsubscribe (.subscribe m next)))))
    (fn []
      (reset! closed true)
      (when-let [f @unsubscribe] (f)))))

(defn- author []
  (or (aget (parseCookieHeader (or (.. (getRequestEvent) -request -headers (get "cookie")) "")) "name")
      "someone"))

(defn time-text
  "Formats the time of day of the `js/Date` `date` as 14:02, or with `seconds` as 14:02:05."
  [date seconds]
  (.toLocaleTimeString date "en-GB" (if seconds
                                      {:hour "2-digit" :minute "2-digit" :second "2-digit"}
                                      {:hour "2-digit" :minute "2-digit"})))

;; Returns the new todo, whose js/Date timestamps Solid's serializer carries to the browser
(defn ^:async add-todo [title]
  "use server"
  (.add! (js-await (db)) (.trim title) (author)))

(defn ^:async toggle-todo [id]
  "use server"
  (.toggle! (js-await (db)) id))

(defn ^:async rename-todo [id title]
  "use server"
  (.rename! (js-await (db)) id (.trim title)))

(defn ^:async remove-todo [id]
  "use server"
  (.remove! (js-await (db)) id))

(defn ^:async sign-in [user-name]
  "use server"
  (respond user-name {:headers {"set-cookie" (serializeCookie "name" user-name
                                                              {:path "/" :maxAge 31536000 :sameSite "Lax"})}}))

(def current-user
  "Returns the name in the request's cookie, which the page reads while it renders."
  (GET (^:async fn []
         "use server"
         (aget (parseCookieHeader (or (.. (getRequestEvent) -request -headers (get "cookie")) "")) "name"))))

(def SharedList
  "Renders the shared todos for every viewer at once, updating as they change."
  (broadcast
   (^:async fn []
     "use server"
     (.track-viewer! (js-await (db)))
     ;; The render is shared by every viewer, so it reads nothing from this body
     (fn [props]
       (let [state (createMemo #(from subscribe))
             todos #(:todos (state))]
         #jsx [:div
               [:p {:class "muted"}
                (count (remove :done (todos))) " open · "
                (count (filter :done (todos))) " done · "
                (:viewers (state)) " viewing"]
               [:ul
                [For {:each (todos)}
                 (fn [t]
                   #jsx [:li {:$key (str (:id t))}
                         [props.check {:$key (str (:id t)) :id (:id t) :done (:done t)}]
                         [:span {:class {:done (:done t)}} (:title t)]
                         [:small {:class "muted"}
                          (:author t)
                          (when-let [at (:created-at t)] (str " · added " (time-text at false)))
                          (when-let [at (:done-at t)] (str " · done " (time-text at false)))]
                         [props.actions {:$key (str (:id t)) :id (:id t) :title (:title t)}]])]]])))))
