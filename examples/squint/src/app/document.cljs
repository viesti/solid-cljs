(ns app.document
  "The document shell around the app in Solid's start mode."
  (:require ["@solidjs/web" :refer [HydrationScript]]))

;; A static import, relative to this namespace's output in out/app. Solid's plugin finds it and
;; puts the stylesheet in the server-rendered head, and Vite updates it in place when the file
;; changes. In development Squint turns a :require into a dynamic import, which the plugin misses.
(js* "import \"../../src/app/style.css\"")

(defn Document [props]
  #jsx [:html {:lang "en"}
        [:head
         [:meta {:charset "utf-8"}]
         [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
         [:title "Solid with Squint"]
         [:link {:rel "icon" :href "data:,"}]
         [HydrationScript]]
        [:body (.-children props)]])

(def default Document)
