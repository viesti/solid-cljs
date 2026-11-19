(ns hydration.client-test
  (:require [solid.hydration-check :as check]
            ["vitest" :refer [test]]))

(test
  "Hydration adopts settled server HTML and makes it interactive"
  (fn []
    (-> (check/fixture)
        (.then (fn [^js f] (check/check-hydration! (.-html f) (.-script f)))))))
