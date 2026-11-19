(ns vitest.refresh
  (:require
    [solid.core :as s :refer [$]]
    ["@solidjs/testing-library" :refer [render]]
    ["vitest" :refer [expect test]]))

(defn- wait [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(test
  "A reloaded component definition replaces running instances and keeps the parent's state"
  (fn []
    (let [child (s/-refresh-component "vitest.refresh/child" (fn [_] ($ :p#child "version 1")) nil)
          clicks (s/signal 0)
          c (.-container (render ($ :div
                                    ($ :button {:on-click #(swap! clicks inc)} @clicks)
                                    ($ child))))
          button (.querySelector c "button")]
      (.click button)
      (s/flush)
      (.. (expect (.-textContent c)) (toBe "1version 1"))
      ;; What a hot reload of the child's namespace evaluates
      (s/-refresh-component "vitest.refresh/child" (fn [_] ($ :p#child "version 2")) nil)
      (-> (wait 10)
          (.then (fn []
                   (.. (expect (.-textContent (.querySelector c "#child"))) (toBe "version 2"))
                   (.. (expect (.-textContent button)) (toBe "1"))
                   (.. (expect (identical? button (.querySelector c "button"))) (toBe true))))))))
