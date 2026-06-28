(ns jepsen.postgres.client
  "Helper functions for interacting with PostgreSQL clients."
  (:require [clojure.tools.logging :refer [info warn]]
            [dom-top.core :refer [with-retry]]
            [jepsen [client :as client]
                    [sql :as sql]
                    [util :as util]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb]
            [slingshot.slingshot :refer [try+ throw+]]
            [wall.hack :as wh])
  (:import (clojure.lang ExceptionInfo)
           (java.sql Connection)
           (org.postgresql.util PSQLException)))

(defn open
  "Opens a connection to the given node."
  [test node]
  (let [user              (:postgres-user       test)
        password          (:postgres-password   test)
        sslmode           (:postgres-sslmode    test)
        prepare-threshold (:prepare-threshold   test)
        spec  (cond-> {:dbtype    "postgresql"
                       ;:dbname    "jepsen"
                       :host      node
                       :port      (:postgres-port test)
                       :connectTimeout      10
                       :loginTimeout        10
                       :queryTimeout        10
                       :socketTimeout       10
                       :cancelSignalTimeout 10}
                user              (assoc :user              user)
                password          (assoc :password          password)
                sslmode           (assoc :sslmode           sslmode)
                prepare-threshold (assoc :prepareThreshold  prepare-threshold))
        ds    (j/get-datasource spec)
        conn  (j/get-connection ds)]
    conn))

(defn await-open
  "Waits for a connection to node to become available, returning conn. Helpful
  for starting up."
  [node]
  (with-retry [tries 100]
    (info "Waiting for" node "to come online...")
    (let [conn (open node)]
      (try (j/execute-one! conn
                           ["create table if not exists jepsen_await ()"])
           conn
           (catch org.postgresql.util.PSQLException e
             (condp re-find (.getMessage e)
               ; Ah, good, someone else already created the table
               #"duplicate key value violates unique constraint \"pg_type_typname_nsp_index\""
               conn

               (throw e)))))
    (catch org.postgresql.util.PSQLException e
      (when (zero? tries)
        (throw e))

      (Thread/sleep 5000)
      (condp re-find (.getMessage e)
        #"connection attempt failed"
        (retry (dec tries))

        #"Connection to .+ refused"
        (retry (dec tries))

        #"An I/O error occurred"
        (retry (dec tries))

        (throw e)))))

(defn error-fn
  "Takes an Exception and returns an error map for jepsen.sql, or nil if
  unrecognized."
  [^Exception e]
  (let [msg (.getMessage e)]
    (condp identical? (class e)
      PSQLException
      (condp re-find msg
            #"ERROR: cannot execute .+ in a read-only transaction"
            {:type :read-only
             :definite? true}

            #"ERROR: could not serialize access"
            {:type      :could-not-serialize
             :msg       msg
             :definite? true}

            #"ERROR: deadlock detected"
            {:type      :deadlock
             :msg       msg
             :definite? true}

            #"ERROR: duplicate key value"
            {:type      :duplicate-key-value
             :msg       msg
             :definite? true}

            #"An I/O error occurred"
            {:type :io-error}

            #"column not found"
            {:type :column-not-found, :definite? true, :msg msg}

            #"connection has been closed"
            {:type :connection-closed}

            #"Connection attempt timed out"
            {:type :connection-timed-out, :definite? true}

            #"current transaction is aborted"
            {:type :aborted, :definite? true}

            #"table not found"
            {:type :table-not-found, :definite? true, :msg msg}

            #"violates not-null constraint"
            {:type :violates-not-null, :msg m, :definite? true}

            nil)

      ExceptionInfo
      (let [data (ex-data e)]
        (cond (and (:rollback data) (:handling data))
          ; For a rollback, the original exception will be in :handling; that's
          ; what we should use
          (recur (:handling data))

          ; Otherwise, pass up our own maps
          true
          data))

      nil)))
