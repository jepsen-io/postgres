(ns jepsen.postgres.db
  "Database setup and automation."
  (:require [cheshire.core :as json]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [dom-top.core :refer [disorderly
                                  real-pmap]]
            [jepsen [control :as c]
                    [core :as jepsen]
                    [db :as db]
                    [util :as util :refer [meh random-nonempty-subset]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian]
            [jepsen.postgres [client :as sc]]
            [slingshot.slingshot :refer [try+ throw+]]))

(def user
  "The OS user which will run postgres."
  "postgres")

(def just-postgres-log-file
  "/var/log/postgresql/postgresql-12-main.log")

(defn install-pg!
  "Installs postgresql"
  [test node]
  (c/su
    ; Install apt key
    (c/exec :wget :--quiet :-O :- "https://www.postgresql.org/media/keys/ACCC4CF8.asc" c/| :apt-key :add :-)
    ; Add repo
    (debian/install [:lsb-release])
    (let [release (c/exec :lsb_release :-cs)]
      (debian/add-repo! "postgresql"
                        (str "deb http://apt.postgresql.org/pub/repos/apt/ "
                             release "-pgdg main")))
    ; Install
    (debian/install [:postgresql-12 :postgresql-client-12])
    ; Deactivate default install
    (c/exec :service :postgresql :stop)
    (c/exec "update-rc.d" :postgresql :disable)))

(defn db
  "A database which just runs a regular old single-node Postgres instance"
  [opts]
  (let [tcpdump (db/tcpdump {:ports [5432]
                             ; Haaack, hardcoded for my particular cluster
                             ; control node
                             :filter "host 192.168.122.1"})]
    (reify db/DB
      (setup! [_ test node]
        (db/setup! tcpdump test node)
        (install-pg! test node)
        (c/su (c/exec :echo (slurp (io/resource "pg_hba.conf"))
                      :> "/etc/postgresql/12/main/pg_hba.conf")
              (c/exec :echo (slurp (io/resource "postgresql.conf"))
                      :> "/etc/postgresql/12/main/postgresql.conf"))

        ; Create fresh data dir
        (c/sudo user
                ; Can't create if it exists--installing will make this dir
                (c/exec :rm :-rf (c/lit "/var/lib/postgresql/12/main/*"))
                (c/exec "/usr/lib/postgresql/12/bin/initdb"
                        :-D "/var/lib/postgresql/12/main"))

        (c/su (c/exec :service :postgresql :start)))

      (teardown! [_ test node]
        (c/su (try+ (c/exec :service :postgresql :stop)
                    ; Not installed?
                    (catch [:exit 5] _))
              ; This might not actually work, so we have to kill the processes
              ; too
              (cu/grepkill! "postgres")
              (c/exec :rm :-rf (c/lit "/var/lib/postgresql/12/main/*")))
        (try+ (c/sudo user
                      (c/exec :truncate :-s 0 just-postgres-log-file))
              (catch [:exit 1] _)) ; No user (not installed)
        (db/teardown! tcpdump test node))

      db/LogFiles
      (log-files [_ test node]
        (concat (db/log-files tcpdump test node)
                [just-postgres-log-file]))

      db/Primary
      (setup-primary! [db test node])
      (primaries [db test]
        ; Everyone's a winner! Really, there should only be one node here,
        ; so... it's kinda trivial.
        (:nodes test))

      db/Process
      (start! [db test node]
        (c/su (c/exec :service :postgresql :restart)))

      (kill! [db test node]
        (doseq [pattern (shuffle
                          ["postgres -D" ; Main process
                           "main: checkpointer"
                           "main: background writer"
                           "main: walwriter"
                           "main: autovacuum launcher"])]
          (Thread/sleep (rand-int 100))
          (info "Killing" pattern "-" (cu/grepkill! pattern)))))))
