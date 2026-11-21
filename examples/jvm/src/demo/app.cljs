(ns demo.app
  "The shared todo list. It renders in the browser and talks to the JVM through demo.api."
  (:require [demo.api :as api]
            [solid.core :as s :refer [$ defui]]))

(defn time-text
  "Formats the time of day of the `js/Date` `date` as 14:02, or with `seconds` as 14:02:05."
  ([date] (time-text date false))
  ([^js date seconds]
   (.toLocaleTimeString date "en-GB" (cond-> {:hour "2-digit" :minute "2-digit"}
                                       seconds (assoc :second "2-digit")
                                       true clj->js))))

(defui todo-row
  "One shared todo. `todo` follows the todo with its id, so the row and an open edit field keep
  their state while other viewers' changes arrive."
  [{:keys [todo]}]
  (let [editing (s/signal false)
        draft (s/signal "")
        save (fn [e]
               (.preventDefault e)
               (when (seq @draft) (api/rename-todo (:id @todo) @draft))
               (reset! editing false))]
    ($ :li
       ($ :input {:type :checkbox :checked (:done @todo) :on-change #(api/toggle-todo (:id @todo))})
       ($ :span {:class {:done (:done @todo)}} (:title @todo))
       ($ :small {:class :muted}
          (:author @todo)
          (when-let [at (:created-at @todo)] (str " · added " (time-text at)))
          (when-let [at (:done-at @todo)] (str " · done " (time-text at))))
       (s/if @editing
         ($ :form {:class :inline :on-submit save}
            ($ :input {:type :text :value @draft :on-input #(reset! draft (.. % -target -value))})
            ($ :button "Save"))
         ($ :span {:class :inline}
            ($ :button {:on-click #(do (reset! draft (:title @todo)) (reset! editing true))} "Edit")
            ($ :button {:on-click #(api/remove-todo (:id @todo))} "x"))))))

(defui shared-list
  "Every todo and the viewer count, as the server's event stream sends them."
  []
  (let [state (s/memo #(s/from api/subscribe))
        todos #(:todos (state))]
    ($ :div
       ($ :p {:class :muted}
          (count (remove :done (todos))) " open · "
          (count (filter :done (todos))) " done · "
          (:viewers (state)) " viewing")
       ($ :ul
          (s/for [[todo] (todos) :key :id]
            ($ todo-row {:todo todo}))))))

(defui sign-in-form [{:keys [on-sign-in]}]
  (let [draft (s/signal "")
        submit (fn [e]
                 (.preventDefault e)
                 (when (seq @draft)
                   (.then (api/sign-in @draft) on-sign-in)))]
    ($ :form {:on-submit submit}
       ($ :input {:type :text :placeholder "Your name" :value @draft
                  :on-input #(reset! draft (.. % -target -value))})
       " "
       ($ :button "Sign in"))))

(defui app
  "The shared todo list. `user-name` comes from the cookie the server sets."
  [{:keys [user-name]}]
  (let [user (s/signal user-name)
        title (s/signal "")
        adding (s/optimistic (fn [] []))
        saved (s/signal nil)
        add (s/action [t]
                      (swap! adding conj t)
                      (s/yield [todo (api/add-todo t)])
                      (reset! saved todo))
        submit (fn [e]
                 (.preventDefault e)
                 (when (seq @title)
                   (add @title)
                   (reset! title "")))]
    ($ :main
       ($ :h1 "solid-cljs with a JVM backend")
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
                     ($ shared-list))
          ($ :ul (s/for [[t] @adding]
                   ($ :li {:class :muted} t " (saving)")))
          (s/when-let [t @saved]
            ($ :p {:class :muted}
               "Saved “" (:title t) "” at " (time-text (:created-at t) true)))))))
