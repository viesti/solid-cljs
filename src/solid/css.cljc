(ns solid.css)

(def unit-less-props
  "CSS properties whose numeric values take no unit."
  #{"animation-iteration-count" "aspect-ratio" "border-image-outset" "border-image-slice"
    "border-image-width" "box-flex" "box-flex-group" "box-ordinal-group" "column-count"
    "columns" "flex" "flex-grow" "flex-positive" "flex-shrink" "flex-negative" "flex-order"
    "grid-area" "grid-row" "grid-row-end" "grid-row-span" "grid-row-start" "grid-column"
    "grid-column-end" "grid-column-span" "grid-column-start" "font-weight" "line-clamp"
    "line-height" "opacity" "order" "orphans" "tab-size" "widows" "z-index" "zoom"
    "fill-opacity" "flood-opacity" "stop-opacity" "stroke-dasharray" "stroke-dashoffset"
    "stroke-miterlimit" "stroke-opacity" "stroke-width"})
