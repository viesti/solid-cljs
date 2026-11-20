(ns hydration.live-server-components-test
  "Adopts a live server component that hydration.server rendered into a document. It has a file
  of its own because Solid indexes a page's server-rendered frames once, so each document needs
  a fresh page."
  (:require [solid.frame-fixtures :as frames]
            [solid.core :as s :refer [$]]
            [solid.server-component-app :as app]
            [solid.server-components :as sc]
            ["vitest" :refer [expect test]]))

(def headline (sc/-reference "hydration/server/headline" {:live true}))

(test
 "A live server component in the document goes live after hydration"
 (fn []
   (-> (frames/fixture)
       (.then (fn [^js f]
                (reset! frames/requests [])
                (let [send-update (frames/serve-live! (.-live f))
                      container (js/document.createElement "div")]
                  (set! (.-innerHTML container) (.-liveHtml f))
                  (.appendChild js/document.body container)
                  (frames/run-scripts! (.-script f) container)
                  (sc/install!)
                  (let [server-button (frames/button container "h")
                        hydrated (js/Promise. (fn [resolve] (s/on-hydration-end resolve)))]
                    (s/hydrate ($ app/live-page {:headline headline}) container)
                    (-> hydrated
                        (.then (fn []
                                 (.. (expect (identical? server-button (frames/button container "h"))) (toBe true))
                                 (.. (expect (frames/texts container "h1")) (toEqual #js ["first"]))
                                 (.click server-button)
                                 (s/flush)
                                 (frames/until #(seq @frames/requests))))
                        (.then (fn []
                                 (send-update)
                                 (frames/until #(= ["second"] (vec (frames/texts container "h1"))))))
                        (.then (fn []
                                 (.. (expect (frames/texts container "button.edit")) (toEqual #js ["h:1"]))
                                 (.. (expect (first @frames/requests)) (toContain "/live/"))))))))))))
