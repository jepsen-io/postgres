# jepsen.postgres.rds

Jepsen tests for Postgres RDS. This is a huge pile of hacks and will probably
require some aggressive sanding/debugging in your environment. I'm so sorry,
you deserve better, but I'm just drowning in backlogged tasks.

## Quickstart

Create an RDS cluster. This takes about 15 minutes, so we make it a separate
phase from normal setup/teardown. Replace this security group with whatever
security group you'd like to associate with your # cluster. This security group
should let your control node (i.e. the computer where you're running this test)
talk to it.

```sh
lein run rds-setup --security-group sg-03948b38d0afdd49a
```

This will spit out a file to `rds.edn` which has the details of how to connect
to your cluster. `lein run test` will use this file to connect to the right
servers.

```sh
lein run test \
  --isolation repeatable-read \
  --expected-consistency-model snapshot-isolation \
  --concurrency 10n \
  --rate 10000
```

When you're done, you can tear down all <b>(DANGER! YES THIS MEANS ALL,
INCLUDING CLUSTERS THIS TOOL DID NOT CREATE)</b> RDS clusters and associated
resources with

```sh
lein run rds-teardown
```

## License

Copyright © Jepsen, LLC

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
