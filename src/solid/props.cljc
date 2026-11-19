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

(def dom-properties
  "Attribute names that Solid sets as DOM properties, to their property names."
  {"value" "value"
   "checked" "checked"
   "selected" "selected"
   "muted" "muted"
   "default-value" "defaultValue"
   "default-checked" "defaultChecked"
   "default-selected" "defaultSelected"
   "default-muted" "defaultMuted"
   "inner-html" "innerHTML"
   "text-content" "textContent"
   "inner-text" "innerText"})

(def ^:private dom-property-names (set (vals dom-properties)))

(defn attr-name
  "Returns the name under which Solid's `spread` sets the attribute or prop `n`, written as a
  keyword's name (`on-click`, `stroke-width`) or as a JavaScript prop name (`onClick`,
  `strokeWidth`), or nil for children. Events become `onClick`, DOM properties their property
  names, and other camelCase names kebab-case attribute names."
  [n]
  (cond
    (= "children" n) nil
    (str/starts-with? n "on-") (prop-name n)
    (re-matches #"on[A-Z].*" n) n
    (dom-properties n) (dom-properties n)
    (dom-property-names n) n
    (re-find #"[A-Z]" n) (name (prop-key n))
    :else n))
