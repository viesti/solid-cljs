(ns solid.props
  (:require [clojure.string :as str]))

(defn prop-name
  "Returns the JavaScript name of the component prop `k`: kebab-case becomes camelCase, so
  `:on-click` is `onClick`, except for `aria-*` and `data-*` names."
  [k]
  (let [n (if (keyword? k) (name k) (str k))]
    (if (or (str/starts-with? n "aria-") (str/starts-with? n "data-") (not (str/includes? n "-")))
      n
      (let [[first-word & words] (str/split n #"-")]
        (apply str first-word (map str/capitalize words))))))

(defn prop-key
  "Returns the keyword of the JavaScript prop name `s`, the inverse of `prop-name`."
  [s]
  (keyword (if (or (str/starts-with? s "aria-") (str/starts-with? s "data-"))
             s
             (str/replace s #"[A-Z]" #(str "-" (str/lower-case %))))))
