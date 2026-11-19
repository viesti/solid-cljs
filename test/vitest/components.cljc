(ns vitest.components
    (:require-macros [solid.core])
    (:require
     [solid.core :as s :refer [$ defui]]
    ["solid-js" :as solid]
    ["@solidjs/web" :as sw]
     ["@solidjs/testing-library" :refer [render]]
     ["vitest" :refer [expect test]]))

(defn- mount [el]
       (.-container (render el)))

(defn- q [^js c selector]
       (.querySelector c selector))

;; A component written in JavaScript style, reading props by their JavaScript names
(defn- JsLink [^js props]
       ($ :a {:href (.-href props) :on-click (.-onClick props)} (.-children props)))

(test
 "JavaScript components receive camelCase props as getters"
 (fn []
     (let [href (s/signal "#a")
           clicks (atom 0)
           c (mount ($ :div ($ JsLink {:href @href :on-click #(swap! clicks inc)} "go")))
           a (q c "a")]
          (.. (expect (.getAttribute a "href")) (toBe "#a"))
          (.. (expect (.-textContent a)) (toBe "go"))
          (.click a)
          (.. (expect @clicks) (toBe 1))
          (reset! href "#b")
          (s/flush)
          (.. (expect (.getAttribute a "href")) (toBe "#b")))))

(defui labeled [{:keys [on-toggle aria-label] :as props}]
       ($ :button {:aria-label aria-label :on-click on-toggle}
          (:the-label props)))

(test
 "defui looks up kebab-case keys, including through destructuring"
 (fn []
     (let [toggles (atom 0)
           label (s/signal "one")
           c (mount ($ labeled {:on-toggle #(swap! toggles inc) :aria-label "lbl" :the-label @label}))
           b (q c "button")]
          (.click b)
          (.. (expect @toggles) (toBe 1))
          (.. (expect (.getAttribute b "aria-label")) (toBe "lbl"))
          (.. (expect (.-textContent b)) (toBe "one"))
          (reset! label "two")
          (s/flush)
          (.. (expect (.-textContent b)) (toBe "two")))))

(defui wrapper [props]
       ($ :section (:children props)))

(test
 "Children pass through as one child or several"
 (fn []
     (let [n (s/signal 1)
           c (mount ($ :div
                       ($ wrapper {} ($ :b "only"))
                       ($ wrapper {} "a" @n ($ :i "c"))))]
          (.. (expect (.-textContent c)) (toBe "onlya1c"))
          (reset! n 2)
          (s/flush)
          (.. (expect (.-textContent c)) (toBe "onlya2c")))))

(defn- RenderProp [^js props]
       ((.-children props) "value"))

(test
 "Function children are passed as is, for render props"
 (fn []
     (let [c (mount ($ :div ($ RenderProp (fn [v] ($ :span "got " v)))))]
          (.. (expect (.-textContent c)) (toBe "got value")))))

(defui cleaned [{:keys [log id]}]
       (s/on-cleanup #(swap! log conj id))
       ($ :li id))

(test
 "Rows and branches dispose their components when removed"
 (fn []
     (let [log (atom [])
           xs (s/signal [1 2 3])
           shown (s/signal true)
           c (mount ($ :div
                       ($ :ul (s/for [[x] @xs] ($ cleaned {:log log :id x})))
                       (s/if @shown ($ cleaned {:log log :id "branch"}) "hidden")))]
          (.. (expect (.-textContent c)) (toBe "123branch"))
          (swap! xs (fn [v] (vec (remove #{2} v))))
          (s/flush)
          (.. (expect (clj->js @log)) (toEqual #js [2]))
          (reset! shown false)
          (s/flush)
          (.. (expect (.-textContent c)) (toBe "13hidden"))
          (.. (expect (clj->js @log)) (toEqual #js [2 "branch"])))))

(test
 "Props of a defui component can be iterated"
 (fn []
     (let [seen (atom nil)
           probe (fn [props] (reset! seen (set (keys (s/-props props)))) nil)
           _ (mount ($ :div ($ probe {:on-click identity :x-y 1})))]
          (.. (expect (clj->js (sort @seen))) (toEqual #js ["on-click" "x-y"])))))

(test
  "Solid's own components read the props $ passes them"
  (fn []
    (let [on (s/signal false)
          target (js/document.createElement "div")
          c (mount ($ :div
                      ($ solid/Show {:when @on :fallback "off"} "on")
                      ($ sw/Portal {:mount target} ($ :b "portaled"))))]
      (.. (expect (.-textContent c)) (toBe "off"))
      (.. (expect (.-textContent target)) (toBe "portaled"))
      (reset! on true)
      (s/flush)
      (.. (expect (.-textContent c)) (toBe "on")))))
