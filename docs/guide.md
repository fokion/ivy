# Getting started with ivy

This guide takes you from nothing to a suite that calls a service, waits for it, hides a secret and
runs in CI. Every suite in it is in [`examples/getting-started`](../examples/getting-started) and
runs as written. The reference pages are [expressions](expressions.md), [secrets](secrets.md),
[connectors](connectors.md) and [MCP](mcp.md).

1. [What ivy does](#1-what-ivy-does)
2. [Install](#2-install)
3. [Your first suite](#3-your-first-suite)
4. [Variables and `set`](#4-variables-and-set)
5. [HTTP, and waiting for a service](#5-http-and-waiting-for-a-service)
6. [Loops, retries and files](#6-loops-retries-and-files)
7. [Secrets](#7-secrets)
8. [Reusable steps](#8-reusable-steps)
9. [Gherkin](#9-gherkin)
10. [Other systems: databases, Kafka, a browser](#10-other-systems-databases-kafka-a-browser)
11. [Reports, CI and parallel runs](#11-reports-ci-and-parallel-runs)
12. [Let Claude write suites](#12-let-claude-write-suites)
13. [When something goes wrong](#13-when-something-goes-wrong)

## 1. What ivy does

An ivy suite is a YAML file. It holds **test cases**; a test case holds **steps**; a step runs one
**connector** (a shell command, an HTTP request, a SQL query...) and checks its **result** with
**assertions**:

```
suite ─┬─ test case ─┬─ step: type + settings ──► connector ──► result ──► assertions
       │             └─ step ...                                           set / info
       └─ test case ...
```

Test cases of a suite run one after the other, in order, and a step can keep values for the steps
after it. Nothing needs compiling: you write the file and run it.

## 2. Install

Pick one (details in the [README](../README.md#install)):

```shell
brew install fokion/tap/ivy                                    # native binary
docker run --rm -v "$PWD:/work" ghcr.io/fokion/ivy run tests/  # Docker, every connector but the browser
```

or build it yourself with Java 25:

```shell
./gradlew build
alias ivy='java --enable-native-access=ALL-UNNAMED -jar ivy-cli/build/quarkus-app/quarkus-run.jar'
ivy version
```

The native binary has the built-in steps `exec`, `http` and `readfile`. The others (SQL, Kafka, Redis,
MongoDB, Couchbase, browser) are bundles for the JVM; see [section 10](#10-other-systems-databases-kafka-a-browser).

## 3. Your first suite

[`01-hello.yml`](../examples/getting-started/01-hello.yml):

```yaml
name: hello
testcases:
- name: say hello
  steps:
  - script: echo "hello ivy"
    assertions:
    - result.exitCode == 0
    - result.stdout == "hello ivy"
    info: the command printed "${result.stdout}"
```

```shell
ivy run examples/getting-started/01-hello.yml
```

```
 • hello (examples/getting-started/01-hello.yml)
 	• say-hello PASS
```

What you used:

- `script:` is the short form of an `exec` step (`type: exec` with a `script`). `command: [prog, arg]`
  runs a program without a shell.
- `result` is what the step produced. For `exec`: `stdout`, `stderr`, `exitCode`, `json` (stdout parsed
  as JSON), `durationMs`, `error`. Each connector documents its own fields; `ivy validate` warns when
  an assertion reads a field that does not exist.
- An assertion is an **expression**, a subset of JavaScript with extras such as `contains` and
  `matches`: `result.json.orders.some(o => o.status == "paid")`. A step passes when all its
  assertions are true. [Expression reference](expressions.md).
- `info` prints a line when the step ran. `${...}` inside any string is a **template**.

Make it fail to see what ivy tells you. For `result.json.orders.length > 2` against one order:

```
 	• wrong-number FAIL
 		• exec
 		  Testcase "wrong number", step #1 (bad.yml:7): assertion failed: result.json.orders.length > 2
 		    result.json.orders.length = 1
```

The failure shows the file and line, and the value of every path the assertion read. The exit code of
`ivy run` is 0 when everything passed and 2 otherwise.

Check a suite without running it, which is cheap enough for an editor hook or a CI step:

```shell
ivy validate examples/getting-started
# valid: 6 suite(s), 20 test case(s)
```

`validate` catches bad YAML, unknown variables, expressions that do not parse, misspelled step keys
(`methd`) and reads of result fields that do not exist.

## 4. Variables and `set`

[`02-variables.yml`](../examples/getting-started/02-variables.yml) shows where values come from and
how they move:

| You write | Meaning |
|---|---|
| `vars:` on the suite or a test case | values; may be nested (`user.langs[0]`) |
| `--var name=value`, `--var-from-file f.yml`, `IVY_VAR_name=value` | override a suite variable from outside |
| `set: {token: result.json.token}` | keep a value from a step for the steps after it |
| `cases["test case name"].token` | a value set by an earlier test case |
| `env.HOME` | an environment variable |
| `ivy.suite.workdir`, `ivy.step.number`... | facts about the run |

In a template write `${greeting} ${user.name}`; in an assertion, `if`, `until` or `set` the text is
already an expression, so write `user.name` with no `${}`. A template that is only `${...}` keeps its
type, so `range: ${items}` is a list.

Three behaviours worth knowing:

- A step with no `type` runs nothing and just checks: `- assertions: [line contains "ada"]`.
- `must:` stops the test case when it fails: `- must: result.stdout == "ready"`. Ordinary assertions
  all run and are all reported.
- `if:` on a test case or step skips it when the expression is false. A skipped test case shows `SKIP`.

## 5. HTTP, and waiting for a service

[`03-local-http.yml`](../examples/getting-started/03-local-http.yml) starts a small web server itself
(so you need no network), calls it and stops it.

```yaml
- type: http
  method: GET
  url: ${base}/api/items.json
  headers: {Accept: application/json}
  assertions:
  - result.status == 200
  - result.body.items.length == 3       # JSON bodies are parsed
  - result.headers["content-type"] contains "json"
  set:
    firstId: result.body.items[0].id
```

`http` also takes `body`, `bodyfile`, `query_parameters`, `multipart_form`, `basic_auth_user`,
`basic_auth_password`, `proxy`, `ignore_verify_ssl`, `tls_client_cert`... (the full list is in
`HttpConfiguration.java`, or ask the `list_step_types` tool of [MCP](mcp.md)). The result has `status`,
`headers`, `body`, `bodyText`, `request`, `durationMs`, `error`. A 404 is a normal result, not an
error: assert on `result.status`.

Services take time to start. **`until`** runs a step again until an expression on its result holds,
then checks the assertions:

```yaml
- type: http
  url: ${base}/api/items.json
  until: result.status == 200
  within: 10        # give up after 10 seconds (default 30)
  every: 0.5        # seconds between attempts (default 1)
```

A request that fails to connect counts as "not yet". If time runs out, the step fails and prints the
values the condition read.

## 6. Loops, retries and files

[`04-files-loops-retries.yml`](../examples/getting-started/04-files-loops-retries.yml):

- **`range`** runs one step several times: a list gives `index` and `value`, a map `key` and `value`, a
  number `n` counts `0..n-1`, and a variable name uses its value.
- **`retry: 5`** with `delay: 0.2` runs a step again while its assertions fail (a flaky call).
  `retryIf: <expression>` limits when to retry. Use `retry` for flakiness and `until` for waiting;
  a step uses one or the other.
- **`readfile`** reads a file, a directory or a glob relative to the suite, and parses JSON and YAML into
  `result.json`.
- `timeout`, `delay`, `within` and `every` are in seconds and may be fractional.

## 7. Secrets

[`05-secrets.yml`](../examples/getting-started/05-secrets.yml). Anything listed in `secrets:`, or given
as `IVY_SECRET_name=value` / `--secret-from-file secrets.yml`, is replaced by `__hidden__` in the
console, `ivy.log` and every report format (including its base64, URL and JSON encodings). The step
still sees the real value.

```shell
IVY_SECRET_api_key=abc-123-xyz ivy run examples/getting-started/05-secrets.yml --output-dir out
grep -r abc-123-xyz out || echo "not in any report"
```

Prefer the environment or a file to `--secret`: command-line arguments are visible to other users of the
machine. [More on secrets](secrets.md).

## 8. Reusable steps

A **user executor** is a YAML file with `executor`, `input`, `steps` and `output`, found in a `lib/`
directory (next to where you run ivy or next to the suite; `--lib-dir` adds more). Its name becomes a
step type. [`lib/greet.yml`](../examples/getting-started/lib/greet.yml):

```yaml
executor: greet
input:
  name: world
steps:
- script: echo "hello ${input.name}"
  set:
    message: result.stdout
output:
  message: ${message}
```

used in [`06-user-executors.yml`](../examples/getting-started/06-user-executors.yml):

```yaml
- type: greet
  name: ivy                      # becomes input.name
  assertions:
  - result.message == "hello ivy"
```

Executors can call other executors, and Gherkin steps and `range` loops can call them too.
[`examples/reuse`](../examples/reuse) shows the idea with real calls: four small blocks (`api_get`,
`api_post`, and `user_with_posts` and `publish_post` built from them) used by plain suites, a
data-driven loop, a multi-test-case flow and a Gherkin feature.

## 9. Gherkin

`.feature` files run directly. A step in the scenario is matched against definitions in
`*.steps.yml` files found in `steps/` or `lib/`, either next to the feature or in the working
directory. [`features/calculator.feature`](../examples/getting-started/features/calculator.feature)
uses [`features/steps/shell.steps.yml`](../examples/getting-started/features/steps/shell.steps.yml):

```yaml
steps:
  - expression: 'I run {string}'               # Cucumber expression; `match:` takes a regex
    step: { script: "${arg1}" }                # arg1, arg2... are the captures
  - expression: 'the exit code is {int}'
    assertions: [ "result.exitCode == arg1" ]  # no `step:`: checks the step before it
```

```shell
ivy run examples/getting-started/features --tags "not @slow"
```

Scenario outlines, backgrounds, doc strings, data tables and tags work as in Cucumber. A scenario
step with no definition fails and ivy prints a definition to start from; with MCP, `gherkin_steps`
proposes them.

Directories are not searched recursively: `ivy run examples/getting-started` runs the `.yml` suites
of that folder, and `.../features` runs the features.

## 10. Other systems: databases, Kafka, a browser

Each connector has a README with its steps; the table is in [connectors.md](connectors.md).

| Type | What for | Where |
|---|---|---|
| `sql`, `dbfixtures` | queries, migrations and fixtures on PostgreSQL, MySQL, SQLite | [connectors/sql](../connectors/sql/README.md) |
| `kafka` | produce and consume, with Avro and a schema registry | [connectors/kafka](../connectors/kafka/README.md) |
| `redis` | commands | [connectors/redis](../connectors/redis/README.md) |
| `mongo` | fixtures, find, update, aggregate | [connectors/mongo](../connectors/mongo/README.md) |
| `couchbase` | get, upsert, query | [connectors/couchbase](../connectors/couchbase/README.md) |
| `browser` | Playwright: goto, fill, click, screenshot | [connectors/browser](../connectors/browser/README.md) |

On the JVM, build the bundles, collect their jars in one directory and point ivy at it
(`--bundles-dir` takes a directory of bundle jars; the Docker image already has them):

```shell
./gradlew bundle
mkdir -p bundles && cp connectors/*/build/bundle/*.jar bundles/
ivy run tests/ --bundles-dir bundles
```

With the native binary, run `ivy-connector-server` and pass `--connector-server key@host:port`. Runnable
browser suites are in [`examples/browser`](../examples/browser): they need the internet and a browser
(`ivy browser install chromium --bundles-dir bundles`).

A typical database check:

```yaml
- type: dbfixtures
  database: postgres
  dsn: "jdbc:postgresql://localhost/app?user=app&password=${db_password}"
  folder: fixtures                  # one <table>.yml per table
- type: sql
  driver: postgres
  dsn: "jdbc:postgresql://localhost/app?user=app&password=${db_password}"
  commands: ["SELECT count(*) AS n FROM players"]
  assertions:
  - result.queries[0].rows[0].n == 2
```

## 11. Reports, CI and parallel runs

```shell
ivy run tests/ --output-dir out --html-report      # JUnit XML (the default) plus out/test_results.html
ivy run tests/ --format json --output-dir out      # xml, json, yaml, tap or cucumber
ivy run tests/ --parallel 4 --stop-on-failure
ivy run features/ --tags "@smoke and not @slow"
ivy run tests/ -vv                                 # DEBUG level in ivy.log, and step dumps
```

- `--parallel n` runs up to `n` **suites** at once; test cases of a suite stay in order. A suite that
  must run alone (resetting a database) says `parallel: false`.
- Put defaults in `.ivyrc` (current or home directory) or `IVY_*` variables such as `IVY_PARALLEL=4`.
  Order of precedence: environment, `.ivyrc`, flags.
- In CI run `ivy validate tests/` first, then `ivy run tests/ --output-dir out`, and publish `out/`. The
  JUnit XML is understood by most CI systems.

## 12. Let Claude write suites

`ivy mcp` is an MCP server. Add it to Claude Code and describe what to test in plain words; Claude lists
the step types, writes a valid suite, runs it and fixes it from the values it sees:

```shell
claude mcp add ivy -- ivy mcp --workspace .
```

[docs/mcp.md](mcp.md) follows a whole session. Secrets stay hidden in every answer.

## 13. When something goes wrong

| Symptom | Cause and fix |
|---|---|
| `mapping values are not allowed here` | A YAML plain scalar holds `: `. Quote it, or use a block: `script: "echo '{\"a\": 1}'"`, or `script: \|` followed by an indented line. |
| A YAML error on an assertion starting with `!` or holding `: ` | Quote it: `- "!(result.stdout == 6)"`. |
| `missing variables [x]` | Declare `x` under `vars:` (an empty default is fine) or pass `--var x=...`. |
| `result.statuscode is not a field of exec results` | A validation warning: the field is misspelled. The message lists the real ones. |
| A relative file path in a script does not work | Scripts run in the folder you started ivy from; use `${ivy.suite.workdir}/file` for paths next to the suite. `readfile` paths are relative to the suite. |
| `undefined step` in a feature | No definition matches. Put a `*.steps.yml` in a `steps/` or `lib/` folder next to the feature (or in the working directory). |
| `the browser connector is a bundle` | Add `--bundles-dir` (or `IVY_BUNDLES_DIR`). |
| A value shows as `__hidden__` | It is a secret. While debugging, give it with a plain `--var` instead. |
| `until ... was still false after Ns` | The condition never held; the message prints the values it read. Check the expression with `evaluate_expression` (MCP) or raise `within`. |

Next: read the [expression reference](expressions.md), copy a suite from
[`examples/`](../examples/README.md), and run `ivy run --help`.
