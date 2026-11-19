(ns hydration.server
  "Renders solid.hydration-app on the server and writes the HTML that the browser test
  hydrates."
  (:require ["node:fs" :as fs]
            [solid.core :as s :refer [$]]
            [solid.hydration-app :refer [app]]))

(defn- render-stream
  "Returns a promise of the chunks that streaming `root` writes, joined, as a browser receives
  them over one response."
  [root]
  (js/Promise.
    (fn [resolve]
      (let [chunks (atom [])]
        (s/pipe (s/render-to-stream root)
                #js {:write (fn [chunk] (swap! chunks conj (str chunk)) true)
                     :end #(resolve (apply str @chunks))})))))

(defn main [& _]
  (-> (js/Promise.all #js [(s/render-to-string-async ($ app)) (render-stream ($ app))])
      (.then (fn [[html streamed]]
               (fs/writeFileSync "out/hydration.json"
                                 (js/JSON.stringify #js {:html html
                                                         :stream streamed
                                                         :script (s/hydration-script)}))))))
