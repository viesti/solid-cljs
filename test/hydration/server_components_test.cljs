(ns hydration.server-components-test
  "Adopts a server component that hydration.server rendered into a document, and renders
  others from the frame streams it recorded."
  (:require [solid.frame-fixtures :as frames]
            [solid.core :as s :refer [$]]
            [solid.server-component-app :as app]
            [solid.server-components :as sc]
            ["vitest" :refer [expect test]]))

(def todo-list (sc/-reference "hydration/server/todo-list" {}))

(def headline (sc/-reference "hydration/server/headline" {:live true}))

(test
 "The browser adopts a server component from the document without a request"
 (fn []
   (-> (frames/fixture)
       (.then (fn [^js f]
                (frames/serve-frames! (.-frames f))
                (reset! frames/requests [])
                (let [container (js/document.createElement "div")]
                  (set! (.-innerHTML container) (.-html f))
                  (.appendChild js/document.body container)
                  (frames/run-scripts! (.-script f) container)
                   ;; As a page's entry script does, after the body it adopts; the second test reuses it
                  (sc/install!)
                  (let [server-button (frames/button container "a")
                        hydrated (js/Promise. (fn [resolve] (s/on-hydration-end resolve)))]
                    (s/hydrate ($ app/page {:todo-list todo-list :filter (s/signal "all")}) container)
                    (-> hydrated
                        (.then (fn []
                                 (.. (expect (identical? server-button (frames/button container "a"))) (toBe true))
                                 (.click server-button)
                                 (s/flush)
                                 (.. (expect (frames/texts container "button.edit")) (toEqual #js ["a:1" "b:0"]))
                                 (.. (expect (frames/texts container "i")) (toEqual #js ["adding"]))
                                 (.. (expect (clj->js @frames/requests)) (toEqual #js []))))))))))))

(test
 "A streamed server component keeps keyed fills across a refetch"
 (fn []
   (-> (frames/fixture)
       (.then (fn [^js f]
                (frames/serve-frames! (.-frames f))
                (reset! frames/requests [])
                (let [container (js/document.createElement "div")
                      filter (s/signal "all")]
                  (.appendChild js/document.body container)
                  (s/render ($ app/page {:todo-list todo-list :filter filter}) container)
                  (-> (frames/until #(frames/button container "a"))
                      (.then (fn []
                               (.. (expect (frames/texts container "li span")) (toEqual #js ["first" "second"]))
                               (.click (frames/button container "a"))
                               (.click (frames/button container "a"))
                               (s/flush)
                               (reset! filter "reversed")
                               (frames/until #(= ["second" "first, renamed"] (vec (frames/texts container "li span"))))))
                      (.then (fn []
                               (.. (expect (frames/texts container "button.edit")) (toEqual #js ["b:0" "a:2"]))
                               (.. (expect (clj->js @frames/requests)) (toEqual #js ["all" "reversed"])))))))))))

(test
 "A live server component updates in place and its fills keep their state"
 (fn []
   (-> (frames/fixture)
       (.then (fn [^js f]
                (reset! frames/requests [])
                (let [send-update (frames/serve-live! (.-live f))
                      container (js/document.createElement "div")
                      warn (.-warn js/console)
                      warnings (atom [])]
                  (set! (.-warn js/console) (fn [& args] (swap! warnings conj (str (first args)))))
                  (.appendChild js/document.body container)
                  (s/render ($ app/live-page {:headline headline}) container)
                  (-> (frames/until #(frames/button container "h"))
                      (.then (fn []
                               (.. (expect (frames/texts container "h1")) (toEqual #js ["first"]))
                               (.. (expect (frames/texts container "section li")) (toEqual #js ["first" "first!"]))
                               (.click (frames/button container "h"))
                               (s/flush)
                               (send-update)
                               (frames/until #(and (= ["second"] (vec (frames/texts container "h1")))
                                                   (= ["second" "second!"] (vec (frames/texts container "section li")))))))
                      (.then (fn []
                               (.. (expect (frames/texts container "button.edit")) (toEqual #js ["h:1"]))
                               (.. (expect (count @frames/requests)) (toBe 1))
                               (.. (expect (first @frames/requests)) (toContain "/live/"))
                               (set! (.-warn js/console) warn)
                               (.. (expect (clj->js (filter #(.includes % "STRICT_READ_UNTRACKED") @warnings)))
                                   (toEqual #js [])))))))))))
