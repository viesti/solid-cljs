(ns solid.server-component-app
  "A page around a server component, which the server renders into a document and the browser
  adopts, and the fills of its slots."
  (:require [solid.core :as s :refer [$ defui]]
            [solid.server-components :as sc]))

(defui editor [{:keys [id]}]
  (let [n (s/signal 0)]
    ($ :button {:class "edit" :on-click #(swap! n inc)} id ":" @n)))

(defui pending []
  ($ :i "adding"))

(defui page
  "Renders the server component `todo-list`, which the server and the browser define
  differently, for the filter that the signal `filter` holds."
  [{:keys [todo-list filter]}]
  (let [todos (sc/server-component (todo-list @filter))]
    ($ :main (s/loading ($ :p "loading") ($ todos {:editor editor :pending pending})))))
