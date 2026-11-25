(ns solid.frame-fixtures
  "Fixtures and DOM helpers for the server component tests, which read what hydration.server
  recorded."
  (:require [cljs.reader :as reader]))

(def requests (atom []))

(defn serve-frames!
  "Answers server function calls with the frame streams in `frames`, by the call's filter."
  [^js frames]
  (set! (.-fetch js/globalThis)
        (fn [_ ^js init]
          (let [[filter] (reader/read-string (.-body init))
                ^js frame (aget frames filter)]
            (swap! requests conj filter)
            (js/Promise.resolve (js/Response. (.-body frame) #js {:headers (.-headers frame)}))))))

(def fetch (.-fetch js/globalThis))

(defn fixture []
  (-> (fetch "/out/server-components.json") (.then #(.json %))))

(defn run-scripts!
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

(defn until
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

(defn texts [^js container selector]
  (into-array (map #(.-textContent %) (array-seq (.querySelectorAll container selector)))))

(defn button [^js container id]
  (some #(when (.startsWith (.-textContent %) (str id ":")) %)
        (array-seq (.querySelectorAll container "button.edit"))))

(defn serve-live!
  "Answers the live call with the first part of the recorded stream, and returns a function
  that sends the rest, as the server does once the title changes."
  [^js live]
  (let [controller (atom nil)
        encode #(.encode (js/TextEncoder.) %)
        body (js/ReadableStream. #js {:start (fn [c]
                                               (reset! controller c)
                                               (.enqueue c (encode (aget (.-parts live) 0))))})]
    (set! (.-fetch js/globalThis)
          (fn [address _]
            (swap! requests conj (str address))
            (js/Promise.resolve (js/Response. body #js {:headers (.-headers live)}))))
    #(.enqueue @controller (encode (aget (.-parts live) 1)))))

(defn serve-single-frame!
  "Answers the next server component request with `frame`."
  [^js frame]
  (set! (.-fetch js/globalThis)
        (fn [address _]
          (swap! requests conj (str address))
          (js/Promise.resolve (js/Response. (.-body frame) #js {:headers (.-headers frame)})))))

(defn serve-live-reconnect!
  "Answers the first live request with the initial frame (`parts[0]`) and returns a function
  that drops that stream. The second request receives `reconnect`, a fresh frame with the updated
  title."
  [^js live]
  (let [controller (atom nil)
        attempt (atom 0)
        encode #(.encode (js/TextEncoder.) %)]
    (set! (.-fetch js/globalThis)
          (fn [address _]
            (let [n (swap! attempt inc)
                  text (if (= 1 n) (aget (.-parts live) 0) (.-reconnect live))
                  body (js/ReadableStream.
                        #js {:start (fn [c]
                                      (when (= 1 n) (reset! controller c))
                                      (.enqueue c (encode text)))})]
              (swap! requests conj (str address))
              (js/Promise.resolve (js/Response. body #js {:headers (.-headers live)})))))
    (fn [] (.error @controller (js/Error. "dropped")))))
