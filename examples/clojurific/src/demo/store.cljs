(ns demo.store
  "Holds the database functions, which the server entry installs. Clojurific compiles each
  namespace once for both the browser and the server, so code that both load reaches the
  database through here instead of requiring it.")

(defonce ^:private db (atom nil))

(defn install!
  "Makes `functions`, a map of keywords to the database's functions, available to `call`."
  [functions]
  (reset! db functions))

(defn call
  "Calls the installed database function `k` with `args`."
  [k & args]
  (apply (get @db k) args))
