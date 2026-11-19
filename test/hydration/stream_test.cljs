(ns hydration.stream-test
  (:require [solid.hydration-check :as check]
            ["vitest" :refer [test]]))

(test
  "Hydration adopts streamed server HTML once its content has been revealed"
  (fn []
    (-> (check/fixture)
        (.then (fn [^js f] (check/check-hydration! (.-stream f) (.-script f)))))))
