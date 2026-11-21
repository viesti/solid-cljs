# Reactivity, async data and stores

## Signals

```clojure
(let [n (s/signal 0)]          ; atom-like
  @n                           ; read (tracked where read in a tracked scope)
  (reset! n 1) (swap! n inc))  ; write (queued until Solid flushes)
```

Options: `:equals` (default `=`), `:owned-write` (allow writes while a body or computation
runs), `:unobserved` (called when nothing reads it any more), `:name`. `s/atom-signal` follows
a Clojure atom; `s/external-store` follows any subscribe/snapshot pair.

## Memos

```clojure
(def total (s/memo #(reduce + (map :price @items))))
(total)                        ; a memo is a function
```

Options: `:equals`, `:lazy` (compute on first read), `:unobserved`, `:name`. A memo may return a
promise or an async iterable (such as `(s/from subscribe)` or a live server function call); read
it under `s/loading`. `s/refresh` recomputes an async memo, `s/latest` reads including writes in
flight, `s/resolve` returns a promise of the first settled value, `s/affects` declares that work
in flight will change a memo.

## Effects

```clojure
(s/effect (fn [] @id)                      ; compute: tracked reads
          (fn [id] (log id) #(cleanup)))   ; apply: side effects, may return a cleanup
(s/effect compute apply {:error (fn [err _] (show-error err)) :defer true})
(s/render-effect compute apply)            ; before the DOM updates
```

The one-function form `(s/effect f)` still works but uses Solid's deprecated
`createTrackedEffect`; prefer two functions. `s/on-settled` (alias `s/on-mount`) runs after the
first render and may return a cleanup; `s/on-cleanup` registers a cleanup with the current
owner.

## Solid 2 rules

- Writes are queued: `@sig` returns the old value until the next microtask or `(s/flush)`.
  Consecutive `swap!`s compose. `s/batch` applies queued writes before returning.
- Writing a signal while a component body or computation runs throws in development builds.
- `s/computed` and `s/resource` are gone: use `s/memo` (with a promise for async).
- `s/untrack` reads without tracking; `s/get-owner` and `s/run-with-owner` carry the owner into
  async callbacks.

## Async data

```clojure
(defui profile-page [{:keys [id]}]
  (let [user (s/memo #(api/fetch-user id))]          ; a promise
    (s/loading ($ :p "Loading...")
      ($ :h1 {:class {:stale (s/pending? (user))}} (:name (user))))))
```

`s/from` turns a subscribe function `(fn [next error complete] unsubscribe)`, an observable or an
async iterable into a source: `(s/memo #(s/from (fn [next _ _] (feed/subscribe next))))`.
`(s/until expr {:timeout ms})` returns a promise of the expression's value once truthy.

## Actions and optimistic updates

```clojure
(let [todos (s/memo #(api/fetch-todos))
      shown (s/optimistic #(todos))                   ; shows writes at once, reverts on settle
      add (s/action [todo]
            (swap! shown conj todo)
            (s/yield (api/add-todo todo))             ; waits while the transition holds
            (s/refresh todos))]                       ; refresh the source of truth before it ends
  ...)
```

`s/action` rewrites its body into generator steps. `s/yield` works in `let` bindings, `do`,
`if`, `when`, `if-not`, `when-not` and function-call arguments, and `(s/yield [x expr])` binds
`x` for the forms after it. `s/yield` inside `and`, `loop`, `try` or a nested `fn` is a compile
error. An action returns a promise of its result. `s/optimistic-store` is the store version.

## Stores

Stores hold JavaScript data; maps and vectors written into them become objects and arrays, and
reads are tracked per property.

```clojure
(let [[st set-st] (s/store {:items [{:id 1 :title "Docs" :done? false}]})]
  (s/for [[t] (s/sget st :items)] ($ :li (.-title t)))
  (s/sset! set-st [:items 0 :done?] true)
  (s/supdate! set-st [:items 0 :title] str "!")
  (set-st (fn [draft] (.push (.-items draft) #js {:id 2 :title "Ship" :done? false})))
  (set-st (fn [draft] (s/replacement (update (s/store->clj draft) :items #(remove :done? %))))))
```

A draft function's return value is ignored unless it is `(s/replacement v)`. `s/reconcile!`
merges new data by key, `s/snapshot` and `s/store->clj` copy data out, `s/deep` tracks all of a
store in an effect, `s/projection` derives a read-only store keyed by `:key`, `s/mark-raw` keeps a
value out of reactivity, `s/track-keys` tracks key sets.

## Interop

- A promise's `.then` ignores a keyword (not a JS function): `(.then p #(:k %))`.
- Values passed to JavaScript components are not converted; use `clj->js` where needed.
- `s/map-array` maps a reactive list with a function called once per item.
