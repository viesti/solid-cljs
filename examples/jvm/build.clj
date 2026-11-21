(ns build
  "Builds target/todos.jar: the server, compiled ahead of time, with the browser bundle from
  dist/ under public/ on its classpath. Run `shadow-cljs release app` and `vite build` first."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")

(defn uber [_]
  (let [basis (b/create-basis {:aliases [:server]})]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["dist"] :target-dir (str class-dir "/public")})
    (b/compile-clj {:basis basis :ns-compile '[demo.server] :class-dir class-dir})
    (b/uber {:basis basis :class-dir class-dir :uber-file "target/todos.jar" :main 'demo.server
             ;; sqlite-jdbc loads a native library, which Java 25 warns about otherwise
             :manifest {"Enable-Native-Access" "ALL-UNNAMED"}})))
