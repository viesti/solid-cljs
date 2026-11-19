(ns solid.hydration-app
  "An app that the hydration tests render on the server and hydrate in the browser."
  (:require [solid.core :as s :refer [$ defui]]))

(defui counter []
  (let [n (s/signal 0)]
    ($ :section#counter
       ($ :button {:on-click #(swap! n inc)} "+")
       ($ :span {:title (str "count " @n)} "count is " @n "!"))))

(defui item [{:keys [label]}]
  ($ :li label))

(defui todo-list []
  (let [items (s/signal ["a" "b"])]
    ($ :section#todos
       ($ :ul (s/for [[x] @items] ($ item {:label x})))
       ($ :button#add {:on-click #(swap! items conj (str "item" (count @items)))} "add"))))

(defui toggle []
  (let [open? (s/signal false)]
    ($ :section#toggle
       ($ :button {:on-click #(swap! open? not)} "toggle")
       (s/if @open? ($ :p "open") ($ :p "closed")))))

(defui card [{:keys [title children]}]
  ($ :article ($ :h2 title) children))

(defui app []
  ($ :main
     ($ counter)
     ($ todo-list)
     ($ toggle)
     ($ card {:title "Card"} ($ :p "body"))))
