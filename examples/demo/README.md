# solid-cljs demo

A small app that uses solid-cljs with Solid 2 as a git dependency: a counter, a todo list and an
async greeting under `s/loading`.

## Running it

Needs Node.js 22, Java 17 or later and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
cd examples/demo
yarn install
yarn dev
```

Open [localhost:3000](http://localhost:3000). `yarn build` writes a production bundle to `dist/`,
and `yarn preview` serves it.

## How it is set up

- **deps.edn** pulls solid-cljs from GitHub at a pinned commit. shadow-cljs reads it because
  shadow-cljs.edn sets `:deps true`.
- **package.json** lists the npm packages solid-cljs requires: `solid-js`, `@solidjs/web` and
  `@solidjs/h`.
- **shadow-cljs.edn** compiles to ES modules with `:js-provider :import`, which leaves the npm
  imports to Vite. shadow-cljs cannot bundle `@solidjs/web` itself.
- **vite.config.mjs** serves `public/` and does not watch shadow-cljs' output, so that shadow-cljs
  hot reloads changes instead of Vite reloading the page.

`yarn dev:local` builds against the solid-cljs checkout this folder lives in instead of the pinned
commit, through the `:local` alias in deps.edn.
