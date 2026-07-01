(ns jepsen.postgres.db
  "Database setup and automation."
  (:require [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c :refer [|]]
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
  "/var/log/postgresql/postgresql-18-main.log")

(defn install-pg!
  "Installs postgresql"
  [test node]
  (c/su
    (when-not (debian/installed? :postgresql-18)
      ; Add repo
      (debian/install ["postgresql-common"])
      (info "Adding Postgres apt repos")
      (c/exec :echo "" | "/usr/share/postgresql-common/pgdg/apt.postgresql.org.sh")
      ; Install
      (debian/install [:postgresql-18 :postgresql-client-18])
      ; Deactivate default install
      (c/exec :service :postgresql :stop)
      (c/exec "update-rc.d" :postgresql :disable))))

(defn db
  "A database which just runs a regular old single-node Postgres instance"
  [opts]
  (reify db/DB
    (setup! [_ test node]
      (install-pg! test node)
      (info "Configuring Postgres")
      (c/su (cu/write-file! (slurp (io/resource "pg_hba.conf"))
                            "/etc/postgresql/18/main/pg_hba.conf")
            (cu/write-file! (slurp (io/resource "jepsen.conf"))
                            "/etc/postgresql/18/main/conf.d/99-jepsen.conf"))

      ; Create fresh data dir
      (c/sudo user
              ; Can't create if it exists--installing will make this dir
              (c/exec :rm :-rf (c/lit "/var/lib/postgresql/18/main/*"))
              (c/exec "/usr/lib/postgresql/18/bin/initdb"
                      :-D "/var/lib/postgresql/18/main"))

      (info "Starting Postgres")
      (c/su (c/exec :service :postgresql :start))
      (cu/await-tcp-port 5432))

    (teardown! [_ test node]
      (info "Tearing down Postgres")
      (c/su (try+ (c/exec :service :postgresql :stop)
                  ; Not installed?
                  (catch [:exit 5] _))
            ; This might not actually work, so we have to kill the processes
            ; too
            (cu/grepkill! "postgres")
            (c/exec :rm :-rf (c/lit "/var/lib/postgresql/18/main/*")))
      (try+ (c/sudo user
                    (c/exec :truncate :-s 0 just-postgres-log-file))
            (catch [:exit 1] _))) ; No user (not installed)

    db/LogFiles
    (log-files [_ test node]
      {just-postgres-log-file "postgresql.log"})

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
        (info "Killing" pattern "-" (cu/grepkill! pattern))))))
