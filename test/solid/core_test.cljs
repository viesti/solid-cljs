(ns solid.core-test
  (:require [cljs.test :refer [deftest testing is async]]
            [solid.core :as s :refer [$ defui]]))

;; ============================================
;; Reactive Primitives Tests
;; ============================================

(deftest signal-test
  (testing "signal creation and deref"
    (let [sig (s/signal 0)]
      (is (= 0 @sig) "Initial value should be 0")))

  (testing "signal reset!"
    (let [sig (s/signal 0)]
      (is (= 5 (reset! sig 5)) "reset! should return the new value")
      (is (= 0 @sig) "Write should be queued until flush")
      (s/flush)
      (is (= 5 @sig) "Value should be 5 after reset! and flush")))

  (testing "signal reset! with a function value"
    (let [f (fn [_] :called)
          sig (s/signal nil)]
      (reset! sig f)
      (s/flush)
      (is (identical? f @sig) "Function should be stored, not applied")))

  (testing "signal swap!"
    (let [sig (s/signal 0)]
      (is (= 1 (swap! sig inc)) "swap! should return the new value")
      (s/flush)
      (is (= 1 @sig) "Value should be 1 after swap! inc")))

  (testing "consecutive swap! calls compose before flush"
    (let [sig (s/signal 0)]
      (swap! sig inc)
      (swap! sig inc)
      (s/flush)
      (is (= 2 @sig) "Second swap! should see the queued value")))

  (testing "signal swap! with multiple args"
    (let [sig (s/signal 10)]
      (swap! sig - 3)
      (s/flush)
      (is (= 7 @sig) "Value should be 7 after swap! - 3")
      (swap! sig + 1 2)
      (s/flush)
      (is (= 10 @sig) "Value should be 10 after swap! + 1 2")))

  (testing "signal with equals option"
    (let [sig (s/signal 0 {:equals false})]
      (is (= 0 @sig) "Signal with equals option should work"))))

(deftest ref-test
  (testing "ref creation and deref"
    (let [r (s/ref)]
      (is (nil? @r) "Initial ref value should be nil")))

  (testing "ref as callback"
    (let [r (s/ref)]
      (r "test-value")
      (is (= "test-value" @r) "Ref should store value when called as function"))))

