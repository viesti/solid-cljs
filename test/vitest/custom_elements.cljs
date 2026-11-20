(ns vitest.custom-elements
  (:require
   [solid.core :as s :refer [$]]
   ["@solidjs/testing-library" :refer [render]]
   ["vitest" :refer [expect test]]))

(defonce defined
  (let [cls (js* "(class extends HTMLElement {
                     constructor() { super(); }
                     connectedCallback() { this.dataset.connected = 'yes'; }
                   })")]
    (set! (.. cls -prototype -ping) (fn [] (this-as ^js el (.dispatchEvent el (js/CustomEvent. "ping" #js {:detail 7})))))
    (js/customElements.define "solid-test-el" cls)
    cls))

(test
 "Custom elements are upgraded, get attributes, events and their owner"
 (fn []
   (let [label (s/signal "a")
         pings (atom [])
         c (.-container (render ($ :div
                                   ($ :solid-test-el {:label @label :count 1 :on-ping #(swap! pings conj (.-detail %))}
                                      ($ :span "child")))))
         el (.querySelector c "solid-test-el")]
     (.. (expect (instance? defined el)) (toBe true))
     (.. (expect (.. el -dataset -connected)) (toBe "yes"))
     (.. (expect (.getAttribute el "label")) (toBe "a"))
     (.. (expect (.getAttribute el "count")) (toBe "1"))
     (.. (expect (some? (.-_$owner el))) (toBe true))
     (.ping el)
     (.. (expect (clj->js @pings)) (toEqual #js [7]))
     (reset! label "b")
     (s/flush)
     (.. (expect (.getAttribute el "label")) (toBe "b"))
     (.. (expect (.-textContent el)) (toBe "child")))))

(test
 "Keywords in the prop namespace set DOM properties"
 (fn []
   (let [items (s/signal #js [1 2])
         checked (s/signal true)
         c (.-container (render ($ :div
                                   ($ :solid-test-el {:prop/items @items :prop/start-index 3})
                                   ($ :input {:type :checkbox :prop/indeterminate @checked})
                                   ($ :solid-test-el.spread {:& {:prop/items #js [9] :title "t"}}))))
         el (.querySelector c "solid-test-el")
         input (.querySelector c "input")
         spread-el (.querySelector c ".spread")]
     (.. (expect (.-items el)) (toEqual #js [1 2]))
     (.. (expect (.-startIndex el)) (toBe 3))
     (.. (expect (.hasAttribute el "items")) (toBe false))
     (.. (expect (.-indeterminate input)) (toBe true))
     (reset! items #js [5])
     (reset! checked false)
     (s/flush)
     (.. (expect (.-items el)) (toEqual #js [5]))
     (.. (expect (.-indeterminate input)) (toBe false))
     (.. (expect (.-items spread-el)) (toEqual #js [9]))
     (.. (expect (.getAttribute spread-el "title")) (toBe "t")))))
