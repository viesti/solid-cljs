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
                     ($ :small {:class :muted} (:author t))
                     ($ actions {:$key (str (:id t)) :id (:id t) :title (:title t)}))))))))
