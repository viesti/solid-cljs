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

(test
  "Optimistic writes in an action show at once and revert when it settles"
  (fn []
    (let [name (s/optimistic "Ann")
          rename (s/action [to]
                   (reset! name to)
                   (s/yield (wait 30))
                   :done)
          c (mount ($ :p @name))]
      (.. (expect (.-textContent c)) (toBe "Ann"))
      (let [result (rename "Bo")]
        (-> (wait 5)
            (.then (fn [] (.. (expect (.-textContent c)) (toBe "Bo"))))
            (.then (fn [] result))
            (.then (fn [r]
                     (.. (expect (= :done r)) (toBe true))
                     (wait 5)))
            (.then (fn [] (.. (expect (.-textContent c)) (toBe "Ann")))))))))

(test
  "Yields bind settled values, and a rejection rejects the action"
  (fn []
    (let [double (s/action [x]
                   (s/yield [d (js/Promise.resolve (* 2 x))])
                   (s/yield [e (js/Promise.resolve (+ d 1))])
                   [d e])
          fail (s/action []
                 (s/yield (js/Promise.reject (js/Error. "nope")))
                 :unreachable)]
      (-> (double 5)
          (.then (fn [r] (.. (expect (clj->js r)) (toEqual #js [10 11]))))
          (.then (fn [] (fail)))
          (.then (fn [_] (throw (js/Error. "should have rejected")))
                 (fn [e] (.. (expect (.-message e)) (toBe "nope"))))))))

(test
  "A keyword child renders its name, as on the server"
  (fn []
    (let [k (s/signal :one)
          c (mount ($ :p @k))]
      (.. (expect (.-textContent c)) (toBe "one"))
      (reset! k :two)
      (s/flush)
      (.. (expect (.-textContent c)) (toBe "two")))))

(defui lazy-target [{:keys [label]}]
  ($ :b label))

(test
  "lazy accepts a promise of a component or of a module"
  (fn []
    (let [from-fn (s/lazy (js/Promise.resolve lazy-target))
          from-module (s/lazy (js/Promise.resolve #js {:default lazy-target}))
          c (mount ($ :div (s/loading ($ :i "loading")
                             ($ from-fn {:label "a"})
                             ($ from-module {:label "b"}))))]
      (-> (wait 20)
          (.then (fn [] (.. (expect (.-textContent c)) (toBe "ab"))))))))

(test
  "s/yield works in let bindings, branches, when and call arguments"
  (fn []
    (let [log (atom [])
          step (fn [x] (js/Promise. (fn [resolve] (js/setTimeout #(do (swap! log conj x) (resolve x)) 5))))
          act (s/action [flag]
                (let [a (s/yield (step :a))
                      b (+ 1 (s/yield (step 1)))]
                  (if flag
                    (swap! log conj (s/yield (step :then)))
                    (swap! log conj (s/yield (step :else))))
                  (when flag
                    (s/yield (step :when)))
                  (swap! log conj :after-if)
                  (vector a b (s/yield (step :x)) (s/yield (step :y)))))
          act-not (s/action []
                    (if-not (s/yield (step false)) :no :yes))
          failing (s/action []
                    (let [x (s/yield (step :before))]
                      (let [y (s/yield (js/Promise.reject (js/Error. (str "fail " (name x)))))]
                        y)))]
      (-> (act true)
          (.then (fn [r]
                   (.. (expect (= [:a 2 :x :y] r)) (toBe true))
                   (.. (expect (= [:a 1 :then :then :when :after-if :x :y] @log)) (toBe true))
                   (reset! log [])
                   (act false)))
          (.then (fn [r]
                   (.. (expect (= [:a 2 :x :y] r)) (toBe true))
                   (.. (expect (= [:a 1 :else :else :after-if :x :y] @log)) (toBe true))
                   (act-not)))
          (.then (fn [r]
                   (.. (expect (= :no r)) (toBe true))
                   (failing)))
          (.then (fn [_] (throw (js/Error. "should have rejected")))
                 (fn [e] (.. (expect (.-message e)) (toBe "fail before"))))))))

(test
  "Signal, memo and effect options"
  (fn []
    (let [unobserved (atom 0)
          computed (atom 0)
          errors (atom [])
          shown (s/signal true)
          watched (s/signal 1 {:unobserved #(swap! unobserved inc)})
          lazy-memo (s/root (fn [_] (s/memo #(do (swap! computed inc) 42) {:lazy true})))
          c (mount ($ :div
                      ($ (fn [_]
                           (let [counter (s/signal 0 {:owned-write true})]
                             (reset! counter 1)
                             ($ :p#owned @counter))))
                      (s/if @shown ($ :p#watched @watched) "hidden")))]
      (s/root (fn [_]
                (s/effect (fn [] (js/Promise.reject (js/Error. "async failure")))
                          (fn [_] nil)
                          {:error (fn [e _] (swap! errors conj (.-message e)))})))
      (.. (expect (.-textContent (q c "#owned"))) (toBe "1"))
      (.. (expect @computed) (toBe 0))
      (.. (expect (lazy-memo)) (toBe 42))
      (.. (expect @computed) (toBe 1))
      (reset! shown false)
      (s/flush)
      (-> (wait 20)
          (.then (fn []
                   (.. (expect @unobserved) (toBe 1))
                   (.. (expect (clj->js @errors)) (toEqual #js ["async failure"]))))))))

(def required-ctx (s/create-context))

(defui needs-ctx []
  ($ :b (s/use-context required-ctx)))

(test
  "A context without a default works under a provider and throws without one"
  (fn []
    (let [c (mount ($ :div
                      ($ required-ctx {:value "given"} ($ needs-ctx))
                      (s/try ($ needs-ctx) (catch _ ($ :i "missing")))))]
      (.. (expect (.-textContent c)) (toBe "givenmissing")))))
