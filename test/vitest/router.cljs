(ns vitest.router
  (:require
   [solid.core :as s :refer [$ defui]]
   ["@solidjs/router" :refer [createRouter memoryHistory]]
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
