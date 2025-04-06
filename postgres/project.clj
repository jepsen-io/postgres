(defproject io.jepsen/postgres "0.1.1"
  :description "Jepsen tests for PostgreSQL."
  :url "https://github.com/jepsen-io/postgres"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [jepsen "0.3.8"]
                 [seancorfield/next.jdbc "1.2.659"]
                 [org.postgresql/postgresql "42.7.5"]
                 [cheshire "5.13.0"]
                 [clj-wallhack "1.0.1"]]
  :main jepsen.postgres.cli
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"]
  :repl-options {:init-ns jepsen.postgres})
