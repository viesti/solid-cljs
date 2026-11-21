(ns solid.server-functions.ring-test
  "Server functions on the JVM: defserverfn in Clojure, answered by
  solid.server-functions.ring/handler. Run with `clojure -M:jvm-test`. The protocol test runs
  Solid's own client (test/jvm/protocol.mjs, under Node.js) against a Jetty server, so it needs
  `yarn install`."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty :as jetty]
            [solid.server-functions :refer [defserverfn]]
            [solid.server-functions.ring :as sfr])
  (:import (java.io ByteArrayInputStream)
           (java.util Date)
           (org.eclipse.jetty.http UriCompliance UriCompliance$Violation)
           (org.eclipse.jetty.server Connector HttpConnectionFactory Server)))

(defserverfn add-item [title]
  {:title title :at (Date.)})

(defserverfn echo-get {:method :get} [x]
  [x (:request-method sfr/*request*)])

(defserverfn fail-with-message [message]
  (throw (ex-info message {})))

(defserverfn fail-badly []
  (throw (RuntimeException. "secret details")))

(defserverfn sign-in [user-name]
  (sfr/respond user-name {:headers {"set-cookie" (str "name=" user-name)}}))

(defserverfn custom-encoded {:encoding {:encode #(str "X" (pr-str %))
                                        :decode #(edn/read-string (subs % 1))}}
  [n]
  (inc n))

(defonce ^:private counter (atom 0))

(defserverfn bump []
  (swap! counter inc))

(defserverfn counter-feed {:live true} []
  (fn [send]
    (send @counter)
    (add-watch counter ::feed (fn [_ _ _ v] (send v)))
    #(remove-watch counter ::feed)))

(def ^:private handler (sfr/handler {:on-error (fn [_])}))

(defn- call
  "Answers a POST of `body` to the server function `id` through the handler."
  ([id body] (call id body {}))
  ([id body headers]
   (handler {:request-method :post
             :uri (str "/_server/data/" (java.net.URLEncoder/encode ^String id "UTF-8"))
             :headers (merge {"x-server-function-format" "1" "host" "localhost:3000"} headers)
             :body (ByteArrayInputStream. (.getBytes ^String body "UTF-8"))})))

(deftest defserverfn-on-the-jvm
  (testing "it defines a function that server code calls directly"
    (is (= "a" (:title (add-item "a")))))
  (testing "it registers the function under the id the browser uses"
    (is (contains? (sfr/registered) "solid.server-functions.ring-test/add-item"))))

(deftest handler-responses
  (testing "a call answers with the result encoded as EDN"
    (let [{:keys [status headers body]} (call "solid.server-functions.ring-test/add-item" "[\"milk\"]")]
      (is (= 200 status))
      (is (= "1" (headers "x-server-function-format")))
      (is (re-find #"^\{:title \"milk\", :at #inst \"" body))))
  (testing "respond adds response keys"
    (let [{:keys [body headers]} (call "solid.server-functions.ring-test/sign-in" "[\"Ann\"]")]
      (is (= "\"Ann\"" body))
      (is (= "name=Ann" (headers "set-cookie")))))
  (testing "a custom encoding encodes both ways"
    (is (= "X2" (:body (call "solid.server-functions.ring-test/custom-encoded" "X[1]")))))
  (testing "other requests fall through"
    (is (nil? (handler {:request-method :get :uri "/other" :headers {}}))))
  (testing "refusals"
    (is (= 404 (:status (call "solid.server-functions.ring-test/missing" "[]"))))
    (is (= 404 (:status (call "solid.server-functions.ring-test/counter-feed" "[]"))) "live, at the data path")
    (is (= 405 (:status (call "solid.server-functions.ring-test/echo-get" "[1]"))) "GET only")
    (is (= 403 (:status (call "solid.server-functions.ring-test/add-item" "[\"x\"]"
                              {"origin" "https://elsewhere.example"}))))
    (is (= 415 (:status (call "solid.server-functions.ring-test/add-item" "[\"x\"]"
                              {"x-server-function-format" "8"})))))
  (testing "an ex-info sends its message and other exceptions a generic one"
    (let [{:keys [status headers body]} (call "solid.server-functions.ring-test/fail-with-message" "[\"nope\"]")]
      (is (= 400 status))
      (is (= "nope" (headers "x-server-function-error")))
      (is (str/includes? body "\"m\":\"nope\"")))
    (let [{:keys [status body]} (call "solid.server-functions.ring-test/fail-badly" "[]")]
      (is (= 500 status))
      (is (not (str/includes? body "secret"))))))

(defn- allow-encoded-slashes!
  "Lets Jetty accept %2F in a path, where Solid's client puts the slash of a function's id."
  [^Server server]
  (doseq [^Connector connector (.getConnectors server)
          factory (.getConnectionFactories connector)
          :when (instance? HttpConnectionFactory factory)]
    (.setUriCompliance (.getHttpConfiguration ^HttpConnectionFactory factory)
                       (.with UriCompliance/DEFAULT "server function ids"
                              (into-array UriCompliance$Violation
                                          [UriCompliance$Violation/AMBIGUOUS_PATH_SEPARATOR])))))

(deftest solid-client-against-the-handler
  (let [port 18091
        server (jetty/run-jetty (fn [request]
                                  (or (handler request) {:status 404 :body "Not found"}))
                                {:port port :join? false :configurator allow-encoded-slashes!})]
    (try
      (let [{:keys [exit out err]} (shell/sh "node" "test/jvm/protocol.mjs" (str "http://localhost:" port))]
        (print out err)
        (is (zero? exit)))
      (finally
        (.stop ^Server server)))))
