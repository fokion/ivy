# Connectors

| Connector | Where | Tested against |
|---|---|---|
| `exec`, `http`, `readfile` | built in | unit and acceptance tests |
| `sql`, `dbfixtures` | `connectors/sql` | PostgreSQL 18, SQLite |
| `kafka` | `connectors/kafka` | Kafka 4.3 (native image), in-process schema registry |
| `redis` | `connectors/redis` | Redis 8 |
| `mongo` | `connectors/mongo` | MongoDB 8.2 |
| `couchbase` | `connectors/couchbase` | Couchbase Server community 8.0 |
| `browser` | `connectors/browser` | Playwright 1.63 server (Chromium) |

Integration tests use Testcontainers: `./gradlew integrationTest` (needs Docker).

A new connector is a `connectors/<name>` module: a `Configuration` bean, a `Connector` annotated with
`@ConnectorClass`, a `META-INF/services/xyz.fokion.ivy.spi.Connector` entry, dependencies in the
`bundled` configuration, and an integration suite run with `SuiteRunner` (from `ivy-core` test fixtures).
