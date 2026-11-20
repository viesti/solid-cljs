(ns vitest.router
  (:require
   [solid.core :as s :refer [$ defui]]
   ["@solidjs/router" :refer [createRouter memoryHistory]]
   [solid.router :as router]
   ["@solidjs/testing-library" :refer [render]]
   ["vitest" :refer [expect test]]))

(defn- wait [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defui home [] ($ :p#page "Home page"))
(defui about [] ($ :p#page "About page"))

(test
 "Router 2.0 claims anchors that $ creates: active links and navigation"
 (fn []
   (let [router (createRouter #js {:routes #js [#js {:path "/" :component home}
                                                #js {:path "/about" :component about}]
                                   :history (memoryHistory "/")})
         about-href (s/signal "/about")
         c (.-container
            (render ($ router
                       (fn [props]
                         ($ :div
                            ($ :nav
                               ($ :a#home {:href "/"} "Home")
                               ($ :a#about {:href @about-href} "About"))
                            (.-children ^js props))))))
         q #(.querySelector c %)]
     (-> (wait 20)
         (.then (fn []
                  (.. (expect (.-textContent (q "#page"))) (toBe "Home page"))
                  (.. (expect (.getAttribute (q "#home") "aria-current")) (toBe "page"))
                  (.. (expect (.getAttribute (q "#about") "aria-current")) (toBe nil))
                  (.click (q "#about"))
                  (wait 20)))
         (.then (fn []
                  (.. (expect (.-textContent (q "#page"))) (toBe "About page"))
                  (.. (expect (.getAttribute (q "#about") "aria-current")) (toBe "page"))
                  (.. (expect (.getAttribute (q "#home") "aria-current")) (toBe nil))))))))

(def ^:private fetches (atom 0))

(def ^:private user-name
  (router/query (fn [id] (swap! fetches inc) (js/Promise.resolve (str "user " id " #" @fetches))) "user-name"))

(defui user-page []
  (let [params (router/use-params)
        navigate (router/use-navigate)
        label (s/memo #(user-name (.-id params)))]
    ($ :div
       (s/loading ($ :p "loading") ($ :p#user (label)))
       ($ :button#next {:on-click #(navigate (str "/users/" (inc (js/parseInt (.-id params)))))} "next"))))

(test
 "solid.router: route maps, params, navigation and queries"
 (fn []
   (let [app-router (router/router [{:path "/" :component home}
                                    {:path "/users/:id" :component user-page}]
                                   {:history (router/memory-history "/users/1")})
         c (.-container (render ($ app-router (fn [props] ($ :main (.-children ^js props))))))
         q #(.querySelector c %)]
     (-> (wait 30)
         (.then (fn []
                  (.. (expect (.-textContent (q "#user"))) (toBe "user 1 #1"))
                  (.click (q "#next"))
                  (wait 30)))
         (.then (fn []
                  (.. (expect (.-textContent (q "#user"))) (toBe "user 2 #2"))
                  (router/revalidate "user-name")
                  (wait 30)))
         (.then (fn []
                  (.. (expect (.-textContent (q "#user"))) (toBe "user 2 #3"))))))))
