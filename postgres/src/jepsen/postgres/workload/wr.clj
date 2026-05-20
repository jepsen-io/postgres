(ns jepsen.postgres.workload.wr
  "Test for transactional read-write registers"
  (:refer-clojure :exclude [read])
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [dom-top.core :refer [loopr with-retry]]
            [elle.core :as elle]
            [jepsen [checker :as checker]
             [client :as client]
             [core :as jepsen]
             [generator :as gen]
             [random :as rand]
             [util :as util]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.tests.cycle.wr :as wr]
            [jepsen.postgres [client :as c]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (org.postgresql.util PSQLException)))

(def default-table-count 3)

(defn table-name
  "Takes an integer and constructs a table name."
  [i]
  (str "txn" i))

(defn table-for
  "What table should we use for the given key?"
  [test k]
  (let [table-count (:table-count test default-table-count)]
    (table-name (mod (hash k) table-count))))

(defn write-on-conflict!
  "Sets k to v using INSERT ... ON CONFLICT"
  [test conn table k v]
  (j/execute! conn
              [(str "INSERT INTO " table " AS t"
                    " (id, sk, val) VALUES (?, ?, ?)"
                    " ON CONFLICT (id) DO UPDATE SET"
                    " val = ? WHERE "
                    (case (rand/nth (:key-types test))
                      :primary   "t.id"
                      :secondary "t.sk")
                    " = ?")
               k k v v k]))

(defn write-update!
  "Sets k to v by using an UPDATE statement. Returns true if written, otherwise
  false, so you can fall back to an INSERT."
  [test conn table k v]
  (-> conn
            (j/execute-one! [(str "UPDATE " table " SET val = ? WHERE "
                                  (case (rand/nth (:key-types test))
                                    :primary "id"
                                    :secondary "sk")
                                  " = ?") v k])
            :next.jdbc/update-count
            pos?))