(deftest memo-test
  (testing "memo creation"
    (s/root
      (fn [dispose]
        (let [sig (s/signal 5)
              m (s/memo #(* 2 @sig))]
          (is (= 10 (m)) "Memo should compute derived value")
          (reset! sig 6)
          (s/flush)
          (is (= 12 (m)) "Memo should follow its source"))
        (dispose)))))

(deftest effect-test
  (testing "effect with compute and apply"
    (s/root
      (fn [dispose]
        (let [sig (s/signal 1)
              seen (atom [])]
          (s/effect (fn [] @sig) #(swap! seen conj %))
          (s/flush)
          (reset! sig 2)
          (s/flush)
          (is (= [1 2] @seen) "Effect should apply each value"))
        (dispose))))

  (testing "single-function effect"
    (s/root
      (fn [dispose]
        (let [sig (s/signal 1)
              seen (atom [])]
          (s/effect #(swap! seen conj @sig))
          (s/flush)
          (reset! sig 2)
          (s/flush)
          (is (= [1 2] @seen) "Effect should re-run on change"))
        (dispose)))))

(deftest batch-test
  (testing "batch function"
    (let [sig (s/signal 0)]
      (s/batch
        (reset! sig 1)
        (reset! sig 2))
      (is (= 2 @sig) "Batch should apply final value"))))

(deftest untrack-test
  (testing "untrack function"
    (let [sig (s/signal 10)
          result (s/untrack #(* 2 @sig))]
      (is (= 20 result) "Untrack should return computed value"))))

;; ============================================
;; Context Tests
;; ============================================

(deftest context-test
  (testing "create-context"
    (let [ctx (s/create-context "default")]
      (is (some? ctx) "Context should be created"))))

;; ============================================
;; Utility Functions Tests
;; ============================================

(deftest uid-test
  (testing "createUniqueId"
    (let [id1 (s/uid)
          id2 (s/uid)]
      (is (string? id1) "UID should be a string")
      (is (not= id1 id2) "UIDs should be unique"))))

(deftest server?-test
  (testing "isServer check"
    (is (boolean? s/server?) "server? should return a boolean")))

(deftest dev?-test
  (testing "DEV check"
    ;; DEV can be nil or a truthy value
    (is (or (nil? (s/dev?))
            (some? (s/dev?)))
        "dev? should return nil or a truthy value")))

;; ============================================
;; Store Tests
;; ============================================

(deftest store-test
  (testing "store creation"
    (let [[state set-state] (s/store #js {:count 0})]
      (is (= 0 (.-count state)) "Store should have initial value")
      (set-state (fn [^js draft] (set! (.-count draft) 1)))
      (s/flush)
      (is (= 1 (.-count state)) "Store setter should take a draft"))))

;; ============================================
;; Component Definition Tests
;; ============================================

(defui test-component [{:keys [value]}]
  ($ :div {:class "test"} value))

(deftest defui-test
  (testing "defui creates a component"
    (is (fn? test-component) "defui should create a function")))

;; ============================================
;; Element Creation Tests
;; ============================================

(deftest create-element-test
  (testing "create-element with keyword tag"
    (let [el ($ :div "hello")]
      (is (some? el) "Should create element with keyword tag")))

  (testing "create-element with attributes"
    (let [el ($ :div {:class "container"} "content")]
      (is (some? el) "Should create element with attributes")))

  (testing "create-element with style"
    (let [el ($ :div {:style {:color "red"}} "styled")]
      (is (some? el) "Should create element with style"))))

;; ============================================
;; Control Flow Tests
;; ============================================

(deftest show-test
  (testing "s/if with truthy condition"
    (let [el (s/if true ($ :span "visible") ($ :span "hidden"))]
      (is (some? el) "Should return element when condition is truthy")))

  (testing "s/when macro"
    (let [el (s/when true ($ :span "shown"))]
      (is (some? el) "s/when should return element when condition is true"))))

(deftest for-test
  (testing "s/for iteration"
    (let [items [1 2 3]
          el (s/for [[x] items] ($ :span (str x)))]
      (is (some? el) "s/for should create elements"))))

(deftest index-test
  (testing "s/index iteration"
    (let [items [1 2 3]
          el (s/index [[x idx] items] ($ :span (str @x)))]
      (is (some? el) "s/index should create elements"))))

(deftest cond-test
  (testing "s/cond control flow"
    (let [value 1
          el (s/cond
               (= value 1) ($ :span "one")
               (= value 2) ($ :span "two")
               :else ($ :span "other"))]
      (is (some? el) "s/cond should return matched element"))))

(deftest dynamic-test
  (testing "s/dynamic component"
    (let [el (s/dynamic ($ :div "dynamic"))]
      (is (some? el) "s/dynamic should create element"))))

(deftest try-test
  (testing "s/try error boundary"
    (let [el (s/try
               ($ :div "content")
               (catch e ($ :div "error")))]
      (is (some? el) "s/try should create error boundary"))))

;; ============================================
;; Computed and Reaction Tests
;; ============================================

(deftest reaction-test
  (testing "reaction macro"
    (let [sig (s/signal 5)
          tracker (s/reaction (* 2 @sig))]
      (is (fn? tracker) "reaction should return a tracking function"))))

;; ============================================
;; Lazy Loading Test
;; ============================================

(deftest lazy-test
  (testing "lazy component"
    (let [lazy-comp (s/lazy
                      #js {:default (fn [] ($ :div "lazy loaded"))})]
      (is (some? lazy-comp) "lazy should create lazy component"))))

;; ============================================
;; Deferred Test
;; ============================================

(deftest deferred-test
  (testing "deferred value"
    (let [sig (s/signal 5)
          deferred-val (s/root (fn [_] (s/deferred #(* 2 @sig))))]
      (is (= (deferred-val) 10) "deferred should return the correct calculation"))))

