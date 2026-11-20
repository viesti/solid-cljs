(ns demo.app
  "The demo's components. The server renders them into the page and the browser hydrates them."
  (:require [demo.todos :as todos]
            [solid.core :as s :refer [$ defui]]
            [solid.server-components :as sc]))

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
       ($ :h2 "Todos in this browser")
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

(defui check
  "Fills the `check` slot of a shared todo with a checkbox."
  [{:keys [id done]}]
  ($ :input {:type :checkbox :checked done :on-change #(todos/toggle-todo id)}))

(defui actions
  "Fills the `actions` slot of a shared todo. The edit field keeps its draft while other
  viewers' changes arrive."
  [{:keys [id title]}]
  (let [editing (s/signal false)
        draft (s/signal "")
        save (fn [e]
               (.preventDefault e)
               (when (seq @draft) (todos/rename-todo id @draft))
               (reset! editing false))]
    (s/if @editing
      ($ :form {:class :inline :on-submit save}
         ($ :input {:type :text :value @draft :on-input #(reset! draft (.. % -target -value))})
         ($ :button "Save"))
      ($ :span {:class :inline}
         ($ :button {:on-click #(do (reset! draft title) (reset! editing true))} "Edit")
         ($ :button {:on-click #(todos/remove-todo id)} "x")))))

(defui sign-in-form [{:keys [on-sign-in]}]
  (let [draft (s/signal "")
        submit (fn [e]
                 (.preventDefault e)
                 (when (seq @draft)
                   (.then (todos/sign-in @draft) on-sign-in)))]
    ($ :form {:on-submit submit}
       ($ :input {:type :text :placeholder "Your name" :value @draft
                  :on-input #(reset! draft (.. % -target -value))})
       " "
       ($ :button "Sign in"))))

(defui shared-todos
  "Renders the todo list that every viewer shares. `user-name` comes from a cookie."
  [{:keys [user-name]}]
  (let [user (s/signal user-name)
        title (s/signal "")
        adding (s/optimistic (fn [] []))
        saved (s/signal nil)
        add (s/action [t]
                      (swap! adding conj t)
                      (s/yield [todo (todos/add-todo t)])
                      (reset! saved todo))
        submit (fn [e]
                 (.preventDefault e)
                 (when (seq @title)
                   (add @title)
                   (reset! title "")))
        shared-list (sc/server-component (todos/shared-list))]
    ($ :section
       ($ :h2 "Shared todos")
       (s/if @user
         ($ :div
            ($ :p {:class :muted} "Signed in as " ($ :strong @user)
               ". Open this page in another window to see changes arrive.")
            ($ :form {:on-submit submit}
               ($ :input {:type :text :placeholder "What needs doing?" :value @title
                          :on-input #(reset! title (.. % -target -value))})
               " "
               ($ :button "Add")))
         ($ sign-in-form {:on-sign-in #(reset! user %)}))
       (s/loading ($ :p {:class :muted} "Loading...")
                  ($ shared-list {:check check :actions actions}))
       ($ :ul (s/for [[t] @adding]
                ($ :li {:class :muted} t " (saving)")))
       (s/when-let [t @saved]
         ($ :p {:class :muted}
            "Saved \u201c" (:title t) "\u201d at " (todos/time-text (:created-at t) true))))))

(defui app [{:keys [user-name]}]
  ($ :main
     ($ :h1 "solid-cljs demo")
     ($ shared-todos {:user-name user-name})
     ($ counter)
     ($ todos)
     ($ greeting)))
