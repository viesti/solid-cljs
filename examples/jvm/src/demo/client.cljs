(ns demo.client
  "The browser entry: renders the app into the static page that the JVM serves."
  (:require [demo.app :refer [app]]
            [solid.core :as s :refer [$]]))

(defn- user-name []
  (get (s/parse-cookie-header js/document.cookie) "name"))

;; Renders once. Development builds set :solid/refresh, so a hot reload re-renders only the
;; components whose code changed and keeps the state of the others.
(defonce dispose
  (s/render ($ app {:user-name (user-name)}) (js/document.getElementById "root")))
