(ns demo.db
  "Stores the shared todos in SQLite and publishes every change, with the number of viewers, to
  the live todo list. Server only."
  (:require ["@solidjs/web/server-functions" :as sf]
            ["node:sqlite" :refer [DatabaseSync]]
            [solid.core :as s]))

(defn- add-timestamps!
  "Adds the timestamp columns to a database created before they existed."
  [^js db]
  (let [columns (set (map #(.-name ^js %) (.all (.prepare db "pragma table_info(todos)"))))]
    (doseq [column ["created_at" "done_at"] :when (not (columns column))]
      (.exec db (str "alter table todos add column " column " integer"))))
  db)

(defonce ^:private db
  (doto (DatabaseSync. (or js/process.env.TODOS_DB "todos.db"))
    (.exec "create table if not exists todos (
              id integer primary key,
              title text not null,
              done integer not null default 0,
              author text not null)")
    add-timestamps!))

(defn- date
  "Returns the epoch milliseconds `ms` as a `js/Date`, or nil for null."
  [ms]
  (some-> ms js/Date.))

(defn- todo [^js row]
  {:id (.-id row) :title (.-title row) :done (= 1 (.-done row)) :author (.-author row)
   :created-at (date (.-created_at row)) :done-at (date (.-done_at row))})

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

(defn add!
  "Adds a todo and returns it."
  [title author]
  (let [row (.get (.prepare db "insert into todos (title, author, created_at) values (?, ?, ?) returning *")
                  title author (js/Date.now))]
    (swap! state assoc :todos (read-todos))
    (todo row)))

(defn toggle! [id]
  (write! "update todos set done = 1 - done,
             done_at = case when done = 0 then ? end
           where id = ?" (js/Date.now) id))

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
