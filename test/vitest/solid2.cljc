(ns vitest.solid2
    (:require-macros [solid.core])
    (:require
     [solid.core :as s :refer [$ defui]]
     ["@solidjs/testing-library" :refer [render]]
     ["vitest" :refer [expect test]]))

(defn- mount [el]
       (.-container (render (fn [] el))))

(defn- text [^js container selector]
       (.-textContent (.querySelector container selector)))

(test
 "Class map and vector compile to Solid 2 class values"
 (fn []
     (let [active (s/signal true)
           c (mount ($ :div
                       ($ :p#a {:class {:on @active :static true}})
                       ($ :p#b {:class [:x "y"]})))
           a (.querySelector c "#a")]
          (.. (expect (.-className a)) (toBe "on static"))
          (.. (expect (.-className (.querySelector c "#b"))) (toBe "x y"))
          (reset! active false)
          (s/flush)
          (.. (expect (.-className a)) (toBe "static")))))

(test
 "Style keys stay kebab-case and lengths get px"
 (fn []
     (let [size (s/signal 10)
           c (mount ($ :div {:style {:font-size @size :z-index 2 :line-height 1.5}}))
           style (.-style (.-firstChild c))]
          (.. (expect (.-fontSize style)) (toBe "10px"))
          (.. (expect (.-zIndex style)) (toBe "2"))
          (.. (expect (.-lineHeight style)) (toBe "1.5"))
          (reset! size 12)
          (s/flush)
          (.. (expect (.-fontSize style)) (toBe "12px")))))

(test
 "SVG attributes keep their kebab-case names"
 (fn []
     (let [c (mount ($ :svg ($ :line {:stroke-width 2})))]
          (.. (expect (.getAttribute (.querySelector c "line") "stroke-width")) (toBe "2")))))

(test
 "if-let binds the current value and falls back"
 (fn []
     (let [user (s/signal nil)
           c (mount ($ :div (s/if-let [u @user]
                                      ($ :span "Hello " u)
                                      ($ :span "Log in"))))]
          (.. (expect (.-textContent c)) (toBe "Log in"))
          (reset! user "Ann")
          (s/flush)
          (.. (expect (.-textContent c)) (toBe "Hello Ann")))))

(test
 "if-some renders false values"
 (fn []
     (let [v (s/signal false)
           c (mount ($ :div (s/if-some [x @v] ($ :span (str x)) ($ :span "none"))))]
          (.. (expect (.-textContent c)) (toBe "false"))
          (reset! v nil)
          (s/flush)
          (.. (expect (.-textContent c)) (toBe "none")))))

(test
 "for and index follow their collection"
 (fn []
     (let [xs (s/signal [1 2])
           c (mount ($ :div
                       ($ :ul#f (s/for [[x] @xs] ($ :li x)))
                       ($ :ul#i (s/index [[x] @xs] ($ :li @x)))))]
          (.. (expect (text c "#f")) (toBe "12"))
          (.. (expect (text c "#i")) (toBe "12"))
          (swap! xs conj 3)
          (s/flush)
          (.. (expect (text c "#f")) (toBe "123"))
          (.. (expect (text c "#i")) (toBe "123")))))

(defui broken []
       (throw (js/Error. "boom")))

(test
 "try renders the catch branch with the error"
 (fn []
     (let [c (mount ($ :div (s/try ($ broken)
                                   (catch e ($ :span (.-message e))))))]
          (.. (expect (.-textContent c)) (toBe "boom")))))

(test
 "Fragments render their children"
 (fn []
     (let [c (mount ($ :div ($ :<> ($ :b "a") ($ :i "b"))))]
          (.. (expect (.-textContent c)) (toBe "ab")))))

(defui label-view [props]
  ($ :span (:label props)))

(test
  "Component props are reactive"
  (fn []
    (let [label (s/signal "a")
          c (mount ($ :div ($ label-view {:label @label})))]
      (.. (expect (.-textContent c)) (toBe "a"))
      (reset! label "b")
      (s/flush)
      (.. (expect (.-textContent c)) (toBe "b")))))

(test
  "Effect callbacks may return any value in the development build"
  (fn []
    (let [n (s/signal 0)
          seen (atom [])
          c (mount ($ (fn [_]
                        (s/effect (fn [] @n) #(swap! seen conj %))
                        (s/effect #(deref n))
                        (s/on-settled #(swap! seen conj :settled))
                        ($ :span @n))))]
      (s/flush)
      (reset! n 1)
      (s/flush)
      (.. (expect (.-textContent c)) (toBe "1"))
      (.. (expect (clj->js @seen)) (toEqual #js [0 "settled" 1])))))
