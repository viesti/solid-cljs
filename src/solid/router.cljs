(ns solid.router
  "@solidjs/router 2.0 with ClojureScript data: route tables as vectors of maps and options as
  maps with keyword keys. Apps that require this namespace install `@solidjs/router`. Links and
  forms are plain `:a` and `:form` elements, which the router claims."
  (:require ["@solidjs/router" :as r]
            [solid.props :as props]))

(defn- js-options
  "Converts an options map to a JavaScript object with camelCase keys, or returns nil."
  [opts]
  (when opts
    (let [o #js {}]
      (doseq [[k v] opts :when (some? v)] (aset o (props/prop-name k) v))
      o)))

(defn- route
  "Converts a route map to the router's route definition."
  [{:keys [children] :as m}]
  (doto (js-options (dissoc m :children))
    (cond-> children (aset "children" (if (map? children)
                                        (route children)
                                        (into-array (map route children)))))))

(defn router
  "Returns a router component for `routes`, a vector of maps of `:path`, `:component` and
  optionally `:children`, `:preload`, `:match-filters` and `:info`. `opts` takes the router's
  options, such as `:base`, `:history`, `:preload`, `:links` and `:preload-links`. Render it with
  a component that places `(.-children props)`, the matched route:
  ```clojure
  (def app-router (router [{:path \"/\" :component home} {:path \"/users/:id\" :component user}]))

  ($ app-router (fn [props] ($ :main ($ nav) (.-children props))))
  ```"
  ([routes] (router routes nil))
  ([routes opts]
   (r/createRouter (doto (or (js-options opts) #js {})
                     (aset "routes" (into-array (map route routes)))))))

(defn memory-history
  "Returns a history kept in memory, starting at `initial`, for tests and the server."
  ([] (r/memoryHistory))
  ([initial] (r/memoryHistory initial)))

(def browser-history
  "Returns the browser's history, the default."
  r/browserHistory)

(def hash-history
  "Returns a history kept in the url's hash."
  r/hashHistory)

(def pending-links
  "The `:links` plugin that marks links to the destination being navigated to with
  `data-pending`."
  r/pendingLinks)

(defn intent-preload
  "Returns the `:preload-links` strategy that preloads a link's route on hover and focus."
  ([] (r/intentPreload))
  ([opts] (r/intentPreload (js-options opts))))

(defn use-params
  "Returns the current route's parameters as a reactive object: read `(.-id params)` where the
  value is used."
  []
  (r/useParams))

(defn use-location
  "Returns the current location as a reactive object with `pathname`, `search`, `hash`,
  `query` and `state`."
  []
  (r/useLocation))

(defn use-navigate
  "Returns a function that navigates to a path, or back and forward by a number, with the
  options `:replace`, `:scroll`, `:state` and `:resolve`."
  []
  (let [navigate (r/useNavigate)]
    (fn
      ([to] (navigate to))
      ([to opts] (navigate to (js-options opts))))))

(defn use-search-params
  "Returns the search parameters, a reactive object, and a function that merges a map into
  them, with the navigation options of `use-navigate`."
  []
  (let [[params set-params] (r/useSearchParams)]
    [params (fn
              ([m] (set-params (clj->js m)))
              ([m opts] (set-params (clj->js m) (js-options opts))))]))

(defn use-match
  "Returns an accessor of the match of the path that `path` returns against the current
  location, or nil."
  [path]
  (r/useMatch path))

(defn use-is-routing
  "Returns an accessor that is true while a navigation is in progress."
  []
  (r/useIsRouting))

(defn use-href
  "Returns an accessor of the href that the path `to` returns, resolved against the router."
  [to]
  (r/useHref to))

(defn use-before-leave
  "Runs `listener` before the router leaves the current route, which may prevent it."
  [listener]
  (r/useBeforeLeave listener))

(defn query
  "Returns a cached version of the async function `f`, keyed by `query-name` and its arguments,
  which `revalidate` refreshes. A server function works as `f`."
  [f query-name]
  (r/query f query-name))

(defn action
  "Returns a router action that runs the async function `f`, such as a server function, and
  revalidates queries afterwards. Submissions of it are tracked by `use-submissions`."
  ([f] (r/action f))
  ([f action-name] (r/action f action-name)))

(defn use-action
  "Returns a function that submits the action `a` with its arguments."
  [a]
  (r/useAction a))

(defn use-submissions
  "Returns the in-flight and settled submissions of the action `a`, optionally only those whose
  input passes `pred`."
  ([a] (r/useSubmissions a))
  ([a pred] (r/useSubmissions a pred)))

(defn revalidate
  "Refreshes cached queries: every one, those with the key or keys `ks`, and with `force` even
  those that are fresh."
  ([] (r/revalidate))
  ([ks] (r/revalidate (if (coll? ks) (into-array ks) ks)))
  ([ks force] (r/revalidate (if (coll? ks) (into-array ks) ks) force)))
