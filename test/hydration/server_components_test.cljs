(ns hydration.server-components-test
  "Adopts a server component that hydration.server rendered into a document, and renders one
  from the frame streams it recorded."
  (:require [cljs.reader :as reader]
            [solid.core :as s :refer [$]]
            [solid.server-component-app :as app]
            [solid.server-components :as sc]
            ["vitest" :refer [expect test]]))

(def todo-list (sc/-reference "hydration/server/todo-list" {}))

(def requests (atom []))

(defn- serve-frames!
  "Answers server function calls with the frame streams in `frames`, by the call's filter."
  [^js frames]
  (set! (.-fetch js/globalThis)
        (fn [_ ^js init]
          (let [[filter] (reader/read-string (.-body init))
                ^js frame (aget frames filter)]
            (swap! requests conj filter)
            (js/Promise.resolve (js/Response. (.-body frame) #js {:headers (.-headers frame)}))))))

(def ^:private fetch (.-fetch js/globalThis))

(defn- fixture []
  (-> (fetch "/out/server-components.json") (.then #(.json %))))

(defn- run-scripts!
  "Runs the hydration script in the head and then the scripts inside `container`, as a browser
  does while it receives the page. Scripts set through innerHTML do not run otherwise."
  [script container]
  (let [t (js/document.createElement "template")
        head-script (js/document.createElement "script")]
    (set! (.-innerHTML t) script)
    (set! (.-textContent head-script) (.-textContent (.querySelector (.-content t) "script")))
    (.appendChild js/document.head head-script)
    (doseq [old (array-seq (.querySelectorAll container "script"))]
      (let [new (js/document.createElement "script")]
        (set! (.-textContent new) (.-textContent old))
        (.replaceWith old new)))))

(defn- until
  "Returns a promise that resolves once `(pred)` is true, checking every 10 ms for a second."
  [pred]
  (js/Promise.
    (fn [resolve reject]
      (let [start (js/Date.now)]
        ((fn check []
           (cond
             (pred) (resolve true)
             (< 1000 (- (js/Date.now) start)) (reject (js/Error. "timed out"))
             :else (js/setTimeout check 10))))))))

(defn- texts [^js container selector]
  (into-array (map #(.-textContent %) (array-seq (.querySelectorAll container selector)))))

(defn- button [^js container id]
  (some #(when (.startsWith (.-textContent %) (str id ":")) %)
        (array-seq (.querySelectorAll container "button.edit"))))

(test
  "The browser adopts a server component from the document without a request"
  (fn []
    (-> (fixture)
        (.then (fn [^js f]
                 (serve-frames! (.-frames f))
                 (reset! requests [])
                 (let [container (js/document.createElement "div")]
                   (set! (.-innerHTML container) (.-html f))
                   (.appendChild js/document.body container)
                   (run-scripts! (.-script f) container)
                   ;; As a page's entry script does, after the body it adopts; the second test reuses it
                   (sc/install!)
                   (let [server-button (button container "a")
                         hydrated (js/Promise. (fn [resolve] (s/on-hydration-end resolve)))]
                     (s/hydrate ($ app/page {:todo-list todo-list :filter (s/signal "all")}) container)
                     (-> hydrated
                         (.then (fn []
                                  (.. (expect (identical? server-button (button container "a"))) (toBe true))
                                  (.click server-button)
                                  (s/flush)
                                  (.. (expect (texts container "button.edit")) (toEqual #js ["a:1" "b:0"]))
                                  (.. (expect (texts container "i")) (toEqual #js ["adding"]))
                                  (.. (expect (clj->js @requests)) (toEqual #js []))))))))))))

(test
  "A streamed server component keeps keyed fills across a refetch"
  (fn []
    (-> (fixture)
        (.then (fn [^js f]
                 (serve-frames! (.-frames f))
                 (reset! requests [])
                 (let [container (js/document.createElement "div")
                       filter (s/signal "all")]
                   (.appendChild js/document.body container)
                   (s/render ($ app/page {:todo-list todo-list :filter filter}) container)
                   (-> (until #(button container "a"))
                       (.then (fn []
                                (.. (expect (texts container "li span")) (toEqual #js ["first" "second"]))
                                (.click (button container "a"))
                                (.click (button container "a"))
                                (s/flush)
                                (reset! filter "reversed")
                                (until #(= ["second" "first, renamed"] (vec (texts container "li span"))))))
                       (.then (fn []
                                (.. (expect (texts container "button.edit")) (toEqual #js ["b:0" "a:2"]))
                                (.. (expect (clj->js @requests)) (toEqual #js ["all" "reversed"])))))))))))
