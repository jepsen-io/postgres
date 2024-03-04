(defproject io.jepsen/postgres.rds "0.1.0-SNAPSHOT"
  :description "Jepsen tests for AWS Postgres RDS."
  :url "https://github.com/jepsen-io/postgres"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [io.jepsen/postgres "0.1.1-SNAPSHOT"
                  :exclusions [; Fights with aws-api
                               org.eclipse.jetty/jetty-http
                               org.eclipse.jetty/jetty-util
                               org.eclipse.jetty/jetty-io
                               ]]
                 [io.jepsen/rds "0.1.0-SNAPSHOT"]]
  :main jepsen.postgres.rds
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"]
  :repl-options {:init-ns jepsen.postgres.rds})
