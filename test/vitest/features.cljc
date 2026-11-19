(ns vitest.features
  (:require-macros [solid.core])
  (:require
    [solid.core :as s :refer [$ defui]]
    ["@solidjs/testing-library" :refer [render]]
    ["vitest" :refer [expect test]]))

;; An element created with `$` is already the function that render expects
(defn- mount [el]
  (.-container (render el)))

(defn- q [^js c selector]
  (.querySelector c selector))

(defn- wait [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defui greeting [{:keys [name punctuation] :or {punctuation "!"}}]
  (let [shout (fn [] (str name punctuation))]
    ($ :p ($ :span.plain name) ($ :span.fn (shout)))))

(test
  "Destructured props stay reactive, also inside functions and with defaults"
  (fn []
    (let [n (s/signal "Ann")
          c (mount ($ greeting {:name @n}))]
      (.. (expect (.-textContent (q c ".plain"))) (toBe "Ann"))
      (.. (expect (.-textContent (q c ".fn"))) (toBe "Ann!"))
      (reset! n "Bo")
      (s/flush)
      (.. (expect (.-textContent (q c ".plain"))) (toBe "Bo"))
      (.. (expect (.-textContent (q c ".fn"))) (toBe "Bo!")))))

(defui shadowed
  {:solid/lint false}
  [{:keys [label]}]
  (let [label (str label "?")]
    ($ :span label)))

(test
  "A prop name that the body binds again keeps the inner binding"
  (fn []
    (let [c (mount ($ shadowed {:label "x"}))]
      (.. (expect (.-textContent c)) (toBe "x?")))))

(test
  "pending? reports an async memo that is refetching"
  (fn []
    (let [lang (s/signal :en)
          c (mount ($ (fn [_]
                        (let [text (s/memo (fn []
                                             (let [l @lang]
                                               (js/Promise. (fn [resolve] (js/setTimeout #(resolve (name l)) 30))))))]
                          (s/loading ($ :p "loading")
                            ($ :p {:class {:pending (s/pending? (text))}} (text)))))))]
      (-> (wait 80)
          (.then (fn []
                   (.. (expect (.-textContent c)) (toBe "en"))
                   (.. (expect (.-className (q c "p"))) (toBe ""))
                   (reset! lang :fi)
                   (s/flush)))
          (.then #(wait 5))
          (.then (fn []
                   (.. (expect (.-className (q c "p"))) (toBe "pending"))
                   (.. (expect (.-textContent c)) (toBe "en"))))
          (.then #(wait 80))
          (.then (fn []
                   (.. (expect (.-textContent c)) (toBe "fi"))
                   (.. (expect (.-className (q c "p"))) (toBe ""))))))))

(test
  "A vector of refs calls each one"
  (fn []
    (let [a (atom nil) b (atom nil)
          _ (mount ($ :div {:ref [#(reset! a %) #(reset! b %)]}))]
      (.. (expect (.-tagName @a)) (toBe "DIV"))
      (.. (expect (identical? @a @b)) (toBe true)))))

(test
  "for and index take a fallback, and for takes a key function"
  (fn []
    (let [xs (s/signal [])
          c (mount ($ :div
                      ($ :ul#f (s/for [[x] @xs :fallback ($ :li "none")] ($ :li (:id x))))
                      ($ :ul#k (s/for [[x] @xs :key :id] ($ :li (:label @x))))
                      ($ :ul#i (s/index [[x] @xs :fallback ($ :li "empty")] ($ :li (:id @x))))))]
      (.. (expect (.-textContent (q c "#f"))) (toBe "none"))
      (.. (expect (.-textContent (q c "#i"))) (toBe "empty"))
      (reset! xs [{:id 1 :label "a"} {:id 2 :label "b"}])
      (s/flush)
      (.. (expect (.-textContent (q c "#f"))) (toBe "12"))
      (.. (expect (.-textContent (q c "#k"))) (toBe "ab"))
      (.. (expect (.-textContent (q c "#i"))) (toBe "12"))
      (let [first-li (q c "#k li")]
        (reset! xs [{:id 1 :label "A"} {:id 2 :label "b"}])
        (s/flush)
        (.. (expect (.-textContent (q c "#k"))) (toBe "Ab"))
        (.. (expect (identical? first-li (q c "#k li"))) (toBe true))))))

(test
  "repeat renders a count and reveal wraps loading boundaries"
  (fn []
    (let [n (s/signal 2)
          c (mount ($ :div
                      ($ :ul (s/repeat [i @n :fallback ($ :li "zero")] ($ :li i)))
                      (s/reveal {:order :together} (s/loading ($ :i "…") ($ :b "r")))))]
      (.. (expect (.-textContent c)) (toBe "01r"))
      (reset! n 0)
      (s/flush)
      (.. (expect (.-textContent c)) (toBe "zeror")))))

(test
  "loading with :on shows the fallback again while new content loads"
  (fn []
    (let [id (s/signal 1)
          c (mount ($ (fn [_]
                        (let [data (s/memo (fn []
                                             (let [i @id]
                                               (js/Promise. (fn [resolve] (js/setTimeout #(resolve (str "item" i)) 30))))))]
                          (s/loading {:fallback ($ :p "loading") :on @id}
                            ($ :p (data)))))))]
      (-> (wait 80)
          (.then (fn []
                   (.. (expect (.-textContent c)) (toBe "item1"))
                   (reset! id 2)
                   (s/flush)))
          (.then #(wait 5))
          (.then (fn [] (.. (expect (.-textContent c)) (toBe "loading"))))
          (.then #(wait 80))
          (.then (fn [] (.. (expect (.-textContent c)) (toBe "item2"))))))))

(test
  "Spreading a map onto an element, with explicit attributes winning"
  (fn []
    (let [inputs (atom 0)
          title (s/signal "t1")
          c (mount ($ :div
                      ($ :input#a {:& {:type :text :placeholder "spread" :class [:x :y] :on-input #(swap! inputs inc)}
                                   :placeholder "own"})
                      ($ :p#b {:& {:title @title :data-k "v"}} "p")))
          a (q c "#a")
          b (q c "#b")]
      (.. (expect (.getAttribute a "type")) (toBe "text"))
      (.. (expect (.getAttribute a "placeholder")) (toBe "own"))
      (.. (expect (.-className a)) (toBe "x y"))
      (.dispatchEvent a (js/Event. "input" #js {:bubbles true}))
      (.. (expect @inputs) (toBe 1))
      (.. (expect (.getAttribute b "title")) (toBe "t1"))
      (.. (expect (.getAttribute b "data-k")) (toBe "v"))
      (reset! title "t2")
      (s/flush)
      (.. (expect (.getAttribute b "title")) (toBe "t2")))))

(defui field [props]
  ($ :label (:label props) ($ :input {:& (s/omit-props props :label)})))

(defui badge [props]
  (let [props (s/merge-props {:tone "info"} props)]
    ($ :span {:class (:tone props)} (:label props))))

(test
  "Forwarding props to elements and components"
  (fn []
    (let [v (s/signal "a")
          c (mount ($ :div
                      ($ field {:label "L" :aria-label "lbl" :value @v :stroke-width 2})
                      ($ badge {:label "plain"})
                      ($ badge {:& {:label "spread" :tone "warn"} :label "own"})))
          input (q c "input")
          badges (.querySelectorAll c "span")]
      (.. (expect (.-textContent (q c "label"))) (toBe "L"))
      (.. (expect (.hasAttribute input "label")) (toBe false))
      (.. (expect (.getAttribute input "aria-label")) (toBe "lbl"))
      (.. (expect (.getAttribute input "stroke-width")) (toBe "2"))
      (.. (expect (.-value input)) (toBe "a"))
      (reset! v "b")
      (s/flush)
      (.. (expect (.-value input)) (toBe "b"))
      (.. (expect (.-className (aget badges 0))) (toBe "info"))
      (.. (expect (.-textContent (aget badges 1))) (toBe "own"))
      (.. (expect (.-className (aget badges 1))) (toBe "warn")))))
