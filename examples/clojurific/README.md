# solid-cljs demo with Clojurific

The demo app of [`../shadow-cljs`](../shadow-cljs), built with
[Clojurific](https://github.com/WhimsicalCode/clojurific) instead of shadow-cljs. Clojurific
compiles each namespace to an ES module inside Vite, so Vite resolves and bundles the npm
packages without extra configuration. src/demo/core.cljs is the same file in both examples.

## Running it

Needs Node.js 22 and Java 17 or later. The Clojure CLI is not needed: Clojurific resolves
deps.edn itself.

```bash
cd examples/clojurific
yarn install
yarn dev
```

Open [localhost:3000](http://localhost:3000). `yarn build` writes a production bundle to `dist/`,
and `yarn preview` serves it. `yarn dev:local` builds against the solid-cljs checkout this folder
lives in instead of the pinned commit.

## How it is set up

- **deps.edn** pulls solid-cljs from GitHub at a pinned commit.
- **vite.config.mjs** adds Clojurific's Vite plugin. Vite 8 is required.
- **index.html** loads src/demo/core.cljs directly, and the plugin compiles it.
- **package.json** sets `"type": "module"`, which Clojurific requires.

- **cljs.edn** sets `:solid/refresh` in the `dev` profile, which the plugin uses while the dev
  server runs, so a hot reload re-renders only the components whose code changed and keeps the
  state of the others. The demo renders once, under `defonce`.

Clojurific is experimental, and version 0.12.15 was tested here. In that version a
`^:dev/after-load` hook does not run after hot updates, which `:solid/refresh` makes unneeded.
