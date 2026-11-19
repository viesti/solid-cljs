(ns bench.core
  "Compares elements compiled to templates (`$`) with runtime hyperscript (`$h`) on a
  table like the js-framework-benchmark's."
  (:require [solid.core :as s :refer [$ defui]]))

(defn- make-rows [n]
  (vec (for [i (range n)]
         {:id i :label (s/signal (str "row " i))})))

(defui table-compiled [props]
  ($ :table {:class "table"}
     ($ :tbody
        (s/for [[row] (:rows props)]
          ($ :tr
             ($ :td {:class "col-md-1"} (:id row))
             ($ :td {:class "col-md-4"} ($ :a {:on-click #(reset! (:label row) "clicked")} @(:label row)))
             ($ :td {:class "col-md-1"} ($ :a ($ :span {:class "remove" :aria-hidden "true"})))
             ($ :td {:class "col-md-6"}))))))

(defui table-hyperscript [props]
  (s/$h :table {:class "table"}
        (s/$h :tbody
              (s/for [[row] (:rows props)]
                (s/$h :tr
                      (s/$h :td {:class "col-md-1"} (:id row))
                      (s/$h :td {:class "col-md-4"} (s/$h :a {:on-click #(reset! (:label row) "clicked")} @(:label row)))
                      (s/$h :td {:class "col-md-1"} (s/$h :a (s/$h :span {:class "remove" :aria-hidden "true"})))
                      (s/$h :td {:class "col-md-6"}))))))

;; The same table with each row as a component, to measure component creation
(defui row-compiled [{:keys [row]}]
  ($ :tr
     ($ :td {:class "col-md-1"} (:id row))
     ($ :td {:class "col-md-4"} ($ :a {:on-click #(reset! (:label row) "clicked")} @(:label row)))
     ($ :td {:class "col-md-1"} ($ :a ($ :span {:class "remove" :aria-hidden "true"})))
     ($ :td {:class "col-md-6"})))

(defui row-hyperscript [{:keys [row]}]
  (s/$h :tr
        (s/$h :td {:class "col-md-1"} (:id row))
        (s/$h :td {:class "col-md-4"} (s/$h :a {:on-click #(reset! (:label row) "clicked")} @(:label row)))
        (s/$h :td {:class "col-md-1"} (s/$h :a (s/$h :span {:class "remove" :aria-hidden "true"})))
        (s/$h :td {:class "col-md-6"})))

(defui rows-compiled [props]
  ($ :table {:class "table"}
     ($ :tbody (s/for [[row] (:rows props)] ($ row-compiled {:row row})))))

(defui rows-hyperscript [props]
  (s/$h :table {:class "table"}
        (s/$h :tbody (s/for [[row] (:rows props)] (s/$h row-hyperscript {:row row})))))

(defn- now [] (js/performance.now))

(defn- run-once [table n]
  (let [container (js/document.createElement "div")
        _ (.appendChild js/document.body container)
        rows (s/root (fn [_] (make-rows n)))
        t0 (now)
        dispose (s/render ($ table {:rows rows}) container)
        t1 (now)
        _ (doseq [r (take-nth 10 rows)] (reset! (:label r) "updated"))
        _ (s/flush)
        t2 (now)
        ;; Release builds elide assert, and a benchmark that renders nothing must fail
        _ (when-not (= n (.-length (.querySelectorAll container "tr")))
            (throw (js/Error. "not every row rendered")))
        _ (when-not (= "updated" (.-textContent (.querySelector container "tr a")))
            (throw (js/Error. "update not applied")))
        _ (dispose)
        t3 (now)]
    (.remove container)
    {:create (- t1 t0) :update (- t2 t1) :clear (- t3 t2)}))

(defn- median [xs]
  (nth (sort xs) (quot (count xs) 2)))

(defn- run-case [n iterations variants]
  (dotimes [_ 3] (doseq [t (vals variants)] (run-once t n)))
  (let [samples (reduce (fn [acc _]
                            (reduce-kv (fn [acc k t] (update acc k conj (run-once t n))) acc variants))
                          {:compiled [] :hyperscript []}
                          (range iterations))]
      (into {} (for [[k xs] samples]
                 [k (into {} (for [m [:create :update :clear]]
                               [m (median (map m xs))]))]))))

(defn run []
  (let [tables {:compiled table-compiled :hyperscript table-hyperscript}
        components {:compiled rows-compiled :hyperscript rows-hyperscript}
        results {:rows-1000 (run-case 1000 15 tables)
                 :rows-10000 (run-case 10000 5 tables)
                 :component-rows-1000 (run-case 1000 15 components)
                 :component-rows-10000 (run-case 10000 5 components)}]
    (set! (.-benchResults js/window) (clj->js results))
    results))

(defn init []
  (set! (.-runBench js/window) run))
