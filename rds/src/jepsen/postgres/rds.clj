(ns jepsen.postgres.rds
  "Constructs tests, handles CLI arguments, etc."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [edn :as edn]
                     [pprint :refer [pprint]]
                     [string :as str]]
            [clojure.java [io :as io]]
            [jepsen [cli :as cli]
                    [checker :as checker]
                    [db :as jdb]
                    [generator :as gen]
                    [os :as os]
                    [rds :as rds]
                    [tests :as tests]
                    [util :as util]]
            [jepsen.os.debian :as debian]
            [jepsen.postgres.cli :as p]))

(def all-nemeses
  "Combinations of nemeses for tests"
  [[]])

(def special-nemeses
  "A map of special nemesis names to collections of faults"
  {:none []
   :all  []})

(defn parse-nemesis-spec
  "Takes a comma-separated nemesis string and returns a collection of keyword
  faults."
  [spec]
  (->> (str/split spec #",")
       (map keyword)
       (mapcat #(get special-nemeses % [%]))))

(def rds-file
  "Where do we store RDS cluster info?"
  "rds.edn")

(defn postgres-rds-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (let [r (edn/read-string (slurp rds-file))
        _ (assert r "No rds.edn found; try running `lein run rds-create`.")
        opts (assoc opts
                    ; We're providing postgres, which disables the OS, DB, and
                    ; nemesis
                    :existing-postgres true
                    ; SSL is mandatory
                    :postgres-sslmode "require"
                    ; Nodes are drawn from primary and reader endpoints
                    :nodes [(:endpoint r)
                            (:reader-endpoint r)]
                    ; Credentials
                    :postgres-port (:port r)
                    :postgres-user (:master-username r)
                    :postgres-password (:master-user-password r))]
    (p/postgres-test opts)))

(def cli-opts
  "Additional CLI options. These are drawn mainly from
  jepsen.postgres.cli/opts."
  [["-i" "--isolation LEVEL" "What level of isolation we should set: serializable, repeatable-read, etc."
    :default :serializable
    :parse-fn keyword
    :validate [#{:read-uncommitted
                 :read-committed
                 :repeatable-read
                 :serializable}
               "Should be one of read-uncommitted, read-committed, repeatable-read, or serializable"]]

   [nil "--expected-consistency-model MODEL" "What level of isolation do we *expect* to observe? Defaults to the same as --isolation."
    :default nil
    :parse-fn keyword]

   [nil "--key-count NUM" "Number of keys in active rotation."
    :default  10
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--key-dist DISTRIBUTION" "Key distribution pattern for workload generation."
    :default :exponential
    :parse-fn keyword
    :validate [#{:uniform :exponential}
               "Should be one of uniform or exponential"]]

   [nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
     :parse-fn parse-nemesis-spec
     :validate [(partial every? #{})
                "Faults are not supported yet."]]

   [nil "--max-txn-length NUM" "Maximum number of operations in a transaction."
    :default  4
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--max-writes-per-key NUM" "Maximum number of writes to any given key."
    :default  64
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--nemesis-interval SECS" "Roughly how long between nemesis operations."
    :default 5
    :parse-fn read-string
    :validate [pos? "Must be a positive number."]]

   [nil "--on-conflict" "If set, uses an ON CONFLICT clause to upsert rows."]

   [nil "--prepare-threshold INT" "Passes a prepareThreshold option to the JDBC spec."
    :parse-fn parse-long]

   ["-r" "--rate HZ" "Approximate request rate, in hz"
    :default 100
    :parse-fn read-string
    :validate [pos? "Must be a positive number."]]

   ["-w" "--workload NAME" "What workload should we run?"
    :default :append
    :parse-fn keyword
    :validate [p/workloads (cli/one-of p/workloads)]]
   ])

(defn all-tests
  "Turns CLI options into a sequence of tests."
  [test-fn cli]
  (let [nemeses   (if-let [n (:nemesis cli)] [n]  all-nemeses)
        workloads (if-let [w (:workload cli)] [w]
                    (if (:only-workloads-expected-to-pass cli)
                      p/workloads-expected-to-pass
                      p/all-workloads))]
    (for [n nemeses, w workloads, i (range (:test-count cli))]
      (test-fn (assoc cli
                      :nemesis   n
                      :workload  w)))))

(def rds-setup-cmd
  "A command which creates an RDS cluster."
  {:opt-spec
   [["-s" "--security-group ID" "The ID of a security group you'd like to associate with this cluster."]
    ["-v" "--version VERSION" "What version of Postgres should we request?"
     :default "17.4"]]
   :usage     "Creates a fresh RDS cluster, writing its details to ./rds.edn"
   :opt-fn    identity
   :run (fn run [{:keys [options]}]
          (let [c (rds/create-postgres!
                    {:engine-version    (:version options)
                     :security-group-id (:security-group options)})]
            (pprint c)
            (spit rds-file (with-out-str (pprint c)))
            (info "RDS cluster ready.")))})

(def rds-teardown-cmd
  "A command which tears down all RDS clusters."
  {:opt-spec  []
   :usage     "Tears down all RDS clusters. Yes, all. ALL. DANGER."
   :opt-fn    identity
   :run (fn run [{:keys [options]}]
          (.delete (io/file rds-file))
          (rds/teardown!))})

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  postgres-rds-test
                                         :opt-spec cli-opts
                                         :opt-fn   p/opt-fn})
                   (cli/test-all-cmd {:tests-fn (partial all-tests
                                                         postgres-rds-test)
                                      :opt-spec cli-opts
                                      :opt-fn   p/opt-fn})
                   (cli/serve-cmd)
                   {"rds-setup"    rds-setup-cmd
                    "rds-teardown" rds-teardown-cmd})
            args))
