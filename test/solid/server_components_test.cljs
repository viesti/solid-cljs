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

(def ^:private headline-state (atom {:title "first" :listeners #{}}))

(defn- publish! [title]
  (swap! headline-state assoc :title title)
  (doseq [f (:listeners @headline-state)] (f title)))

(defn- headline-source [next _ _]
  (swap! headline-state update :listeners conj next)
  (next (:title @headline-state))
  #(swap! headline-state update :listeners disj next))

(defservercomponent headline {:live true} []
  (s/ui []
        (let [title (s/memo #(s/from headline-source))]
          ($ :h1 (title)))))

(defservercomponent shared-headline {:broadcast true} []
  (s/ui []
        (let [title (s/memo #(s/from headline-source))]
          ($ :h1 (title)))))

(defn- live-response
  "Returns a promise of the response to a browser's live call of the server component `id`,
  which keeps streaming until the test cancels it."
  [id {:keys [broadcast]}]
  (js/Promise.
   (fn [resolve reject]
     (let [fetch (.-fetch js/globalThis)]
       (set! (.-fetch js/globalThis)
             (fn [address ^js init]
               (set! (.-fetch js/globalThis) fetch)
               (let [headers (js/Headers. (.-headers init))]
                 (.set headers "origin" "http://localhost")
                 (-> (sfs/handle-request (js/Request. (str (js/URL. (str address) "http://localhost"))
                                                      (js/Object.assign #js {} init #js {:headers headers})))
                     (.then (fn [^js response]
                              (resolve response)
                               ;; the client's own read would race the test's, so it gets nothing
                              (js/Response. "" #js {:status 204})))))))
       (let [iterable ((sc/-edn-arguments ((if broadcast client/broadcast client/live) (client/createServerReference id))))
             iterator (.call (aget iterable js/Symbol.asyncIterator) iterable)]
         (-> (.next iterator) (.catch (fn [_]))))))))

(defn- read-until
  "Reads `reader` until the text read so far contains `s`, and returns a promise of that text."
  [^js reader text s]
  (if (str/includes? text s)
    (js/Promise.resolve text)
    (-> (.read reader)
        (.then (fn [^js chunk]
                 (if (.-done chunk)
                   (js/Promise.reject (js/Error. (str "stream ended before " s ": " text)))
                   (read-until reader (str text (.decode (js/TextDecoder.) (.-value chunk))) s)))))))

(defn- wait [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(deftest live-frame-stream
  (async done
         (swap! headline-state assoc :title "first")
         (let [reader (atom nil)]
           (-> (live-response "solid/server-components-test/headline" {})
               (.then (fn [^js response]
                        (is (= "text/event-stream" (.get (.-headers response) "content-type")))
                        (reset! reader (.getReader (.-body response)))
                        (read-until @reader "" "first")))
               (.then (fn [text]
                        (is (str/includes? text "<h1><!--lh:0--><!--!$-->first<!--lh:/0--></h1>")
                            "the first frame marks the title as a live hole")
                        (publish! "second")
                        (read-until @reader "" "second")))
               (.then (fn [text]
                        (is (str/includes? text "\"hole:lh:0\":{\"kind\":\"html\"")
                            "a change on the server sends only the hole")
                        (.cancel @reader)
                        (wait 10)))
               (.then (fn []
                        (is (empty? (:listeners @headline-state))
                            "closing the stream unsubscribes the source")))
               (.catch (fn [e] (is false (str e))))
               (.finally done)))))

(deftest broadcast-frame-stream
  (async done
         (swap! headline-state assoc :title "first")
         (let [readers (atom [])
               open #(-> (live-response "solid/server-components-test/shared-headline" {:broadcast true})
                         (.then (fn [^js response]
                                  (let [reader (.getReader (.-body response))]
                                    (swap! readers conj reader)
                                    (read-until reader "" "first")))))]
           (-> (open)
               (.then (fn [_] (open)))
               (.then (fn [_]
                        (is (= 1 (count (:listeners @headline-state)))
                            "two viewers share one render")
                        (publish! "second")
                        (js/Promise.all (into-array (map #(read-until % "" "second") @readers)))))
               (.then (fn [texts]
                        (is (= 2 (count texts)) "both viewers receive the change")))
               (.catch (fn [e] (is false (str e))))
               (.finally (fn []
                           (doseq [r @readers] (.cancel r))
                           (done)))))))

(s/defui live-page []
  (let [h (sc/server-component (headline))]
    ($ :main ($ h))))

(deftest live-document-render
  (async done
         (swap! headline-state assoc :title "second")
         (-> (server/provide-request-event
              (server/create-request-event (js/Request. "http://localhost/"))
              #(s/render-to-string-async ($ live-page) {:plugins [sc/plugin]}))
             (.then (fn [html]
                      (is (str/includes? html "<h1><!--lh:0--><!--!$-->second<!--lh:/0--></h1>")
                          "the document renders the current value")
                      (is (str/includes? html ".r(\"solid/server-components-test/headline\",")
                          "the browser adopts the live component by reference")
                      (is (empty? (:listeners @headline-state))
                          "the document render unsubscribes once it is written")))
             (.catch (fn [e] (is false (str e))))
             (.finally done))))
