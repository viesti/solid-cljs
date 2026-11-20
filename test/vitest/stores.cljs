(ns vitest.stores
  (:require-macros [solid.core])
  (:require
   [solid.core :as s :refer [$]]
   ["@solidjs/testing-library" :refer [render]]
   ["vitest" :refer [expect test]]))

(defn- mount [el]
  (.-container (render el)))

(defn- q [^js c selector]
  (.querySelector c selector))

(defn- wait [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(test
 "Stores from maps, path reads and writes, and draft functions"
 (fn []
   (let [[st set-st] (s/store {:user {:name "Ann"} :todos [{:id 1 :title "a"} {:id 2 :title "b"}]})
         c (mount ($ :div
                     ($ :p#name (s/sget st :user :name))
                     ($ :ul (s/for [[t] (s/sget st :todos)] ($ :li (.-title t))))))
         first-li (q c "li")]
     (.. (expect (.-textContent (q c "#name"))) (toBe "Ann"))
     (s/sset! set-st [:user :name] "Bo")
     (s/supdate! set-st [:todos 0 :title] str "!")
     (s/flush)
     (.. (expect (.-textContent (q c "#name"))) (toBe "Bo"))
     (.. (expect (.-textContent (q c "ul"))) (toBe "a!b"))
     (.. (expect (identical? first-li (q c "li"))) (toBe true))
      ;; set! returns the object it assigns, which must not replace the store
     (set-st (fn [^js d] (set! (.-user d) #js {:name "Cy"})))
     (s/flush)
     (.. (expect (.-textContent (q c "#name"))) (toBe "Cy"))
     (.. (expect (.-textContent (q c "ul"))) (toBe "a!b"))
     (set-st (fn [^js d] (s/replacement (assoc (s/store->clj d) :todos [{:id 2 :title "b"}]))))
     (s/flush)
     (.. (expect (.-textContent (q c "ul"))) (toBe "b"))
     (.. (expect (clj->js (s/store->clj st))) (toEqual (clj->js {:user {:name "Cy"} :todos [{:id 2 :title "b"}]}))))))

(test
 "Projections keep item identity by key, and reconcile! too"
 (fn []
   (let [src (s/signal [{:id 1 :n "a"} {:id 2 :n "b"}])
         proj (s/projection (fn [] (map #(update % :n str "!") @src)) nil {:key "id"})
         [st set-st] (s/store {:items [{:id 1 :n "x"}]})
         c (mount ($ :div
                     ($ :ul#p (s/for [[i] proj] ($ :li (.-n i))))
                     ($ :ul#r (s/for [[i] (s/sget st :items)] ($ :li (.-n i))))))
         p1 (q c "#p li")
         r1 (q c "#r li")]
     (.. (expect (.-textContent (q c "#p"))) (toBe "a!b!"))
     (reset! src [{:id 1 :n "A"} {:id 3 :n "c"}])
     (s/flush)
     (.. (expect (.-textContent (q c "#p"))) (toBe "A!c!"))
     (.. (expect (identical? p1 (q c "#p li"))) (toBe true))
     (set-st (fn [^js d] (s/reconcile! (.-items d) [{:id 1 :n "y"} {:id 4 :n "z"}] :id)))
     (s/flush)
     (.. (expect (.-textContent (q c "#r"))) (toBe "yz"))
     (.. (expect (identical? r1 (q c "#r li"))) (toBe true)))))

(test
 "Optimistic stores show writes in an action and revert when it settles"
 (fn []
   (let [[st set-st] (s/optimistic-store {:count 1})
         bump (s/action []
                        (s/supdate! set-st [:count] inc)
                        (s/yield (wait 30)))
         c (mount ($ :p (s/sget st :count)))]
     (bump)
     (-> (wait 5)
         (.then (fn [] (.. (expect (.-textContent c)) (toBe "2"))))
         (.then #(wait 60))
         (.then (fn [] (.. (expect (.-textContent c)) (toBe "1"))))))))
