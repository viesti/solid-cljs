(ns solid.attribution
  "Solid's attribution engine, which explains why computations ran and what user interactions
  cost, in a namespace of its own so that only builds that use it include it. Development and
  observe builds record; production builds get an inert engine with the same functions."
  (:require ["solid-js/attribution" :refer [attribution costs feedback formatRerun subscriptions why]]
            [solid.props :as props]))

(defn- js-options [opts]
  (let [o #js {}]
    (doseq [[k v] opts] (aset o (props/prop-name k) v))
    o))

(defn enable!
  "Starts recording, and returns a function that stops this recording. `opts` takes the
  options of Solid's `attribution.enable`, such as `:history-limit`."
  ([] (.enable attribution))
  ([opts] (.enable attribution (js-options opts))))

(defn disable!
  "Stops every recording and clears what was recorded."
  []
  (.disable attribution))

(defn history
  "Returns the records of type `type` since recording started, oldest first: `:rerun`,
  `:waterfall`, `:hold`, `:navigation` or `:interaction`."
  [type]
  (.history attribution (name type)))

(defn explain
  "Returns the recorded re-runs of `target`, a memo, an effect or another computation: what
  changed and why it ran. Solid's `why`."
  [target]
  (why target))

(defn dependencies
  "Returns the names of what `target` currently reads. Solid's `subscriptions`."
  [target]
  (subscriptions target))

(defn cost-tables
  "Returns the run counts and times of computations and writes since recording started."
  []
  (costs))

(defn feedback-tables
  "Returns how long interactions and navigations took until the page answered them."
  []
  (feedback))

(defn format-rerun
  "Returns a readable description of a re-run record from `explain` or `history`."
  [record]
  (formatRerun record))
