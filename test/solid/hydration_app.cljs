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

(defui async-greeting []
  (let [text (s/memo #(js/Promise. (fn [resolve] (js/setTimeout (fn [] (resolve "Moi")) 60))))]
    ($ :section#async
       (s/loading ($ :p "loading") ($ :p (text) ", world")))))

(defui async-data
  "An async read whose value is ClojureScript data, which the server serializes for hydrate."
  []
  (let [todos (s/memo #(js/Promise.
                        (fn [resolve]
                          (js/setTimeout
                           (fn [] (resolve [{:id 1 :title "first" :tags #{:a}
                                             :at (js/Date. 0) :id2 #uuid "00000000-0000-0000-0000-000000000001"
                                             :kind 'task :ns/k :x/y :path '(1 2) :by-pair {[1 2] "pair"}}
                                            {:id 2 :title "second" :tags #{}}]))
                           60))))
        shown (s/signal "")]
    ($ :section#data
       (s/loading ($ :p "loading")
                  ($ :ul (s/for [[t] (todos)] ($ :li (:title t)))))
       ($ :button {:on-click #(reset! shown (pr-str [(vector? (todos)) (todos) (get-in (todos) [0 :by-pair [1 2]]) (keyword? (get-in (todos) [0 :ns/k]))]))} "show")
       ($ :pre @shown))))

(defui app []
  ($ :main
     ($ counter)
     ($ todo-list)
     ($ toggle)
     ($ card {:title "Card"} ($ :p "body"))
     ($ async-greeting)
     ($ async-data)))
