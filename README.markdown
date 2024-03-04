# Jepsen Postgres tests

This repo includes tests for several related systems that expose a Postgres
API. The core tests for single-node Postgres are in `postgres/`. Tests for AWS
RDS Postgres clusters are in `rds/`.

We package these separately because some Postgres-based tests pull in
conflicting dependencies. Stolon, for instance, uses jetcd, which uses a bunch
of Jetty deps. RDS needs the AWS API, which also uses Jetty, but incompatible
versions.

This also makes it possible to pull in the Postgres tests as a library when
testing other Postgres-based systems, like Cockroach.
