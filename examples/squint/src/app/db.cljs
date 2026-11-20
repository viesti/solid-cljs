(ns app.db
  "Stores the shared todos in SQLite and publishes every change, with the number of viewers, to
  the live todo list. Server only: app.todos imports it inside its server functions."
  (:require ["node:sqlite" :refer [DatabaseSync]]
            ["@solidjs/web" :refer [getRequestEvent]]
            ["@solidjs/web/server-functions" :refer [getServerFunctionInvocation]]))

(def db
  (doto (DatabaseSync. (or js/process.env.TODOS_DB "todos.db"))
    (.exec "create table if not exists todos (
              id integer primary key,
              title text not null,
              done integer not null default 0,
              author text not null)")))

(defn- read-todos []
  (mapv (fn [row] {:id (.-id row) :title (.-title row) :done (= 1 (.-done row)) :author (.-author row)})
        (.all (.prepare db "select * from todos order by id"))))

(def state (atom {:todos (read-todos) :viewers 0}))

(def listeners (atom #{}))

(add-watch state :publish (fn [_ _ _ value] (doseq [f @listeners] (f value))))

(defn subscribe
  "Sends the current todos and viewer count to `next`, and again after every change, until the
  returned function is called. A subscribe function for Solid's `from`."
  [next]
  (swap! listeners conj next)
  (next @state)
  #(swap! listeners disj next))

(defn- write! [sql & params]
  (let [statement (.prepare db sql)]
    (.apply (.-run statement) statement (into-array params)))
  (swap! state assoc :todos (read-todos))
  nil)

(defn add! [title author]
  (write! "insert into todos (title, author) values (?, ?)" title author))

(defn toggle! [id]
  (write! "update todos set done = 1 - done where id = ?" id))

(defn rename! [id title]
  (write! "update todos set title = ? where id = ?" title id))

(defn remove! [id]
  (write! "delete from todos where id = ?" id))

(defn track-viewer!
  "Counts the browser making the current live call as a viewer until its connection closes.
  Calls that are not live, such as a page render, are not counted."
  []
  (when (some-> (getServerFunctionInvocation) .-live)
    (let [signal (.. (getRequestEvent) -request -signal)]
      (swap! state update :viewers inc)
      (.addEventListener signal "abort" #(swap! state update :viewers dec) {:once true}))))
