(ns demo.todos
  "Server functions and the live server component of the shared todo list. The browser build
  keeps only references to them, and the server build reads `:server` conditionals."
  (:require #?@(:server [[demo.db :as db]])
            [clojure.string :as str]
            [solid.core :as s :refer [$]]
            [solid.server-components :refer [defservercomponent]]
            [solid.server-functions :refer [defserverfn]]))

#?(:server
   (defn- author
     "Returns the name in the cookie of the current request."
     []
     (-> (s/request-event) .-request .-headers (.get "cookie") (or "")
         s/parse-cookie-header
         (get "name" "someone"))))

(defn time-text
  "Formats the time of day of the `js/Date` `date` as 14:02, or with `seconds` as 14:02:05."
  ([date] (time-text date false))
  ([^js date seconds]
   (.toLocaleTimeString date "en-GB" (cond-> {:hour "2-digit" :minute "2-digit"}
                                       seconds (assoc :second "2-digit")
                                       true clj->js))))

;; Returns the new todo, whose js/Date timestamps travel to the browser as EDN's #inst
(defserverfn add-todo [title]
  (db/add! (str/trim title) (author)))

(defserverfn toggle-todo [id]
  (db/toggle! id))

(defserverfn rename-todo [id title]
  (db/rename! id (str/trim title)))

(defserverfn remove-todo [id]
  (db/remove! id))

(defserverfn sign-in [user-name]
  (s/respond user-name
             {:headers {"set-cookie" (s/serialize-cookie "name" user-name
                                                         {:path "/" :max-age 31536000 :same-site "Lax"})}}))

(defservercomponent shared-list {:broadcast true} []
  (db/track-viewer!)
  (s/ui [{:keys [check actions]}]
        (let [state (s/memo #(s/from db/subscribe))
              todos #(:todos (state))]
          ($ :div
             ($ :p {:class :muted}
                (count (remove :done (todos))) " open · "
                (count (filter :done (todos))) " done · "
                (:viewers (state)) " viewing")
             ($ :ul
                (s/for [[t] (todos)]
                  ($ :li {:$key (str (:id t))}
                     ($ check {:$key (str (:id t)) :id (:id t) :done (:done t)})
                     ($ :span {:class {:done (:done t)}} (:title t))
                     ($ :small {:class :muted}
                        (:author t)
                        (when-let [at (:created-at t)] (str " · added " (time-text at)))
                        (when-let [at (:done-at t)] (str " · done " (time-text at))))
                     ($ actions {:$key (str (:id t)) :id (:id t) :title (:title t)}))))))))
