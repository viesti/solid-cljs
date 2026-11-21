(ns user
  "Starts the API server from a REPL: `(start!)`, `(stop!)`.")

(defn start!
  ([] (start! 8080))
  ([port]
   (require 'demo.server)
   ((resolve 'demo.server/start!) port)))

(defn stop! []
  (require 'demo.server)
  ((resolve 'demo.server/stop!)))
