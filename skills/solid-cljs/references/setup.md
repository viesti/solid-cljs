# Setting up a solid-cljs project

## Dependencies

solid-cljs's Solid 2 port is a git dependency, not a Clojars release. Pin a commit of the
`solid-2` branch:

```clojure
;; deps.edn
{:deps {com.github.roman01la/solid-cljs
        {:git/url "https://github.com/viesti/solid-cljs"
         :git/sha "<full commit sha>"}}}
```

```bash
npm i solid-js @solidjs/web @solidjs/h
```

shadow-cljs reads deps.edn when shadow-cljs.edn sets `:deps true`. Upgrade solid-cljs and the
Solid npm packages together, and keep the npm versions locked (yarn.lock or
package-lock.json).

## shadow-cljs with Vite

shadow-cljs cannot bundle `@solidjs/web` (the Closure Compiler rejects its dynamic `import()`),
so shadow-cljs writes ES modules and leaves npm imports to Vite:

```clojure
;; shadow-cljs.edn
{:deps true
 :builds {:app {:target :esm
                :output-dir "public/js"
                :asset-path "/js"
                :js-options {:js-provider :import}
                :modules {:main {:entries [app.core]}}
                :compiler-options {:solid/render :dom}
                :dev {:compiler-options {:solid/refresh true}}}}}
```

The page loads `/js/main.js` with `<script type="module">`. Run `shadow-cljs watch app` beside
`vite`. Stop Vite from reloading the page when shadow-cljs writes its output, so that
shadow-cljs's hot reload keeps state: either ignore the output dir in Vite's watcher
(`server: {watch: {ignored: ['**/js/**']}}`) or set `server: {hmr: false}`. Pre-bundle the
Solid entries the app imports so the page loads one copy of `solid-js`:

```js
// vite.config.mjs
export default defineConfig({
  root: 'public',
  server: { hmr: false },
  optimizeDeps: { include: ['solid-js', 'solid-js/refresh', '@solidjs/h', '@solidjs/web'] },
});
```

## Clojurific

Clojurific compiles ClojureScript inside Vite 8 and reads deps.edn. Use
`cljf({outputDir: 'target/cljs'})` in vite.config.mjs and load `/src/app/core.cljs` from
index.html. It compiles each namespace once for browser and server, so it cannot use
`:solid/render`; `defserverfn` then compiles both sides and picks with `isServer` at runtime.
Set `:solid/refresh` in a `cljs.edn` `:dev` profile.

## Compiler options

| Option | Values | Effect |
|---|---|---|
| `:solid/render` | `:dom`, `:server`, unset | Keep only browser or server output of `$`, and leave server function bodies out of `:dom` builds. Unset compiles both and chooses with `isServer`. |
| `:solid/hydratable` | `true` | Both server and browser builds, for `s/hydrate`. |
| `:solid/refresh` | `true` | Development only: hot reloads keep the state of unchanged components. Render the root once, under `defonce`. |

shadow-cljs does not recompile cached namespaces when these change: delete
`.shadow-cljs/builds` after changing them.

## Editor support and warnings

Import solid-cljs's clj-kondo config once:

```bash
clj-kondo --copy-configs --dependencies --lint "$(clojure -Spath)"
```

The compiler warns about reactive reads outside tracked scopes, writes in component bodies,
async work in tracked scopes, signals used without `@`, and destructured props read once. An
attribute map `{:solid/lint false}` on a `defui` turns its warnings off.

## Testing

`(s/root (fn [dispose] ... (dispose)))` creates a reactive owner outside rendering. Signal
writes are queued, so call `(s/flush)` before asserting on `@sig` or on the DOM. Browser tests
work with Vitest's browser mode or Playwright; server rendering tests run under Node.js with a
build that sets `:solid/render :server`.
