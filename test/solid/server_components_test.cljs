(ns solid.server-components-test
  "Renders server components into frame streams and into a document, on the server."
  (:require [cljs.test :refer [deftest is async]]
            [clojure.string :as str]
            [solid.core :as s :refer [$]]
            [solid.server :as server]
            [solid.server-components :as sc :refer [defservercomponent]]
            [solid.server-functions.server :as sfs]
            ["@solidjs/web/server-functions/client" :as client]))

(sc/configure-server!)

(defservercomponent todo-list [filter]
  (js/Promise.resolve
    (let [todos [{:id "a" :title (str "first " filter)} {:id "b" :title "second"}]]
      (s/ui [{:keys [editor pending]}]
        ($ :ul
           (s/for [[t] todos]
             ($ :li {:$key (:id t)} (:title t) ($ editor {:$key (:id t) :id (:id t)})))
           ($ pending))))))

(defn- call
  "Returns a promise of the response to a browser's call of the server component `id` with
  `args`, sent through the browser module of Solid's transport straight to the handler."
  [id args]
  (js/Promise.
    (fn [resolve reject]
      (let [fetch (.-fetch js/globalThis)]
        (set! (.-fetch js/globalThis)
              (fn [address ^js init]
                (let [headers (js/Headers. (.-headers init))]
                  (.set headers "origin" "http://localhost")
                  (-> (sfs/handle-request (js/Request. (str (js/URL. (str address) "http://localhost"))
                                                       (js/Object.assign #js {} init #js {:headers headers})))
                      (.then (fn [^js response] (resolve (.clone response)) response))))))
        (-> (apply (sc/-edn-arguments (client/createServerReference id)) args)
            (.catch reject)
            (.finally #(set! (.-fetch js/globalThis) fetch)))))))

(deftest frame-stream
  (async done
    (-> (call "solid/server-components-test/todo-list" ["x"])
        (.then (fn [^js response]
                 (is (= "application/x-frame-stream" (.get (.-headers response) "content-type")))
                 (.text response)))
        (.then (fn [body]
                 (is (str/includes? body "<li _key=\\\"a\\\">first x<!--slot:editor#a:start-->")
                     "keyed elements and slot markers")
                 (is (str/includes? body "\"slot:editor#b\":{\"kind\":\"slot\",\"args\":{\"id\":\"b\"}}")
                     "slot arguments travel as records")
                 (is (str/includes? body "slot:pending#0:start") "a slot without props")))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(s/defui editor [{:keys [id]}]
  ($ :button "edit " id))

(s/defui page []
  (let [todos (sc/server-component (todo-list "y"))]
    ($ :main ($ todos {:editor editor :pending (s/ui [] ($ :i "adding"))}))))

(deftest document-render
  (async done
    (-> (server/provide-request-event
          (server/create-request-event (js/Request. "http://localhost/"))
          #(s/render-to-string-async ($ page) {:plugins [sc/plugin]}))
        (.then (fn [html]
                 (is (str/includes? html "<li _key=\"a\">first y<!--slot:editor#a:start--><button>edit a</button>")
                     "the document renders the fills in place")
                 (is (str/includes? html "<i>adding</i>"))
                 (is (str/includes? html ".r(\"solid/server-components-test/todo-list\",\"solid/server-components-test/todo-list:")
                     "the browser adopts the server component by reference")))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))
