(ns demo.todos
  "The todo operations as server functions, defined once for both sides with solid-cljs's
  `defserverfn`. The browser build, with `:solid/render :dom`, keeps only references that call
  the server. On the JVM each is a function, registered under the same id,
  \"demo.todos/<name>\", for `solid.server-functions.ring/handler`."
  (:require #?@(:clj [[demo.db :as db]
                      [demo.live :as live]
                      [solid.server-functions.ring :as sfr]])
            [clojure.string :as str]
            [solid.server-functions :refer [defserverfn]]))

#?(:clj
   (defn- author
     "Returns the name in the cookie of the current call."
     []
     (get-in sfr/*request* [:cookies "name" :value] "someone")))

;; Returns the new todo, whose java.util.Date timestamps reach the browser as js/Date
(defserverfn add-todo [title]
  (db/add! title (author)))

(defserverfn toggle-todo [id]
  (db/toggle! id))

(defserverfn rename-todo [id title]
  (db/rename! id title))

(defserverfn remove-todo [id]
  (db/remove! id))

(defserverfn sign-in [user-name]
  (when (str/blank? user-name)
    (throw (ex-info "A name is needed" {})))
  (sfr/respond user-name {:cookies {"name" {:value user-name :path "/" :max-age 31536000
                                           :same-site :lax}}}))

;; A call that only reads, which the browser may send as a GET
(defserverfn list-todos {:method :get} []
  (db/todos))

;; Every todo and the viewer count, now and after every change
(defserverfn todo-feed {:live true} []
  live/subscribe)
