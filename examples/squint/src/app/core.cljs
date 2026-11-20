(ns app.core
  (:require ["solid-js" :refer [createSignal createMemo For Show Loading isPending]]
            ["@solidjs/web" :refer [render]]
            [solid.squint :as s]))

(defn Counter []
  (let [count (s/signal 0)
        doubled (createMemo #(* 2 @count))]
    (s/effect #(deref count) #(js/console.log "count is" %))
    #jsx [:section
          [:h2 "Counter"]
          [:button {:onClick #(swap! count dec)} "-"]
          [:span {:style {:padding "0 12px"}} @count]
          [:button {:onClick #(swap! count inc)} "+"]
          [:p {:class "muted"} "Doubled: " (doubled)]]))

(defn TodoItem [props]
  #jsx [:li
        [:input {:type "checkbox"
                 :checked (.-done props)
                 :onChange (.-onToggle props)}]
        [:span {:class {:done (.-done props)}} (.-title props)]
        [:button {:onClick (.-onRemove props)} "x"]])

(defn Todos []
  (let [title (s/signal "")
        [items set-items] (s/store [{:title "Try Squint" :done true}
                                    {:title "Write a component" :done false}])
        add (fn [e]
              (.preventDefault e)
              (when (seq @title)
                (let [t @title]
                  (set-items (fn [draft] (.push draft {:title t :done false}))))
                (reset! title "")))]
    #jsx [:section
          [:h2 "Todos"]
          [:form {:onSubmit add}
           [:input {:type "text"
                    :placeholder "What needs doing?"
                    :value @title
                    :onInput #(reset! title (.. % -target -value))}]
           " "
           [:button "Add"]]
          [Show {:when (seq items)
                 :fallback #jsx [:p {:class "muted"} "Nothing to do."]}
           [:ul
            [For {:each items}
             (fn [item i]
               #jsx [TodoItem {:title (:title item)
                               :done (:done item)
                               :onToggle #(set-items (fn [draft]
                                                       (let [todo (aget draft (i))]
                                                         (aset todo "done" (not (:done todo))))))
                               :onRemove #(set-items (fn [draft]
                                                       (s/replacement (.filter draft (fn [_ j] (not= j (i)))))))}])]]]
          [:p {:class "muted"}
           (count (filter :done items)) " of " (count items) " done"]]))

(defn fetch-greeting
  "Resolves to a greeting after a delay, standing in for a request."
  [lang]
  (js/Promise.
   (fn [resolve]
     (js/setTimeout #(resolve (get {:en "Hello" :fi "Moi" :sv "Hej"} lang)) 600))))

(defn Greeting []
  (let [[lang set-lang] (createSignal "en")
        text (createMemo #(fetch-greeting (lang)))]
    #jsx [:section
          [:h2 "Async"]
          [For {:each ["en" "fi" "sv"]}
           (fn [l]
             #jsx [:button {:onClick #(set-lang l) :style {:margin-right "4px"}} l])]
          [Loading {:fallback #jsx [:p {:class "muted"} "Loading..."]}
           [:p {:class {:muted (isPending text)}} (text) ", world!"]]]))

(defn App []
  #jsx [:main
        [:h1 "Solid with Squint"]
        [Counter]
        [Todos]
        [Greeting]])

(defonce dispose (atom nil))

(defn ^:dev/after-load start []
  (when-let [f @dispose] (f))
  (reset! dispose (render (fn [] #jsx [App]) (js/document.getElementById "root"))))

(defonce started (start))
