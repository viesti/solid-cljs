(ns demo.todos
  "The todo operations as server functions. The browser build compiles them with solid-cljs's
  `defserverfn`, which with `:solid/render :dom` keeps only a reference that calls the server,
  and the JVM compiles them with `demo.server-functions/defserverfn`, which defines and
  registers them. Both give each the id \"demo.todos/<name>\"."
  (:require #?@(:clj [[demo.db :as db]
                      [demo.live :as live]
                      [demo.server-functions :as sf :refer [defserverfn]]]
                :cljs [[solid.server-functions :refer [defserverfn]]])
            [clojure.string :as str]))

#?(:clj
   (defn- author
     "Returns the name in the cookie of the current call."
     []
     (get-in sf/*request* [:cookies "name" :value] "someone")))

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
  (sf/respond user-name {:cookies {"name" {:value user-name :path "/" :max-age 31536000
                                           :same-site :lax}}}))

;; A call that only reads, which the browser may send as a GET
(defserverfn list-todos {:method :get} []
  (db/todos))

;; Every todo and the viewer count, now and after every change
(defserverfn todo-feed {:live true} []
  live/subscribe)
