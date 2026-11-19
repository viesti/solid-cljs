(ns hydration.client-test
  (:require [solid.core :as s :refer [$]]
            [solid.hydration-app :refer [app]]
            ["vitest" :refer [expect test]]))

(defn- run-script!
  "Runs the inline script of the HTML `html`, as the head of a server-rendered page would."
  [html]
  (let [t (js/document.createElement "template")
        script (js/document.createElement "script")]
    (set! (.-innerHTML t) html)
    (set! (.-textContent script) (.-textContent (.querySelector (.-content t) "script")))
    (.appendChild js/document.head script)))

(defn- q [^js c selector]
  (.querySelector c selector))

(test
  "Hydration adopts the server's DOM and makes it interactive"
  (fn []
    (-> (js/fetch "/out/hydration.json")
        (.then #(.json %))
        (.then
          (fn [^js fixture]
            (run-script! (.-script fixture))
            (let [container (js/document.createElement "div")
                  _ (set! (.-innerHTML container) (.-html fixture))
                  _ (.appendChild js/document.body container)
                  server-button (q container "#counter button")
                  server-span (q container "#counter span")
                  server-li (q container "#todos li")
                  errors (atom [])
                  log-error (.-error js/console)]
              (set! (.-error js/console) (fn [& xs] (swap! errors conj (apply str xs)) (apply log-error xs)))
              (s/hydrate ($ app) container)
              (s/flush)
              (set! (.-error js/console) log-error)
              (.. (expect (clj->js @errors)) (toEqual #js []))
              (.. (expect (identical? server-button (q container "#counter button"))) (toBe true))
              (.. (expect (identical? server-span (q container "#counter span"))) (toBe true))
              (.. (expect (identical? server-li (q container "#todos li"))) (toBe true))
              (.. (expect (.-textContent server-span)) (toBe "count is 0!"))
              (.click server-button)
              (.click server-button)
              (s/flush)
              (.. (expect (.-textContent server-span)) (toBe "count is 2!"))
              (.. (expect (.getAttribute server-span "title")) (toBe "count 2"))
              (.click (q container "#add"))
              (s/flush)
              (.. (expect (.-textContent (q container "#todos ul"))) (toBe "abitem2"))
              (.. (expect (.-textContent (q container "#toggle p"))) (toBe "closed"))
              (.click (q container "#toggle button"))
              (s/flush)
              (.. (expect (.-textContent (q container "#toggle p"))) (toBe "open"))
              (.. (expect (.-textContent (q container "article"))) (toBe "Cardbody"))
              (.. (expect (.-length (.-children container))) (toBe 1))))))))
