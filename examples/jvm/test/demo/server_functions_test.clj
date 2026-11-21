(ns demo.server-functions-test
  "Runs Solid's own server function client (test/protocol.mjs, under Node.js) against the JVM's
  answers, so that a Solid release that changes the wire fails here. Needs `yarn install`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [demo.server :as server]))

(deftest solid-client-against-the-jvm
  (let [db (doto (io/file (System/getProperty "java.io.tmpdir") (str "todos-test-" (System/nanoTime) ".db"))
             .deleteOnExit)
        port 18080]
    (System/setProperty "todos.db" (str db))
    (server/start! port)
    (try
      (let [{:keys [exit out err]} (shell/sh "node" "test/protocol.mjs" (str "http://localhost:" port))]
        (print out err)
        (is (zero? exit)))
      (finally
        (server/stop!)))))
