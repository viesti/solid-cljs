(ns solid.ssr-test
  (:require [cljs.test :refer [deftest testing is async]]
            [solid.core :as s :refer [$ defui]]))

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
