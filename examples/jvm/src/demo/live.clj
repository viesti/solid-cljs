(ns demo.live
  "The live state of the shared list, every todo and the number of viewers, and a response body
  that streams it as server-sent events."
  (:require [demo.db :as db]
            [ring.core.protocols :refer [StreamableResponseBody]])
  (:import (java.io IOException OutputStream)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

;; The callbacks of the open streams, one per viewer
(defonce ^:private viewers (atom #{}))

(defn broadcast!
  "Sends every todo and the viewer count to every viewer."
  []
  (let [state {:todos (db/todos) :viewers (count @viewers)}]
    (doseq [f @viewers]
      (f state))))

(defn subscribe
  "Counts a viewer and calls `f` with `{:todos [...] :viewers n}` now and after every change,
  until the returned function is called."
  [f]
  (swap! viewers conj f)
  (broadcast!)
  (fn []
    (swap! viewers disj f)
    (broadcast!)))

;; How long a stream may stay quiet before it gets a heartbeat. A write is how the server
;; notices that a browser went away, so this also bounds how long a closed window counts.
(def ^:private heartbeat-seconds 5)

(defn event-stream
  "Returns a response body that writes `preamble`, then `(event value)` for every value that
  `subscribe` sends, until the browser goes away. `subscribe` takes a callback and returns a
  function that stops it. Each stream blocks a thread, a virtual one (see demo.server/start!)."
  [subscribe event preamble]
  (reify StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (let [^OutputStream out out
            queue (LinkedBlockingQueue.)
            write! (fn [^String s]
                     (.write out (.getBytes s StandardCharsets/UTF_8))
                     (.flush out))
            unsubscribe (subscribe #(.offer queue %))]
        (try
          (some-> preamble write!)
          (loop []
            (let [value (.poll queue heartbeat-seconds TimeUnit/SECONDS)]
              (write! (if (nil? value) ":\n\n" (event value)))
              (recur)))
          (catch IOException _)
          (finally
            (unsubscribe)))))))
