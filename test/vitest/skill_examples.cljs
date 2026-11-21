(ns vitest.skill-examples
  "The code examples of the agent skill in skills/solid-cljs, so that they keep compiling and
  working as the library changes. Update both together."
  (:require [applied-science.js-interop :as j]
            [solid.core :as s :refer [$ defui]]
            ["vitest/browser" :refer [page]]
            ["@solidjs/testing-library" :refer [render]]
            ["vitest" :refer [expect test]]))

(defui counter [{:keys [label]}]
  (let [n (s/signal 0)
        doubled (s/memo #(* 2 @n))]
    ($ :div {:class [:counter (when (> @n 9) :big)]}
       ($ :button {:on-click #(swap! n inc)} label)
       ($ :span @n " doubled is " (doubled)))))

(defui row [{:keys [todo]}]
  ($ :li (:title @todo)))

(defui lists [{:keys [todos]}]
  ($ :div
     (s/for [[todo] @todos :key :id] ($ row {:todo todo}))
     (s/for [[t idx] @todos :fallback ($ :li "none")] ($ :li @idx (:title t)))
     (s/index [[t i] @todos] ($ :li i (:title @t)))
     (s/repeat [i 3 :fallback ($ :p "zero")] ($ :span i))
     (s/case (count @todos) 0 ($ :p "none") 1 ($ :p "one") ($ :p "many"))
     (s/if-some [c (count @todos)] ($ :span c) ($ :span "-"))))

(defn fetch-user [id] (js/Promise.resolve {:name (str "u" id)}))
(defn spinner [] ($ :p "..."))

(defui profile-page [{:keys [id]}]
  (let [user (s/memo #(fetch-user id))]
    (s/loading {:fallback ($ spinner) :on id}
      ($ :h1 {:class {:stale (s/pending? (user))}} (:name (user))))))

(defui todos-page []
  (let [todos (s/memo #(js/Promise.resolve []))
        shown (s/optimistic #(todos))
        add (s/action [todo]
              (swap! shown conj todo)
              (s/yield (js/Promise.resolve todo))
              (s/refresh todos))]
    (s/effect (fn [] (count (shown))) (fn [c] (js/console.log c) #(js/console.log "cleanup")))
    (s/try
      ($ :button {:on-click #(add {:id 1})} "add")
      (catch [_err reset]
        ($ :button {:on-click #(reset)} "Retry")))))

(defui store-demo []
  (let [[st set-st] (s/store {:items [{:id 1 :title "Docs" :done? false}]})]
    ($ :ul
       {:on-click (fn [_]
                    (s/sset! set-st [:items 0 :done?] true)
                    (s/supdate! set-st [:items 0 :title] str "!")
                    (set-st (fn [draft] (.push (.-items draft) #js {:id 2 :title "Ship" :done? false})))
                    (set-st (fn [draft] (s/replacement (update (s/store->clj draft) :items #(remove :done? %))))))}
       (s/for [[t] (s/sget st :items)] ($ :li (.-title t))))))

(def theme (s/create-context))

(defui themed []
  ($ theme {:value "dark"} ($ :p (s/use-context theme))))

(defn- shows [^js screen text]
  (.. expect (element (.. screen (getByText text))) toBeInTheDocument))

(test "skill examples render"
  (fn []
    (j/let [todos (s/signal [{:id 1 :title "a"} {:id 2 :title "b"}])
            ^:js {:keys [baseElement]} (render #($ :div ($ counter {:label "+"})
                                                   ($ lists {:todos todos})
                                                   ($ profile-page {:id 7})
                                                   ($ todos-page)
                                                   ($ store-demo)
                                                   ($ themed)))
            screen (.. page (elementLocator baseElement))]
      (-> (shows screen "0 doubled is 0")
          (.then #(.click (.. screen (getByRole "button" #js {:name "+"}))))
          (.then #(shows screen "1 doubled is 2"))
          (.then #(shows screen "u7"))
          (.then #(shows screen "many"))
          (.then #(shows screen "dark"))
          (.then #(shows screen "Docs"))))))
