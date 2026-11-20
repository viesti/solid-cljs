(ns app.app
  "The demo's components. The server renders them into the page and the browser hydrates them.
  The default export is the root component of Solid's start mode."
  (:require ["solid-js" :refer [action createMemo createOptimistic createSignal For Show Loading isPending]]
            ["@solidjs/web" :refer [dynamicComponent]]
            [app.todos :as todos]
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
          [:h2 "Todos in this browser"]
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

(defn Check
  "Fills the `check` slot of a shared todo with a checkbox."
  [props]
  #jsx [:input {:type "checkbox" :checked (.-done props) :onChange #(todos/toggle-todo (.-id props))}])

(defn Actions
  "Fills the `actions` slot of a shared todo. The edit field keeps its draft while other
  viewers' changes arrive."
  [props]
  (let [editing (s/signal false)
        draft (s/signal "")
        save (fn [e]
               (.preventDefault e)
               (when (seq @draft) (todos/rename-todo (.-id props) @draft))
               (reset! editing false))]
    #jsx [Show {:when @editing
                :fallback #jsx [:span {:class "inline"}
                                [:button {:onClick #(do (reset! draft (.-title props)) (reset! editing true))} "Edit"]
                                [:button {:onClick #(todos/remove-todo (.-id props))} "x"]]}
          [:form {:class "inline" :onSubmit save}
           [:input {:type "text" :value @draft :onInput #(reset! draft (.. % -target -value))}]
           [:button "Save"]]]))

(defn SignInForm [props]
  (let [draft (s/signal "")
        submit (fn [e]
                 (.preventDefault e)
                 (when (seq @draft)
                   (.then (todos/sign-in @draft) (.-onSignIn props))))]
    #jsx [:form {:onSubmit submit}
          [:input {:type "text" :placeholder "Your name" :value @draft
                   :onInput #(reset! draft (.. % -target -value))}]
          " "
          [:button "Sign in"]]))

(defn SharedTodos
  "Renders the todo list that every viewer shares. The name comes from a cookie, which a server
  function reads while the page renders."
  []
  (let [cookie-user (createMemo #(todos/current-user))
        signed-in (s/signal nil)
        user #(or @signed-in (cookie-user))
        title (s/signal "")
        [adding set-adding] (createOptimistic [])
        saved (s/signal nil)
        add (action (^:gen fn [t]
                      (set-adding #(conj % t))
                      (reset! saved (js-yield (todos/add-todo t)))))
        submit (fn [e]
                 (.preventDefault e)
                 (when (seq @title)
                   (add @title)
                   (reset! title "")))
        SharedList (dynamicComponent #(todos/SharedList))]
    #jsx [:section
          [:h2 "Shared todos"]
          [Loading {:fallback #jsx [:p {:class "muted"} "Loading..."]}
           [Show {:when (user)
                  :fallback #jsx [SignInForm {:onSignIn #(reset! signed-in %)}]}
            [:div
             [:p {:class "muted"} "Signed in as " [:strong (user)]
              ". Open this page in another window to see changes arrive."]
             [:form {:onSubmit submit}
              [:input {:type "text" :placeholder "What needs doing?" :value @title
                       :onInput #(reset! title (.. % -target -value))}]
              " "
              [:button "Add"]]]]
           [SharedList {:check Check :actions Actions}]]
          [:ul
           [For {:each (adding)}
            (fn [t] #jsx [:li {:class "muted"} t " (saving)"])]]
          [Show {:when @saved}
           [:p {:class "muted"}
            "Saved \u201c" (:title @saved) "\u201d at " (todos/time-text (:created-at @saved) true)]]]))

(defn App []
  #jsx [:main
        [:h1 "Solid with Squint"]
        [SharedTodos]
        [Counter]
        [Todos]
        [Greeting]])

(def default App)
