(defproject jepsen.postgres "0.1.0"
  :description "Jepsen tests for PostgreSQL."
  :url "https://github.com/jepsen-io/postgres"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.3.5"]
                 [seancorfield/next.jdbc "1.2.659"]
                 [org.postgresql/postgresql "42.7.2"]
                 [cheshire "5.12.0"]
                 [clj-wallhack "1.0.1"]]
  :main jepsen.postgres.cli
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"]
  :repl-options {:init-ns jepsen.postgres})
