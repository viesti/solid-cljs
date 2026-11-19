(ns vitest.template
    (:require-macros [solid.core])
    (:require
     [solid.core :as s :refer [$ defui]]
     ["@solidjs/testing-library" :refer [render]]
     ["vitest" :refer [expect test]]))

(defn- mount [el]
       (.-container (render (fn [] el))))

(defn- q [^js c selector]
       (.querySelector c selector))

(test
 "Static and dynamic children keep their order"
 (fn []
     (let [a (s/signal "A")
           c (mount ($ :p "x" @a "y" ($ :b "z") @a))]
          (.. (expect (.-textContent c)) (toBe "xAyzA"))
          (reset! a "B")
          (s/flush)
          (.. (expect (.-textContent c)) (toBe "xByzB")))))

(test
 "Tag shorthand merges with static and dynamic classes"
 (fn []
     (let [on (s/signal true)
           c (mount ($ :div
                       ($ :span#one.a.b {:class :c})
                       ($ :span#two.a {:class {:on @on}})))]
          (.. (expect (.-className (q c "#one"))) (toBe "a b c"))
          (.. (expect (.-className (q c "#two"))) (toBe "a on"))
          (reset! on false)
          (s/flush)
          (.. (expect (.-className (q c "#two"))) (toBe "a")))))

(test
 "Delegated and non-delegated events fire"
 (fn []
     (let [clicks (atom 0)
           focuses (atom 0)
           c (mount ($ :div
                       ($ :button {:on-click #(swap! clicks inc)} "b")
                       ($ :input {:on-focus #(swap! focuses inc)})))]
          (.click (q c "button"))
          (.dispatchEvent (q c "input") (js/FocusEvent. "focus"))
          (.. (expect @clicks) (toBe 1))
          (.. (expect @focuses) (toBe 1)))))

(test
 "DOM properties and boolean attributes"
 (fn []
     (let [v (s/signal "a")
           off (s/signal true)
           c (mount ($ :div
                       ($ :input#in {:value @v :disabled @off :required true :hidden false})
                       ($ :div#html {:inner-html "<i>i</i>"})))
           input (q c "#in")]
          (.. (expect (.-value input)) (toBe "a"))
          (.. (expect (.hasAttribute input "disabled")) (toBe true))
          (.. (expect (.hasAttribute input "required")) (toBe true))
          (.. (expect (.hasAttribute input "hidden")) (toBe false))
          (.. (expect (.-innerHTML (q c "#html"))) (toBe "<i>i</i>"))
          (reset! v "b")
          (reset! off false)
          (s/flush)
          (.. (expect (.-value input)) (toBe "b"))
          (.. (expect (.hasAttribute input "disabled")) (toBe false)))))

(defn- title-directive [el value]
       (set! (.-title el) (value)))

(test
 "Refs and directives receive the element"
 (fn []
     (let [r (s/ref)
           c (mount ($ :div ($ :span {:ref r title-directive "hi"} "x")))]
          (.. (expect (.-tagName @r)) (toBe "SPAN"))
          (.. (expect (.-title (q c "span"))) (toBe "hi")))))

(defui hand [props]
       ($ :line {:stroke-width (:width props)}))

(test
 "SVG elements at a template root are created in the SVG namespace"
 (fn []
     (let [c (mount ($ :svg ($ hand {:width 3})))
           line (q c "line")]
          (.. (expect (.-namespaceURI line)) (toBe "http://www.w3.org/2000/svg"))
          (.. (expect (.getAttribute line "stroke-width")) (toBe "3")))))

(test
 "Dynamic style objects get units and update"
 (fn []
     (let [w (s/signal 10)
           c (mount ($ :div {:style {:width @w :opacity 0.5}}))
           style (.-style (.-firstChild c))]
          (.. (expect (.-width style)) (toBe "10px"))
          (.. (expect (.-opacity style)) (toBe "0.5"))
          (reset! w 20)
          (s/flush)
          (.. (expect (.-width style)) (toBe "20px")))))

(test
 "Text is escaped in the template"
 (fn []
     (let [c (mount ($ :p {:title "a\"b"} "<b>&</b>"))]
          (.. (expect (.-textContent c)) (toBe "<b>&</b>"))
          (.. (expect (.-title (q c "p"))) (toBe "a\"b")))))

(test
 "$h creates the same elements through hyperscript"
 (fn []
     (let [n (s/signal 1)
           c (mount (s/$h :p {:class :x} "n=" @n))]
          (.. (expect (.-outerHTML (q c "p"))) (toBe "<p class=\"x\">n=1</p>"))
          (reset! n 2)
          (s/flush)
          (.. (expect (.-textContent c)) (toBe "n=2")))))
