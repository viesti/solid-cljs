# Examples

The same demo app (a counter, a todo list and an async greeting) built three ways. Each folder is
a self-contained project that depends on solid-cljs through a git dependency.

| Folder | Compiler | How Solid is used |
|---|---|---|
| [shadow-cljs](shadow-cljs) | shadow-cljs, with Vite bundling the npm packages | solid-cljs macros (`$`, `defui`, `s/for`) |
| [clojurific](clojurific) | Clojurific, inside Vite 8 | solid-cljs macros, the same demo/core.cljs as shadow-cljs |
| [squint](squint) | Squint, with Solid's own JSX compiler | `#jsx` literals and the `solid.squint` helpers |

[jvm](jvm) has the shared todo list only, with its server in Clojure on the JVM: a reitit API
and server-sent events, or `defserverfn` calls that the JVM answers in Solid's server function
protocol, with the page rendered in the browser by shadow-cljs code.

Each folder's README explains how to run it.