(defn write-insert!
  "Sets k to v using an INSERT statement. Returns true if written, otherwise
  false, so you can fall back to an UPDATE."
  [test conn txn? table k v]
  ; If the insert conflicts, it'd be nice if we didn't have to throw away the
  ; whole transaction. We'll use a savepoint to do that--but some DBs don't
  ; support savepoints, so we make it optional.
  (let [savepoint? (and txn? (:savepoints test))]
    (try
      (when savepoint?
        (j/execute! conn ["SAVEPOINT upsert"]))
      (j/execute! conn
                  [(str "INSERT INTO " table " (id, sk, val) VALUES (?, ?, ?)")
                   k k v])
      (when savepoint?
        (j/execute! conn ["RELEASE SAVEPOINT upsert"]))
      true
      (catch PSQLException e
        (if (re-find #"duplicate key value" (.getMessage e))
          (do (cond ; Fine--we failed but there's no txn that'll break
                    (not txn?)
                    nil

                    ; We're in a txn, but have a savepoint; roll back.
                    savepoint?
                    (j/execute! conn ["ROLLBACK TO SAVEPOINT upsert"])

                    ; No savepoint; gotta explode.
                    true
                    (throw e))
              false)
          (throw e))))))

(defn write!
  "Sets k to v, returning v."
  [test conn txn? table k v]
  (case (rand/nth (:upsert-types test))
    ; Try an update, and if that fails, fall back to an insert, and if THAT
    ; fails we probably conflicted, so try an update again.
    :update-insert-update
    (or (write-update! test conn table k v)
        (write-insert! test conn txn? table k v)
        (write-update! test conn table k v)
        (throw+ {:type ::update-insert-update-failed
                 :key k
                 :value v}))

    :on-conflict
    (write-on-conflict! test conn table k v))
  v)

(defn read
  "Reads the value of key k."
  [test conn table k]
  (-> (j/execute! conn
                  [(str "SELECT (val) FROM " table " WHERE "
                        (case (rand/nth (:key-types test))
                          :primary   "id"
                          :secondary "sk")
                        " = ?")
                   k]
                  {:builder-fn rs/as-unqualified-lower-maps})
      first
      :val))

(defn mop!
  "Executes a transactional micro-op on a connection. Returns the completed
  micro-op."
  [test conn txn? [f k v]]
  (let [table (table-for test k)]
    (Thread/sleep (rand/zipf (:mop-delay test)))
    [f k (case f
           :r (read test conn table k)
           :w (write! test conn txn? table k v))]))

; initialized? is an atom which we set when we first use the connection--we set
; up initial isolation levels, logging info, etc. This has to be stateful
; because we don't necessarily know what process is going to use the connection
; at open! time.
(defrecord Client [node conn initialized?]
  client/Client
  (open! [this test node]
    (let [c (c/open test node)]
      (assoc this
             :node          node
             :conn          c
             :initialized?  (atom false))))

  (setup! [_ test]
    ; Secondaries may not be writable; always do writes on the primary node.
    (when (= node (jepsen/primary test))
      (dotimes [i (:table-count test default-table-count)]
        (with-retry [conn  conn
                     tries 10]
          (j/execute! conn
                      [(str "create table if not exists " (table-name i)
                            " (id int not null primary key,
                               sk int not null,
                               val integer)")])
          (catch org.postgresql.util.PSQLException e
            (condp re-find (.getMessage e)
              #"duplicate key value violates unique constraint"
              :dup

              #"An I/O error occurred|connection has been closed"
              (do (when (zero? tries)
                    (throw e))
                  (info "Retrying IO error")
                  (Thread/sleep 1000)
                  (c/close! conn)
                  (retry (c/await-open node)
                         (dec tries)))

              (throw e))))
        ; Make sure we start fresh--in case we're using an existing postgres
        ; cluster and the DB automation isn't wiping the state for us.
        (j/execute! conn [(str "delete from " (table-name i))]))))

  (invoke! [_ test op]
    ; One-time connection setup
    (when (compare-and-set! initialized? false true)
      (j/execute! conn [(str "set application_name = 'jepsen process "
                        (:process op) "'")]))

    (c/with-errors op
      (let [txn       (:value op)
            use-txn?  (rand/nth [true (< 1 (count txn))])
            txn'      (if use-txn?
                        (c/with-txn test [conn conn
                                          {:isolation (:isolation test)}]
                          (mapv (partial mop! test conn true) txn))
                          (mapv (partial mop! test conn false) txn))]
        (assoc op :type :ok, :value txn'))))

  (teardown! [_ test])

  (close! [this test]
    (c/close! conn)))

(defn process->node
  "Converts a process back to a node ID."
  [test process]
  (nth (:nodes test) (mod process (count (:nodes test)))))

(defn read-only
  "Converts writes to reads."
  [op]
  (loopr [txn' []]
         [[f k v :as mop] (:value op)]
         (recur (conj txn' (case f
                             :r mop
                             [:r k nil])))
         (assoc op :f :read, :value txn')))

(defrecord ROGen [gen ro-nodes]
  gen/Generator
  (update [this test ctx event]
    (let [gen' (gen/update gen test ctx event)]
      (if (= [:read-only] (:error event))
        ; Flag this node as read-only
        (let [node (process->node test (:process event))]
          (ROGen. gen' (conj ro-nodes node)))
        (ROGen. gen' ro-nodes))))

  (op [this test ctx]
    (when-let [[op gen'] (gen/op gen test ctx)]
      (if (= :pending op)
        [:pending this]
        (let [node (process->node test (:process op))]
          (if (contains? ro-nodes node)
            (let [op (read-only op)
                  ; Small chance of this node going back to normal
                  ro-nodes' (if (rand/bool 0.001)
                              (disj ro-nodes node)
                              ro-nodes)]
              [op (ROGen. gen' ro-nodes')])
            ; Pass through
            [op (ROGen. gen' ro-nodes)]))))))

(defn ro-gen
  "Generator that detects read-only errors and flips to emitting read-only
  transactions on that node. Nodes fall out of the read-only pool randomly over
  time."
  [gen]
  (ROGen. gen #{}))

(defn workload
  "A list append workload."
  [opts]
  (-> (wr/test (assoc (select-keys opts [:key-count
                                         :key-dist
                                         :max-txn-length
                                         :max-writes-per-key
                                         :wfr-keys?
                                         :linearizable-keys?
                                         :sequential-keys?])
                      :min-txn-length 1
                      :consistency-models [(:expected-consistency-model opts)]))
      (assoc :client (Client. nil nil nil))
      (update :generator ro-gen)))
