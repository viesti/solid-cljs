(ns solid.server-functions-test
  "Calls server functions through Solid's browser transport, sent straight to the server
  handler in the same Node.js process."
  (:require [cljs.reader :as reader]
            [cljs.test :refer [deftest is async]]
            [clojure.walk]
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

(defserverfn how-called []
  (sfs/invocation))

(defserverfn fail-safely [reason]
  (throw (s/safe-error (js/Error. (str "refused: " reason)))))

(defn- browser-reference
  "Returns what a browser build defines for the server function `id`, using the browser
  module of Solid's runtime."
  [id]
  (sf/-caller (client/createServerReference id)))

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
               (.then #(step (fn [] ((browser-reference "solid.server-functions-test/how-called")))
                             (fn [r]
                               (is (= {:id "solid.server-functions-test/how-called" :live false :broadcast false} r)
                                   "the invocation of the running server function")
                               (is (nil? (sfs/invocation)) "and nil outside one"))))
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

(def ^:private price (atom {:value 1 :listeners #{}}))

(defn- set-price! [v]
  (swap! price assoc :value v)
  (doseq [f (:listeners @price)] (f {:price v})))

(defserverfn price-feed {:live true} [sym]
  (s/from (fn [next _ _]
            (swap! price update :listeners conj next)
            (next {:price (:value @price) :symbol sym})
            #(swap! price update :listeners disj next))))

(defn- wait [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest live-values
  (async done
         (let [feed (sf/-live-caller (client/live (client/createServerReference "solid.server-functions-test/price-feed")))
               ^js values (feed :abc)
               ^js it (.call (aget values js/Symbol.asyncIterator) values)]
           (is (true? (aget values (js/Symbol.for "solid.LiveSource"))) "the answer is a live source")
           (-> (.next it)
               (.then (fn [^js r]
                        (is (= {:price 1 :symbol :abc} (.-value r)) "the first value arrives as EDN")
                        (set-price! 2)
                        (.next it)))
               (.then (fn [^js r]
                        (is (= {:price 2} (.-value r)) "a later value follows the source")
                        (.return it)
                        (wait 20)))
               (.then (fn [] (is (empty? (:listeners @price)) "closing the iterator unsubscribes on the server")))
               (.catch (fn [e] (is false (str "rejected: " e))))
               (.finally done)))))

(defserverfn shared-price-feed {:broadcast true} []
  (s/from (fn [next _ _]
            (swap! price update :listeners conj next)
            (next {:price (:value @price)})
            #(swap! price update :listeners disj next))))

(deftest broadcast-values
  (async done
         (let [feed (sf/-live-caller (client/broadcast (client/createServerReference "solid.server-functions-test/shared-price-feed")))
               open #(let [^js values (feed)] (.call (aget values js/Symbol.asyncIterator) values))
               its [(open) (open)]]
           (-> (js/Promise.all (into-array (map #(.next ^js %) its)))
               (.then (fn [rs]
                        (is (= [{:price 2} {:price 2}] (map #(.-value ^js %) rs)) "both callers get the value")
                        (is (= 1 (count (:listeners @price))) "and share one source")
                        (doseq [^js it its] (.return it))
                        (wait 20)))
               (.then (fn [] (is (empty? (:listeners @price)) "the source closes with its last caller")))
               (.catch (fn [e] (is false (str "rejected: " e))))
               (.finally done)))))

(defserverfn add-note [list-id form]
  (str "saved " (.get form "text") " to " list-id))

(deftest form-posts
  (async done
         (sfs/configure-server {:secret "a test secret"})
         (let [action (sf/action-url add-note "inbox")
               form (doto (js/FormData.) (.append "text" "hi"))]
           (is (re-find #"^/_server/solid\.server-functions-test%2Fadd-note\?args=" action) "the form's url")
           (is (re-find #"^/_server/data/solid\.server-functions-test%2Fread-count\?args="
                        (sf/url (sf/-caller (client/GET (client/createServerReference "solid.server-functions-test/read-count")))))
               "a GET call's url, with EDN arguments")
           (-> (sfs/handle-request
                (js/Request. (str "http://localhost" action)
                             #js {:method "POST" :body form
                                  :headers #js {"origin" "http://localhost" "referer" "http://localhost/notes"
                                                "sec-fetch-site" "same-origin" "sec-fetch-mode" "navigate"
                                                "accept" "text/html"}}))
               (.then (fn [^js response]
                        (is (= 303 (.-status response)) "a form post redirects back")
                        (is (= "http://localhost/notes" (.get (.-headers response) "location")))
                        (let [cookie (first (.split (or (.get (.-headers response) "set-cookie") "") ";"))]
                          (is (.startsWith cookie "flash=") "with the outcome in a flash cookie")
                          (sfs/flash (js/Request. "http://localhost/notes" #js {:headers #js {"cookie" cookie}})))))
               (.then (fn [flash]
                        (is (= "saved hi to inbox" (:result flash)) "flash reads the outcome")))
               (.catch (fn [e] (is false (str "rejected: " e))))
               (.finally done)))))

(defserverfn next-day [date]
  {:date date :next (js/Date. (+ (.getTime date) 86400000))})

(deftest dates-in-edn
  (async done
         (-> (step #((browser-reference "solid.server-functions-test/next-day") (js/Date. 0))
                   (fn [{:keys [date next]}]
                     (is (instance? js/Date date) "a js/Date argument arrives as a js/Date")
                     (is (= 86400000 (.getTime next)) "and a js/Date result as one")))
             (.then done done))))

(defrecord Money [cents])

(def money-encoding
  "Writes `Money` as `#test/money`, and anything else as EDN."
  {:encode (fn [v]
             (pr-str (clojure.walk/postwalk #(if (instance? Money %) (tagged-literal 'test/money (:cents %)) %) v)))
   :decode (fn [s] (reader/read-string {:readers {'test/money ->Money}} s))})

(defserverfn add-money {:encoding money-encoding} [a b]
  (s/respond {:sum (->Money (+ (:cents a) (:cents b))) :at (js/Date. 0)} {:status 201}))

(defserverfn money-feed {:encoding money-encoding :live true} [cents]
  (s/from (fn [next _ _] (next (->Money cents)) (fn []))))

(defserverfn read-money {:method :get} [cents]
  (->Money cents))

(deftest custom-encodings
  (async done
         (let [add (sf/-caller (client/createServerReference "solid.server-functions-test/add-money") money-encoding)
               feed (sf/-live-caller (client/live (client/createServerReference "solid.server-functions-test/money-feed"))
                                     money-encoding)
               read-ref (fn [] (sf/-caller (client/GET (client/createServerReference "solid.server-functions-test/read-money"))))]
           (-> (step #(add (->Money 100) (->Money 50))
                     (fn [{:keys [sum at]}]
                       (is (= (->Money 150) sum) "a custom type round-trips through its encoding")
                       (is (= 201 (second (last @requests))) "inside a response that keeps its status")
                       (is (instance? js/Date at) "and dates arrive as js/Date")))
               (.then (fn []
                        (let [^js values (feed 7)
                              ^js it (.call (aget values js/Symbol.asyncIterator) values)]
                          (-> (.next it)
                              (.then (fn [^js r]
                                       (is (= (->Money 7) (.-value r)) "live values use the encoding")
                                       (.return it)))))))
               (.then (fn []
                        (sf/set-default-encoding! money-encoding)
                        (is (re-find #"test%2Fmoney" (sf/url (read-ref) (->Money 3))) "url encodes with the default")
                        (step #((read-ref) 3)
                              (fn [r] (is (= (->Money 3) r) "the default encoding applies to functions without one")))))
               (.catch (fn [e] (is false (str "rejected: " e))))
               (.finally (fn []
                           (sf/set-default-encoding! sf/edn)
                           (done)))))))
