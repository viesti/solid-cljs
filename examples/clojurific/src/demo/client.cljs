(ns demo.client
  "The browser entry: hydrates the page that the server rendered."
  (:require [demo.app :refer [app]]
            [solid.core :as s :refer [$]]
            [solid.server-components :as sc]))

(defn- user-name []
  (get (s/parse-cookie-header js/document.cookie) "name"))

;; Hydrates once. Development builds set :solid/refresh, so a hot reload re-renders only the
;; components whose code changed and keeps the state of the others.
(defonce dispose
  (do (sc/install!)
      (s/hydrate ($ app {:user-name (user-name)}) (js/document.getElementById "root"))))
