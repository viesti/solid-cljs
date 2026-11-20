(ns solid.ssr-test
  (:require [cljs.test :refer [deftest is async]]
            [solid.core :as s :refer [$ defui]]
            [solid.server]
            ["@solidjs/router" :refer [createRouter memoryHistory]]))

(defn- html [root]
  (s/render-to-string root))

(deftest static-elements
  (is (= "<div id=\"x\" class=\"card\" style=\"width:10px;z-index:2\">a&amp;b <i>c</i></div>"
         (html ($ :div.card {:id "x" :style {:width 10 :z-index 2}} "a&b " ($ :i "c")))))
  (is (= "<input type=\"checkbox\" required>"
         (html ($ :input {:type :checkbox :required true :hidden false})))))

(deftest dynamic-attributes-and-children
  (let [n (s/signal 1)
        t (s/signal "T\"")
        on (s/signal true)]
    (is (= "<p class=\"a on\" title=\"T&quot;\">n=1&lt;b&gt;&lt;b></p>"
           (html ($ :p.a {:title @t :class {:on @on}} "n=" @n "<b>" (str "<" "b>")))))))

(deftest properties-events-and-refs
  (let [v (s/signal "val")
        called (atom false)]
    (is (= "<div><input checked value=\"val\"><span><b>raw</b></span><button>go</button></div>"
           (html ($ :div
                    ($ :input {:value @v :checked true :on-input identity})
                    ($ :span {:inner-html (str "<b>" "raw</b>")})
                    ($ :button {:on-click identity :ref #(reset! called true)} "go")))))
    (is (false? @called) "refs do not run on the server")))

(defui card [{:keys [title children]}]
  ($ :section ($ :h2 title) children))

(deftest components
  (is (= "<section><h2>Hello</h2><p>body</p></section>"
         (html ($ card {:title (str "Hel" "lo")} ($ :p "body")))))
  (is (= "<section><h2>root</h2></section>"
         (html (fn [] ($ card {:title "root"}))))))

(deftest control-flow
  (let [xs (s/signal [1 2 3])
        user (s/signal {:name "Ann"})]
    (is (= "<ul><li>1</li><li>2</li><li>3</li></ul>"
           (html ($ :ul (s/for [[x] @xs] ($ :li x))))))
    (is (= "<p>Hi Ann</p>"
           (html (s/if-let [u @user] ($ :p "Hi " (:name u)) ($ :p "none")))))
    (is (= "<p>two</p>"
           (html (s/cond (= 1 2) ($ :p "one") (= 2 2) ($ :p "two") :else ($ :p "other")))))
    ;; Solid's server hides error messages that are not marked safe
    (is (= "<span>caught Internal Server Error</span>"
           (html (s/try ($ (fn [_] (throw (js/Error. "boom"))))
                        (catch e ($ :span "caught " (.-message e)))))))))

(defui greeting-view []
  (let [greeting (s/memo #(js/Promise. (fn [resolve] (js/setTimeout (fn [] (resolve "Moi")) 10))))]
    (s/loading ($ :p "Loading") ($ :p (greeting) ", world"))))

(deftest async-reads
  (async done
         (do
           (is (re-matches #"<p>Loading</p><script>.*\[\"\"\].*</script>" (html greeting-view))
               "render-to-string renders the fallback and a hydration script for the pending read")
           (-> (s/render-to-string-async greeting-view)
               (.then (fn [out]
                        (is (re-find #"Moi, world" out))
                        (done)))))))

(defui badge [props]
  (let [props (s/merge-props {:tone "info"} props)]
    ($ :span {:class (:tone props)} (:label props))))

(deftest spreads
  (is (= "<p title=\"own\" class=\"a b\" data-k=\"v\">x</p>"
         (html ($ :p {:& {:title "spread" :class [:a :b] :data-k "v" :on-click identity} :title "own"} "x"))))
  (is (= "<span class=\"warn\">own</span>"
         (html ($ badge {:& {:label "spread" :tone "warn"} :label "own"})))))

(defui titled []
  (s/use-head {:tag :title :props {:children "Demo"}})
  ($ :p "body"))

(deftest head-errors-and-boundaries
  (let [head (atom nil)]
    (is (= "<p>body</p>" (s/render-to-string titled {:on-head #(reset! head %)})))
    ;; Without a </head> in the render, Solid sets the title from a script
    (is (re-find #"\"Demo\"" (str @head))))
  (is (= "<span>caught shown</span>"
         (html (s/try ($ (fn [_] (throw (s/safe-error (js/Error. "shown")))))
                      (catch e ($ :span "caught " (.-message e)))))))
  (is (= "<p>static</p>" (html ($ s/no-hydration ($ :p "static")))))
  (is (= "<p>kw</p>" (html ($ :p (keyword "kw"))))))

(defui slow-view []
  (let [v (s/memo #(js/Promise. (fn [resolve] (js/setTimeout (fn [] (resolve "late")) 10))))]
    (s/loading ($ :p "wait") ($ :p (v)))))

(deftest streaming
  (async done
         (let [chunks (atom [])
               writable #js {:write (fn [chunk] (swap! chunks conj (str chunk)) true)
                             :end (fn []
                                    (is (re-find #"<p>wait</p>" (first @chunks)) "the shell has the fallback")
                                    (is (re-find #"late" (apply str @chunks)) "a later chunk has the content")
                                    (done))}]
           (s/pipe (s/render-to-stream slow-view) writable))))

(defui h-card [{:keys [title]}]
  (s/$h :section (s/$h :h2 title) (s/$h :p {:class :body} "text")))

(deftest hyperscript-on-the-server
  (is (= "<div><section><h2>T</h2><p class=\"body\">text</p></section></div>"
         (html (s/$h :div (s/$h h-card {:title "T"}))))))

(defn- delayed [v ms]
  (js/Promise. (fn [resolve] (js/setTimeout #(resolve v) ms))))

(defui revealed []
  (let [a (s/memo #(delayed "A-content" 40))
        b (s/memo #(delayed "B-content" 5))]
    (s/reveal {:order :together}
              (s/loading ($ :p "A-wait") ($ :p (a)))
              (s/loading ($ :p "B-wait") ($ :p (b))))))

(defn- stream-chunks [root on-end]
  (let [chunks (atom [])]
    (s/pipe (s/render-to-stream root)
            #js {:write (fn [chunk] (swap! chunks conj (str chunk)) true)
                 :end #(on-end @chunks)})))

(deftest reveal-together-when-streaming
  (async done
         (stream-chunks
          revealed
          (fn [chunks]
            (let [index-of (fn [re] (first (keep-indexed #(when (re-find re %2) %1) chunks)))
                  b-content (index-of #"B-content")
                  a-content (index-of #"A-content")]
              (is (re-find #"A-wait" (first chunks)) "the shell has the first fallback")
              (is (re-find #"B-wait" (first chunks)) "the shell has the second fallback")
              (is (< b-content a-content) "the faster content streams first")
              (is (not (re-find #"\$df\(" (nth chunks b-content))) "but is not revealed on its own")
              (is (re-find #"\$dfj\(\[\"\d+\",\"\d+\"\]\)" (nth chunks a-content))
                  "both boundaries reveal together once the slower one is ready"))
            (done)))))

(defui home-page [] ($ :p "Home"))

(deftest router-links-on-the-server
  (let [router (createRouter #js {:routes #js [#js {:path "/" :component home-page}]
                                  :history (memoryHistory "/")})
        out (html ($ router (fn [props]
                              ($ :nav ($ :a#home {:href "/"} "Home") ($ :a#other {:href (str "/oth" "er")} "Other")
                                 (.-children ^js props)))))]
    (is (re-find #"<a id=\"home\" href=\"/\"[^>]*aria-current=\"page\"" out))
    (is (not (re-find #"<a id=\"other\"[^>]*aria-current" out)))
    (is (re-find #"<p>Home</p>" out))))

(deftest cookies
  (is (= {"a" "1" "b" "two"} (s/parse-cookie-header "a=1; b=two")))
  (is (re-find #"^session=abc; Path=/; Max-Age=60; HttpOnly"
               (s/serialize-cookie "session" "abc" {:max-age 60 :path "/" :http-only true}))))

(deftest request-scope
  (let [event (solid.server/create-request-event (js/Request. "http://localhost/page?x=1"))]
    (is (= "http://localhost/page?x=1"
           (solid.server/provide-request-event event #(.-url (.-request (s/request-event))))))
    (is (nil? (s/request-event)) "outside the scope there is no event")))
