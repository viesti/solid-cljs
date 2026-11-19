(ns solid.ssr-test-runner
  "Runs the server rendering tests with Node.js resolving the server builds of Solid."
  (:require [cljs.test :as t]
            [solid.ssr-test]
            [solid.server-functions-test]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (js/process.exit (if (t/successful? m) 0 1)))

(defn main [& _]
  (t/run-tests 'solid.ssr-test 'solid.server-functions-test))
