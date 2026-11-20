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

(defserverfn remember-name [n]
  (s/respond {:name n} {:headers {"set-cookie" (s/serialize-cookie "name" n {:path "/"})}}))

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

(defn- send-now [address init]
  (let [url (str (js/URL. (str address) "http://localhost"))
        headers (js/Headers. (.-headers init))]
    (.set headers "origin" "http://localhost")
    (-> (sfs/handle-request (js/Request. url (js/Object.assign #js {} init #js {:headers headers})))
        (.then (fn [response]
                 (swap! requests conj [url (.-status response) (.get (.-headers response) "set-cookie")])
                 response)))))

(defn- send [address ^js init]
  (if-let [^js signal (some-> init .-signal (as-> s (when (.-aborted s) s)))]
    ;; As fetch does with an aborted signal
    (js/Promise.reject (.-reason signal))
    (send-now address init)))

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
               (.then #(step (fn [] ((browser-reference "solid.server-functions-test/remember-name") "Ann"))
                             (fn [r]
                               (is (= {:name "Ann"} r) "a response's value travels as EDN")
                               (is (= ["solid.server-functions-test/remember-name" 200 "name=Ann; Path=/"]
                                      (let [[url status cookie] (last @requests)]
                                        [(js/decodeURIComponent (subs url (inc (.lastIndexOf url "/")))) status cookie]))
                                   "and keeps its headers"))))
               (.then #(step (fn [] ((browser-reference "solid.server-functions-test/request-path")))
                             (fn [r] (is (re-find #"^/_server" r) "the request event is in scope"))))
               (.then #(-> (fail :quota)
                           (.then (fn [_] (is false "should have rejected"))
                                  (fn [e] (is (= "refused: :quota" (.-message e)) "safe errors keep their message")))))
               (.then done done)))))

(deftest per-call-options
  (async done
         (let [award (browser-reference "solid.server-functions-test/award-points")
               aborted (js/AbortSignal.abort)
               metas (atom [])]
           (is (= {:user :ann :points 11 :tags #{:new}} (sf/invoke award-points {} :ann 1))
               "invoke calls a server function in process on the server")
           (-> (sf/invoke award-points {:signal aborted} :ann 1)
               (.then (fn [_] (is false "an aborted in-process call should reject"))
                      (fn [e] (is (= "AbortError" (.-name e)) "an aborted signal rejects in process")))
               (.then #(step (fn [] (client/invoke award #js {:signal (.-signal (js/AbortController.))} :ann 2))
                             (fn [r] (is (= {:user :ann :points 12 :tags #{:new}} r) "invoke passes EDN through"))))
               (.then (fn []
                        (reset! requests [])
                        (-> (client/invoke award #js {:signal aborted} :ann 3)
                            (.then (fn [_] (is false "an aborted call should reject"))
                                   (fn [e]
                                     (is (= "AbortError" (.-name e)) "an aborted signal rejects the call")
                                     (is (empty? @requests) "and sends no request"))))))
               (.then (fn []
                        (client/configureServerFunctionsClient
                         #js {:prepareRequest (fn [init ^js context] (swap! metas conj (.-meta context)) init)})
                        (client/withMeta award #js {:requiresAuth true})
                        (step #(award :ann 4)
                              (fn [_]
                                (is (true? (some-> ^js (last @metas) .-requiresAuth))
                                    "metadata written on the EDN function reaches prepareRequest")))))
               (.finally (fn []
                           (client/configureServerFunctionsClient #js {:prepareRequest (fn [init _] init)})
                           (done)))))))
