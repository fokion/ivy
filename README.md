# ivy

ivy is a declarative integration-test runner: a Java port of [venom](https://github.com/ovh/venom).
Test suites are YAML files of test cases and steps; each step runs a connector (shell command, HTTP
request, SQL query...), its result is flattened into `result.*` variables, checked with assertions
such as `result.statuscode ShouldEqual 200`, and can feed variables to later steps.

The suite format is venom's: existing venom suites run unchanged (see [Differences from venom](#differences-from-venom)).

```yaml
name: my suite
vars:
  url: https://example.com
testcases:
- name: get the home page
  steps:
  - type: http
    method: GET
    url: "{{.url}}"
    assertions:
    - result.statuscode ShouldEqual 200
    vars:
      size:
        from: result.headers.Content-Length
- name: use a previous result
  steps:
  - script: echo {{.get-the-home-page.size}}
    assertions:
    - result.systemout ShouldNotBeEmpty
```

## Build

Java 25 and the Gradle wrapper:

```shell
./gradlew build                      # compile and run all tests
./gradlew :ivy-cli:build             # the CLI: ivy-cli/build/quarkus-app/quarkus-run.jar
java -jar ivy-cli/build/quarkus-app/quarkus-run.jar run tests/*.yml
```

The native binary is built by Quarkus with Mandrel 25; the container build needs Docker, not a
local GraalVM:

```shell
./gradlew :ivy-cli:build -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
    -Dquarkus.native.container-build=true
./ivy-cli/build/ivy-cli-0.0.1-runner run tests/*.yml        # Linux binary
docker build -f ivy-cli/src/main/docker/Dockerfile.native -t ivy ivy-cli
docker run --rm -v "$PWD:/work" ivy run tests/*.yml
```

## Usage

```
ivy run [paths...] [flags]     paths are files, directories or globs (** supported); default "."
ivy version
```

| Flag | Environment | `.ivyrc` key | |
|---|---|---|---|
| `--format` | `IVY_FORMAT` | `format` | report format: `xml` (JUnit, default), `json`, `yaml`, `tap` |
| `--output-dir` | `IVY_OUTPUT_DIR` | `output_dir` | where reports and `ivy.log` are written |
| `--html-report` | `IVY_HTML_REPORT` | `html_report` | also write `test_results.html` |
| `--stop-on-failure` | `IVY_STOP_ON_FAILURE` | `stop_on_failure` | skip the remaining test cases of a suite after a failure |
| `-v`, `-vv` | `IVY_VERBOSE` | `verbosity` | step details on the console, INFO / DEBUG in `ivy.log`, step dumps at `-vv` |
| `--var name=value` | `IVY_VAR`, `IVY_VAR_<name>` | `variables` | variables; values are parsed as YAML |
| `--var-from-file f` | `IVY_VAR_FROM_FILE` | `variables_files` | YAML files of variables |
| `--lib-dir` | `IVY_LIB_DIR` | `lib_dir` | directories of user executors, `:`-separated (`./lib` is always read) |
| `--bundles-dir` | `IVY_BUNDLES_DIR` | `bundles_dir` | directory of connector bundles (JVM only) |
| `--connector-server key@host:port` | | `connector_servers` | a connector server |

Flags override `.ivyrc` (read from the current directory, then the home directory), which
overrides the environment. `VENOM_*` variables and `.venomrc` are read too, so venom setups keep
working. The exit code is 0 when every suite passed, 2 otherwise.

## Writing suites

The format, the variables (`venom.testsuite.workdir`, `venom.testcase`...), the template helpers
(`default`, `upper`, `b64enc`, `toJSON`...), the assertions (`ShouldEqual`, `ShouldContainKey`,
`ShouldHappenBefore`... with `Must*` variants and `and`/`or`/`xor`/`not` operators), `range`,
`skip`, `retry`, `retry_if`, `timeout`, `info` and user executors are venom's: its
[documentation](https://github.com/ovh/venom#readme) applies.

Built-in connectors, available in every distribution including the native binary:

| type | | result |
|---|---|---|
| `exec` (default when `script` or `command` is set) | runs a command or a script (`#!` selects the interpreter) | `systemout`, `systemoutjson`, `systemerr`, `code`, `timeseconds` |
| `http` | HTTP/1.1 and HTTP/2 requests, `body`, `bodyfile`, `multipart_form`, TLS client certificates, `resolve`, proxies | `statuscode`, `body`, `bodyjson`, `headers`, `request` |
| `readfile` | reads files or globs | `content`, `contentjson`, `md5sum`, `size`, `modtime`, `mod` |

Other venom executors (kafka, mongo, grpc, redis...) are meant to be added as connector bundles.

## Connectors

Connectors are written against the `ivy-api` module, which has no dependency. The model follows
[OpenICF](https://github.com/OpenIdentityPlatform/OpenICF): an SPI, bundles loaded in isolated
class loaders, and a connector server for processes that cannot load bundles.

```java
@ConnectorClass(type = "greet", configurationClass = GreetConfiguration.class)
public final class GreetConnector implements Connector<GreetConfiguration>, DefaultAssertionsProvider {

    @Override
    public Object run(GreetConfiguration config, StepContext context) {
        return Struct.builder("Result").put("message", "hello " + config.getName()).build();
    }

    @Override
    public List<Object> defaultAssertions() {
        return List.of("result.message ShouldNotBeEmpty");
    }
}

public final class GreetConfiguration implements Configuration {
    private String name = "";

    public String getName() {
        return name;
    }

    @ConfigurationProperty(required = true)
    public void setName(String name) {
        this.name = name;
    }
}
```

- A step's keys are bound to the configuration's annotated setters (case-insensitively), then
  `validate()` is called. A `Struct` named `Result` becomes the `result.*` variables.
- A connector instance lives for one test case: `open` is called before its first step and `close`
  at the end, so it can keep connections in fields.
- Connectors are declared in `META-INF/services/xyz.fokion.ivy.spi.Connector`.

**Bundles.** A bundle is a jar with `Ivy-Bundle-Name`, `Ivy-Bundle-Version` and
`Ivy-Framework-Version` manifest attributes and its dependencies under `lib/`. Each bundle gets
its own class loader that sees only the JDK, the `xyz.fokion.ivy.spi` classes and its own jars, so
bundles can embed conflicting driver versions. Modules under `connectors/` get a `bundle` task;
dependencies to embed go in the `bundled` configuration (see `connectors/sql`).

```shell
./gradlew :connectors:sql:bundle     # connectors/sql/build/bundle/ivy-connector-sql-0.0.1.jar
mkdir -p bundles && cp connectors/sql/build/bundle/*.jar bundles/
java -jar ivy-cli/build/quarkus-app/quarkus-run.jar run --bundles-dir bundles sql.yml
```

**Connector server.** The native binary cannot load bundles; it uses them through
`ivy-connector-server`, a JVM process serving the bundles of a directory over TCP (length-prefixed
JSON messages, a shared key, optional TLS through the `javax.net.ssl.*` properties):

```shell
./gradlew :ivy-connector-server:installDist
IVY_CONNECTOR_KEY=changeit ivy-connector-server/build/install/ivy-connector-server/bin/ivy-connector-server \
    --bundles bundles --port 8759
ivy run --connector-server changeit@localhost:8759 sql.yml
```

Steps run on the server: files they reference (a SQLite database, a SQL file) are resolved on the
server's file system.

Executors are resolved in this order: built-in connectors, user executors, bundles, connector
servers.

The `sql` bundle runs `commands` or a `file` with the SQLite, PostgreSQL and MySQL JDBC drivers. The
`dsn` is a JDBC URL, or a venom (Go driver) DSN that is translated.

## Differences from venom

- No `update` command, no CPU/memory profiles at `-vvv`, no Go plugins (`lib/*.so`): connector
  bundles and servers replace them.
- The log file is `ivy.log`; variables keep their `venom.*` names so suites are unchanged.
- `http`: `unix_sock` is not supported; with `resolve` the request goes to the resolved host with
  the original `Host` header, and certificates are checked against the original host.
- JSON numbers have a single representation, so JSON assertions accept numbers that venom rejects
  as "unexpected type" when they come from variables.
- The YAML report is the JSON report as YAML (venom's YAML report misses most fields), and the TAP
  report has one line per test case.
- Failure messages point at the line of the failing step (venom reports the line of the next step).
- `slug` names (`{{.my-test-case.var}}`) strip accents but do not transliterate other scripts.

## Project layout

| module | |
|---|---|
| `ivy-api` | the connector SPI, no dependencies |
| `ivy-core` | the engine: Go-template interpolation, flattening, assertions, reports, built-in connectors, connector framework; depends on `snakeyaml-engine` only |
| `ivy-cli` | the command line; Quarkus is used only to build the binary (`IvyMain`), `Cli` is plain Java |
| `ivy-connector-server` | serves connector bundles to remote ivy processes |
| `connectors/sql` | the SQL connector bundle |

## Tests

`./gradlew build` runs:

- unit tests ported from venom's Go tests; the template, flattening and assertion behaviours are
  checked against output produced by the original Go code (`ivy-core/src/test/resources/golden`);
- venom's own suites (`ivy-cli/src/test/resources/venom-tests`), run unchanged by the CLI,
  including the ones that check the console output of failing runs;
- the connector framework end to end: the sql bundle loaded locally and through a connector server.

`IVY_NETWORK_TESTS=true ./gradlew :ivy-cli:test` also runs venom's HTTP suites; they need network
access and httpbin on port 9280 (`docker run -d -p 9280:80 kennethreitz/httpbin`).
