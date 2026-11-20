(ns hydration.server
  "Renders solid.hydration-app on the server and writes the HTML that the browser test
  hydrates."
  (:require ["node:fs" :as fs]
            ["@solidjs/web/server-functions/client" :as client]
            [solid.core :as s :refer [$]]
            [solid.hydration-app :refer [app]]
            [solid.server :as server]
            [solid.server-component-app :as sca]
            [solid.server-components :as sc :refer [defservercomponent]]
            [solid.server-functions.server :as sfs]))

(sc/configure-server!)

(defservercomponent todo-list [filter]
  (js/Promise.resolve
    (let [todos (cond-> [{:id "a" :title "first"} {:id "b" :title "second"}]
                  (= "reversed" filter) (-> rseq vec (assoc-in [1 :title] "first, renamed")))]
      (s/ui [{:keys [editor pending]}]
        ($ :ul
           (s/for [[t] todos]
             ($ :li {:$key (:id t)} ($ :span (:title t)) ($ editor {:$key (:id t) :id (:id t)})))
           ($ pending))))))

(defn- frame-response
  "Returns a promise of the body and the headers of the frame stream that answers a browser's
  call of `todo-list` with `filter`, sent through Solid's browser transport to the handler."
  [filter]
  (js/Promise.
    (fn [resolve reject]
      (set! (.-fetch js/globalThis)
            (fn [address ^js init]
              (let [headers (js/Headers. (.-headers init))]
                (.set headers "origin" "http://localhost")
                (-> (sfs/handle-request (js/Request. (str (js/URL. (str address) "http://localhost"))
                                                     (js/Object.assign #js {} init #js {:headers headers})))
                    (.then (fn [^js response]
                             (-> (.text (.clone response))
                                 (.then #(resolve #js {:body %
                                                       :headers (js/Object.fromEntries (.-headers response))})))
                             response))))))
      (-> ((sc/-edn-arguments (client/createServerReference "hydration/server/todo-list")) filter)
          (.catch reject)))))

(defn- server-component-fixture
  "Returns a promise of the frame streams for two filters and of a document that renders the
  server component, for hydration.server-components-test."
  []
  (-> (js/Promise.all
        #js [(frame-response "all")
             (frame-response "reversed")
             (server/provide-request-event
               (server/create-request-event (js/Request. "http://localhost/"))
               #(s/render-to-string-async ($ sca/page {:todo-list todo-list :filter (s/signal "all")})
                                          {:plugins [sc/plugin]}))])
      (.then (fn [[all reversed html]]
               #js {:frames #js {"all" all "reversed" reversed}
                    :html html
                    :script (s/hydration-script)}))))

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
  (-> (server-component-fixture)
      (.then #(fs/writeFileSync "out/server-components.json" (js/JSON.stringify %))))
  (-> (js/Promise.all #js [(s/render-to-string-async ($ app)) (render-stream ($ app))])
      (.then (fn [[html streamed]]
               (fs/writeFileSync "out/hydration.json"
                                 (js/JSON.stringify #js {:html html
                                                         :stream streamed
                                                         :script (s/hydration-script)}))))))
