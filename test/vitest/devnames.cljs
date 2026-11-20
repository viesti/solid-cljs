(ns vitest.devnames
  (:require
   [solid.core :as s :refer [$ defui]]
   ["@solidjs/testing-library" :refer [render]]
   ["vitest" :refer [expect test]]))

(defui reads-in-body
  {:solid/lint false}
  [props]
  ;; Reading a prop at the top of the body makes Solid's development build warn
  (let [v (:label props)]
    ($ :p v)))

(test
 "Solid's diagnostics name components by their ClojureScript name"
 (fn []
   (let [warnings (atom [])
         warn (.-warn js/console)
         label (s/signal "a")]
     (set! (.-warn js/console) (fn [& xs] (swap! warnings conj (apply str xs))))
     (render ($ :div ($ reads-in-body {:label @label})))
     (set! (.-warn js/console) warn)
     (.. (expect (apply str @warnings)) (toContain "vitest.devnames/reads-in-body")))))
