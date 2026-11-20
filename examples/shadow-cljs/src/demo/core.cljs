(ns demo.core
  (:require [solid.core :as s :refer [$ defui]]))

(defui counter []
  (let [count (s/signal 0)
        doubled (s/memo #(* 2 @count))]
    (s/effect (fn [] @count) #(js/console.log "count is" %))
    ($ :section
       ($ :h2 "Counter")
       ($ :button {:on-click #(swap! count dec)} "-")
       ($ :span {:style {:padding "0 12px"}} @count)
       ($ :button {:on-click #(swap! count inc)} "+")
       ($ :p {:class :muted} "Doubled: " (doubled)))))

(defui todo-item [props]
  ($ :li
     ($ :input {:type :checkbox
                :checked (:done? props)
                :on-change (:on-toggle props)})
     ($ :span {:class {:done (:done? props)}} (:title props))
     ($ :button {:on-click (:on-remove props)} "x")))

(defui todos []
  (let [title (s/signal "")
        items (s/signal [{:title "Try solid-cljs" :done? true}
                         {:title "Write a component" :done? false}])
        add (fn [e]
              (.preventDefault e)
              (when (seq @title)
                (swap! items conj {:title @title :done? false})
                (reset! title "")))]
    ($ :section
       ($ :h2 "Todos")
       ($ :form {:on-submit add}
          ($ :input {:type :text
                     :placeholder "What needs doing?"
                     :value @title
                     :on-input #(reset! title (.. % -target -value))})
          " "
          ($ :button "Add"))
       (s/if (empty? @items)
         ($ :p {:class :muted} "Nothing to do.")
         ($ :ul
            (s/for [[item idx] @items]
              ($ todo-item {:title (:title item)
                            :done? (:done? item)
                            :on-toggle #(swap! items update-in [@idx :done?] not)
                            :on-remove #(swap! items (fn [xs] (into (subvec xs 0 @idx)
                                                                    (subvec xs (inc @idx)))))}))))
       ($ :p {:class :muted}
          (count (filter :done? @items)) " of " (count @items) " done"))))

(defn fetch-greeting
  "Resolves to a greeting after a delay, standing in for a request."
  [lang]
  (js/Promise.
   (fn [resolve]
     (js/setTimeout #(resolve (get {:en "Hello" :fi "Moi" :sv "Hej"} lang)) 600))))

(defui greeting []
  (let [lang (s/signal :en)
        text (s/memo #(fetch-greeting @lang))]
    ($ :section
       ($ :h2 "Async")
       (s/for [[l] [:en :fi :sv]]
         ($ :button {:on-click #(reset! lang l)
                     :style {:margin-right 4}}
            (name l)))
       (s/loading ($ :p {:class :muted} "Loading...")
                          ;; Dims the old greeting while the next one loads
                  ($ :p {:class {:muted (s/pending? (text))}} (text) ", world!")))))

(defui app []
  ($ :main
     ($ :h1 "solid-cljs demo")
     ($ counter)
     ($ todos)
     ($ greeting)))

;; Renders once. Development builds set :solid/refresh, so a hot reload re-renders only the
;; components whose code changed and keeps the state of the others.
(defonce dispose (s/render ($ app) (js/document.getElementById "root")))
