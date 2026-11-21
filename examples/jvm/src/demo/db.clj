(ns demo.db
  "Stores the shared todos in SQLite and tells subscribers about every change."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.util Date)))

(defonce ^:private ds
  (jdbc/get-datasource {:dbtype "sqlite" :dbname (or (System/getenv "TODOS_DB") "todos.db")}))

(defn migrate!
  "Creates the table, the same one the other examples use."
  []
  (jdbc/execute! ds ["create table if not exists todos (
                        id integer primary key,
                        title text not null,
                        done integer not null default 0,
                        author text not null,
                        created_at integer,
                        done_at integer)"]))

(defn- date
  "Returns the epoch milliseconds `ms` as a `java.util.Date`, which EDN prints as #inst, or nil."
  [ms]
  (some-> ms long Date.))

(defn- todo [{:keys [id title done author created_at done_at]}]
  {:id id :title title :done (= 1 done) :author author
   :created-at (date created_at) :done-at (date done_at)})

(def ^:private opts {:builder-fn rs/as-unqualified-maps})

(defn todos
  "Returns every todo, oldest first."
  []
  (mapv todo (jdbc/execute! ds ["select * from todos order by id"] opts)))

;; Called with no arguments after every write
(defonce ^:private listeners (atom #{}))

(defn subscribe
  "Calls `f` after every change until the returned function is called."
  [f]
  (swap! listeners conj f)
  #(swap! listeners disj f))

(defn- changed! []
  (doseq [f @listeners] (f)))

(defn- clean [title]
  (let [title (some-> title str/trim)]
    (when (str/blank? title)
      (throw (ex-info "A todo needs a title" {:type ::invalid})))
    title))

(defn add!
  "Adds a todo by `author` and returns it."
  [title author]
  (let [row (jdbc/execute-one! ds ["insert into todos (title, author, created_at) values (?, ?, ?) returning *"
                                   (clean title) author (System/currentTimeMillis)]
                               opts)]
    (changed!)
    (todo row)))

(defn- write! [& sql-params]
  (let [{::jdbc/keys [update-count]} (jdbc/execute-one! ds (vec sql-params))]
    (when (pos? update-count)
      (changed!)
      true)))

(defn toggle!
  "Ticks or unticks the todo `id`. Returns false when there is none."
  [id]
  (write! "update todos set done = 1 - done, done_at = case when done = 0 then ? end where id = ?"
          (System/currentTimeMillis) id))

(defn rename!
  "Renames the todo `id`. Returns false when there is none."
  [id title]
  (write! "update todos set title = ? where id = ?" (clean title) id))

(defn remove!
  "Deletes the todo `id`. Returns false when there is none."
  [id]
  (write! "delete from todos where id = ?" id))
