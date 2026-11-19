(ns solid.performance
  "Solid's performance tracks for the browser's performance panel, in a namespace of their own
  so that only builds that use them include them."
  (:require ["@solidjs/web/performance-tracks" :as tracks]))

(defn enable-performance-tracks
  "Records Solid's reactive work as tracks in the browser's performance panel, and returns a
  function that stops recording. `opts` takes the options of Solid's
  `enablePerformanceTracks`, as a map."
  ([] (tracks/enablePerformanceTracks))
  ([opts] (tracks/enablePerformanceTracks (clj->js opts))))
