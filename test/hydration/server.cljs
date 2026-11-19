(ns hydration.server
  "Renders solid.hydration-app on the server and writes the HTML that the browser test
  hydrates."
  (:require ["node:fs" :as fs]
            [solid.core :as s :refer [$]]
            [solid.hydration-app :refer [app]]))

(defn main [& _]
  (fs/writeFileSync "out/hydration.json"
                    (js/JSON.stringify #js {:html (s/render-to-string ($ app))
                                            :script (s/hydration-script)})))
