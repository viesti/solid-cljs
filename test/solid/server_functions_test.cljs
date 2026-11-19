(ns solid.server-functions-test
  "Calls server functions through Solid's browser transport, sent straight to the server
  handler in the same Node.js process."
  (:require [cljs.test :refer [deftest is async]]
            [solid.core :as s]
            [solid.server-functions :as sf :refer [defserverfn]]
            [solid.server-functions.server :as sfs]
            ["@solidjs/web/server-functions/client" :as client]))

(defserverfn award-points [user n]
  {:user user :points (+ 10 n) :tags #{:new}})

(defserverfn read-count {:method :get} []
  (js/Promise.resolve 42))

(defserverfn request-path []
  (.-pathname (js/URL. (.-url (.-request (s/request-event))))))

(defserverfn fail-safely [reason]
  (throw (s/safe-error (js/Error. (str "refused: " reason)))))

(defn- browser-reference
  "Returns what a browser build defines for the server function `id`, using the browser
  module of Solid's runtime."
  [id]
  (sf/-edn-caller (client/createServerReference id)))

;; Node.js has no page location, so URLs resolve against localhost. The client runtime sends
;; with the global fetch, which goes to the handler here, with the Origin a browser would send.
(def requests (atom []))

(defn- send [address init]
  (let [url (str (js/URL. (str address) "http://localhost"))
        headers (js/Headers. (.-headers init))]
    (.set headers "origin" "http://localhost")
    (-> (sfs/handle-request (js/Request. url (js/Object.assign #js {} init #js {:headers headers})))
        (.then (fn [response]
                 (swap! requests conj [url (.-status response)])
                 response)))))

(set! (.-fetch js/globalThis) send)

(defn- step
  "Runs the promise of `f`, reporting its result to `check`, and a rejection as a failure."
  [f check]
  (-> (f)
      (.then check (fn [e] (is false (str "rejected: " (.-message e) " " (pr-str @requests)))))))

(deftest server-function-calls
  (async done
    (let [award (browser-reference "solid.server-functions-test/award-points")
          count-ref (browser-reference "solid.server-functions-test/read-count")
          fail (browser-reference "solid.server-functions-test/fail-safely")]
      (is (= {:user :ann :points 11 :tags #{:new}} (award-points :ann 1)) "server code calls it directly")
      (-> (step #(award :ann 5)
                (fn [r] (is (= {:user :ann :points 15 :tags #{:new}} r) "ClojureScript data round-trips")))
          (.then #(step (fn [] (count-ref))
                        (fn [r] (is (= 42 r) "a :get function answers over the transport"))))
          (.then #(step (fn [] ((browser-reference "solid.server-functions-test/request-path")))
                        (fn [r] (is (re-find #"^/_server" r) "the request event is in scope"))))
          (.then #(-> (fail :quota)
                      (.then (fn [_] (is false "should have rejected"))
                             (fn [e] (is (= "refused: :quota" (.-message e)) "safe errors keep their message")))))
          (.then done done)))))
