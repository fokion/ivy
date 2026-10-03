# ivy

A declarative integration-test runner. Write suites in YAML or Gherkin; each step runs a connector
(shell, HTTP, SQL, Kafka…), and assertions check its result.

```yaml
name: smoke
vars:
  base: https://example.com
testcases:
- name: home page
  steps:
  - type: http
    url: ${base}/api/items
    assertions:
    - result.status == 200
    - result.body.items.length > 0
    set:
      first: result.body.items[0].id
```

Templates are `${...}`, assertions are expressions; see [docs/expressions.md](docs/expressions.md).
Secrets are hidden in logs and reports; see [docs/secrets.md](docs/secrets.md).

## Build and run

Requires Java 25.

```shell
./gradlew build
java -jar ivy-cli/build/quarkus-app/quarkus-run.jar run tests/ --output-dir out --html-report
```

Native binary (needs Docker):

```shell
./gradlew :ivy-cli:build -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
    -Dquarkus.native.container-build=true
```

Run `ivy run --help` to see the flags.

## Parallel suites

`--parallel n` (also `IVY_PARALLEL`, or `parallel: n` in `.ivyrc`) runs up to `n` suites at the
same time; the test cases of a suite still run one after the other, so `cases.<name>` works as
before. The default is 1.

A suite that must not run alongside others, such as one that resets a database, says so:

```yaml
name: orders
parallel: false   # runs alone, in its turn
```

With more than one suite at a time, the console prints each test case as one block, starting
with its suite (`[orders] • create order PASS`), and `ivy.log` keeps the lines of each test case
together.

## Validate without running

`ivy validate [paths...]` parses and checks suites without running a step: it exits with 2 and
prints the first error of each test case (with `file:line`), and warns about assertions reading a
result field the step type does not have, such as `result.statuscode` for `http`. Use it in CI
before running suites.

## Writing tests with Claude (MCP)

`ivy mcp` serves tools over stdio, so a model can write suites from a description or a Gherkin
file, check them, run them and fix them:

```shell
claude mcp add ivy -- ivy mcp --workspace .            # add --no-run to only validate and write
```

| Tool | What it does |
|---|---|
| `list_step_types`, `describe_syntax` | step types with their properties and result fields; the syntax |
| `validate_suite` | checks a suite: errors per test case, warnings |
| `write_suite` | writes a suite only when it is valid (checked in its folder first) |
| `run_suite` | runs a suite: status per test case, failed steps with their values; secrets hidden |
| `evaluate_expression` | evaluates an expression against given values |
| `list_step_definitions`, `gherkin_steps` | Gherkin definitions; steps without one, with a definition to start from |

Paths are confined to the workspace. Steps run for real (`exec` runs commands), so `run_suite` is
marked destructive; a client asks before calling it.

[docs/mcp.md](docs/mcp.md) walks through a whole session: a browser suite that opens the 3rd Hacker
News story, from the first draft to the run that passes.

To check every suite file Claude writes or edits itself, add a hook to `.claude/settings.json`:

```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "Write|Edit",
      "hooks": [{
        "type": "command",
        "command": "f=$(jq -r '.tool_input.file_path'); case \"$f\" in *.steps.yml) ;; *tests/*.yml|*tests/*.yaml|*tests/*.feature) ivy validate \"$f\" || exit 2 ;; esac"
      }]
    }]
  }
}
```

The errors of an invalid suite go back to Claude, which fixes the file.

## Gherkin

`.feature` files run directly. Steps map to `*.steps.yml` definitions:

```yaml
steps:
  - expression: 'I GET {string}'
    step: { type: http, url: "${base_url}${arg1}" }
  - expression: 'the status is {int}'
    assertions: [ "result.status == arg1" ]
```

## Connectors

| Built in | Bundles (`connectors/`) |
|---|---|
| `exec`, `http`, `readfile` | `sql` + `dbfixtures`, `kafka`, `redis`, `mongo`, `couchbase`, `browser` |

Bundles are loaded with `--bundles-dir` on the JVM, or served to the native binary by
`ivy-connector-server`. Each bundle documents its steps in its own README.

## Development

| Task | Command |
|---|---|
| Unit tests | `./gradlew build` |
| Connector tests (Docker) | `./gradlew integrationTest` |
| Benchmark (parallel suites, memory) | `./gradlew :ivy-core:perfTest` |
| Connector bundles | `./gradlew bundle` (in `connectors/*/build/bundle`) |
| HTML report (Node) | `./gradlew ivyReport` |
