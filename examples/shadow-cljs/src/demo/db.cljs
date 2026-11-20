(ns demo.db
  "Stores the shared todos in SQLite and publishes every change, with the number of viewers, to
  the live todo list. Server only."
  (:require ["@solidjs/web/server-functions" :as sf]
            ["node:sqlite" :refer [DatabaseSync]]
            [solid.core :as s]))

(defonce ^:private db
  (doto (DatabaseSync. (or js/process.env.TODOS_DB "todos.db"))
    (.exec "create table if not exists todos (
              id integer primary key,
              title text not null,
              done integer not null default 0,
              author text not null)")))

(defn- todo [^js row]
  {:id (.-id row) :title (.-title row) :done (= 1 (.-done row)) :author (.-author row)})

(defn- read-todos []
  (mapv todo (.all (.prepare db "select * from todos order by id"))))

(defonce ^:private state (atom {:todos (read-todos) :viewers 0}))

(defonce ^:private listeners (atom #{}))

(add-watch state ::publish (fn [_ _ _ value] (doseq [f @listeners] (f value))))

(defn subscribe
  "Sends the current todos and viewer count to `next`, and again after every change, until the
  returned function is called. A subscribe function for `s/from`."
  [next _ _]
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
  (when (some-> ^js (sf/getServerFunctionInvocation) .-live)
    (let [^js signal (.. ^js (s/request-event) -request -signal)]
      (swap! state update :viewers inc)
      (.addEventListener signal "abort" #(swap! state update :viewers dec) #js {:once true}))))
