(ns vitest.api-extras
  "Browser tests for client error configuration, error halt reset, lazy modules,
  hydration callbacks, external reactive sources, and live server functions in a memo."
  (:require-macros [solid.core])
  (:require
   [clojure.string :as str]
   [solid.core :as s :refer [$ defui]]
   [solid.server-functions :as sf]
   ["@solidjs/testing-library" :refer [render]]
   ["vitest" :refer [expect test]]))

(defn- mount [el]
  (.-container (render el)))

(defn- wait [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- until [pred]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       ((fn check []
          (cond
            (pred) (resolve true)
            (< 2000 (- (js/Date.now) start)) (reject (js/Error. "timed out"))
            :else (js/setTimeout check 10))))))))

(test
 "lazy-module caches the loaded module, shares preload, and exposes peek and moduleUrl"
 (fn []
   (let [calls (atom 0)
         loader (s/lazy-module (fn []
                                 (swap! calls inc)
                                 (js/Promise.resolve #js {:answer 42}))
                               "/modules/answer.js")]
     (.. (expect (.-moduleUrl loader)) (toBe "/modules/answer.js"))
     (.. (expect (.peek loader)) (toBeUndefined))
     (let [p1 (loader)
           p2 (.preload loader)]
       (.. (expect (identical? p1 p2)) (toBe true))
       (.. (expect @calls) (toBe 1))
       (-> p1
           (.then (fn [^js mod]
                    (.. (expect (.-answer mod)) (toBe 42))
                    (.. (expect (.-answer (.peek loader))) (toBe 42)))))))))

(test
 "on-hydration-end runs its callback when not hydrating"
 (fn []
   (let [called (atom false)]
     (-> (js/Promise. (fn [resolve]
                        (s/on-hydration-end #(do (reset! called true) (resolve nil)))))
         (.then (fn []
                  (.. (expect @called) (toBe true))))))))

(test
 "configure-client-errors reports errors caught by s/try and clears on nil"
 (fn []
   (let [reported (atom [])]
     (s/configure-client-errors {:on-error (fn [err _] (swap! reported conj (.-message err)))})
     (let [c (mount (s/try ($ (fn [_] (throw (js/Error. "boundary boom"))))
                           (catch e ($ :span "caught:" (.-message e)))))]
       (.. (expect (.-textContent c)) (toBe "caught:boundary boom"))
       (.. (expect (clj->js @reported)) (toEqual #js ["boundary boom"])))
     (s/configure-client-errors {:on-error nil})
     (mount (s/try ($ (fn [_] (throw (js/Error. "second boom"))))
                   (catch _ ($ :span "ok"))))
     (.. (expect (clj->js @reported)) (toEqual #js ["boundary boom"])))))

(test
 "reset-error-halt! resumes reactivity after an uncaught effect error halts it"
 (fn []
   (let [trigger (s/signal false)
         n (s/signal 0)
         seen (atom [])
         orig-report (.-reportError js/globalThis)
         reported (atom [])
         dispose (s/root
                  (fn [d]
                    (s/effect #(do @trigger)
                              (fn [v] (when v (throw (js/Error. "uncaught halt")))))
                    (s/effect #(do @n)
                              (fn [v] (swap! seen conj v)))
                    d))]
     (s/flush)
     (.. (expect (clj->js @seen)) (toEqual #js [0]))
     (set! (.-reportError js/globalThis) (fn [e] (swap! reported conj (.-message e))))
     (try
       (reset! trigger true)
       (try (s/flush) (catch :default e (swap! reported conj (.-message e))))
       ;; While halted, updates to unrelated signals are ignored
       (reset! n 1)
       (s/flush)
       (.. (expect (clj->js @seen)) (toEqual #js [0]))
       (.. (expect (pos? (count @reported))) (toBe true))
       ;; Resetting the halt lets reactivity resume
       (s/reset-error-halt!)
       (reset! trigger false)
       (reset! n 2)
       (s/flush)
       (.. (expect (last @seen)) (toBe 2))
       (finally
         (s/reset-error-halt!)
         (set! (.-reportError js/globalThis) orig-report)
         (dispose))))))

(def ^:private live-preamble
  (str "data: {\"t\":10,\"i\":0,\"p\":{\"k\":[{\"t\":17,\"i\":1,\"s\":0}],\"v\":[{\"t\":30,\"a\":[{\"t\":29,\"i\":2,\"a\":[{\"t\":26,\"i\":3,\"s\":1},{\"t\":4,\"i\":1}]},{\"t\":31,\"i\":4,\"a\":[],\"f\":{\"t\":26,\"i\":5,\"s\":4}}]}]},\"o\":0}\n\n"))

(defn- seroval-escape [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "<" "\\x3C")))

(defn- sse-value [v]
  (str "data: {\"t\":32,\"i\":4,\"f\":{\"t\":1,\"s\":" (js/JSON.stringify (seroval-escape (pr-str v))) "}}\n\n"))

(test
 "A memo over a live server function updates in the browser and reconnects after a drop"
 (fn []
   (let [orig-fetch (.-fetch js/globalThis)
         enc (js/TextEncoder.)
         attempt (atom 0)
         controllers (atom [])
         statuses (atom [])
         live-ref (sf/-reference "vitest.api-extras/live-counter" {:live true})]
     (set! (.-fetch js/globalThis)
           (fn [_ _]
             (let [n (swap! attempt inc)
                   body (js/ReadableStream.
                         #js {:start (fn [ctrl]
                                       (swap! controllers conj ctrl)
                                       (.enqueue ctrl (.encode enc live-preamble))
                                       (.enqueue ctrl (.encode enc (sse-value {:count (if (= 1 n) 1 3)}))))})]
               (js/Promise.resolve
                (js/Response. body #js {:status 200
                                        :headers #js {"content-type" "text/event-stream"
                                                      "x-server-function-format" "0"}})))))
     (let [unmount (atom nil)
           view (fn [_]
                  (let [data (s/memo (fn []
                                       (let [^js src (live-ref :a)]
                                         (set! (.-onstatus src) (fn [state _] (swap! statuses conj state)))
                                         src)))]
                    (s/loading ($ :p "loading")
                               ($ :p "count:" (:count (data))))))
           ^js rendered (render ($ view))
           c (.-container rendered)]
       (reset! unmount (.-unmount rendered))
       (-> (until #(= "count:1" (.-textContent c)))
           (.then (fn []
                    ;; Push a second value over the first stream
                    (.enqueue (first @controllers) (.encode enc (sse-value {:count 2})))
                    (until #(= "count:2" (.-textContent c)))))
           (.then (fn []
                    ;; Drop the first stream and wake reconnect immediately with the online event
                    (.error (first @controllers) (js/Error. "dropped"))
                    (until #(= "reconnecting" (last @statuses)))))
           (.then (fn []
                    (.dispatchEvent js/window (js/Event. "online"))
                    (until #(= "count:3" (.-textContent c)))))
           (.then (fn []
                    (.. (expect @attempt) (toBe 2))
                    (@unmount)
                    (until #(= "closed" (last @statuses)))))
           (.then (fn []
                    (.. (expect (clj->js @statuses))
                        (toEqual #js ["connected" "reconnecting" "connected" "closed"]))))
           (.finally (fn []
                       (set! (.-fetch js/globalThis) orig-fetch))))))))

(test
 "enable-external-source integrates an external reactive tracker and untrack hook"
 (fn []
   (let [active? (atom false)
         current-trigger (atom nil)
         untrack-calls (atom 0)
         ext-val (atom 1)
         ext-listeners (atom #{})
         read-ext (fn []
                    (when-let [t @current-trigger]
                      (swap! ext-listeners conj t))
                    @ext-val)
         set-ext! (fn [v]
                    (reset! ext-val v)
                    (doseq [t @ext-listeners] (t)))]
     (s/enable-external-source
      {:factory (fn [f trigger]
                  #js {:track (fn [x]
                                (if @active?
                                  (let [prev @current-trigger]
                                    (reset! current-trigger trigger)
                                    (try (f x) (finally (reset! current-trigger prev))))
                                  (f x)))
                       :dispose (fn [] (swap! ext-listeners disj trigger))})
       :untrack (fn [f]
                  (when @active? (swap! untrack-calls inc))
                  (f))})
     (reset! active? true)
     (let [seen (atom [])
           dispose (s/root
                    (fn [d]
                      (s/effect #(read-ext) (fn [v] (swap! seen conj v)))
                      d))]
       (try
         (s/flush)
         (.. (expect (clj->js @seen)) (toEqual #js [1]))
         (set-ext! 2)
         (s/flush)
         (.. (expect (clj->js @seen)) (toEqual #js [1 2]))
         (let [before @untrack-calls]
           (s/untrack #(read-ext))
           (.. (expect (> @untrack-calls before)) (toBe true)))
         (finally
           (dispose)
           (reset! active? false)))))))
