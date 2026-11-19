(ns solid.hydration-check
  "Hydrates HTML that the server rendered from solid.hydration-app and checks the result, for
  the hydration tests."
  (:require [solid.core :as s :refer [$]]
            [solid.hydration-app :refer [app]]
            ["vitest" :refer [expect]]))

(defn- run-script!
  "Runs the inline script of the HTML `html`, as the head of a server-rendered page would."
  [html]
  (let [t (js/document.createElement "template")
        script (js/document.createElement "script")]
    (set! (.-innerHTML t) html)
    (set! (.-textContent script) (.-textContent (.querySelector (.-content t) "script")))
    (.appendChild js/document.head script)))

(defn- run-inline-scripts!
  "Runs the scripts inside `container` in document order, as a browser does while it receives
  the page. Scripts set through innerHTML do not run otherwise."
  [container]
  (doseq [old (array-seq (.querySelectorAll container "script"))]
    (let [new (js/document.createElement "script")]
      (set! (.-textContent new) (.-textContent old))
      (.replaceWith old new))))

(defn- q [^js c selector]
  (.querySelector c selector))

(defn fixture
  "Returns a promise of the server output that hydration.server wrote."
  []
  (-> (js/fetch "/out/hydration.json")
      (.then #(.json %))))

(defn check-hydration!
  "Loads `html` with the hydration script `script`, hydrates solid.hydration-app into it, and
  checks that the server's nodes were adopted and respond to updates."
  [html script]
  (run-script! script)
  (let [container (js/document.createElement "div")
        _ (set! (.-innerHTML container) html)
        _ (.appendChild js/document.body container)
        _ (run-inline-scripts! container)
        server-async (q container "#async p")
        server-button (q container "#counter button")
        server-span (q container "#counter span")
        server-li (q container "#todos li")
        errors (atom [])
        log-error (.-error js/console)]
    (.. (expect (.-textContent server-async)) (toBe "Moi, world"))
    (set! (.-error js/console) (fn [& xs] (swap! errors conj (apply str xs)) (apply log-error xs)))
    (s/hydrate ($ app) container)
    (s/flush)
    (set! (.-error js/console) log-error)
    (.. (expect (clj->js @errors)) (toEqual #js []))
    (.. (expect (identical? server-button (q container "#counter button"))) (toBe true))
    (.. (expect (identical? server-span (q container "#counter span"))) (toBe true))
    (.. (expect (identical? server-li (q container "#todos li"))) (toBe true))
    (.. (expect (identical? server-async (q container "#async p"))) (toBe true))
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
    (.. (expect (.-textContent (q container "#async"))) (toBe "Moi, world"))))
