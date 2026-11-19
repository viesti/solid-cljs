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
