<p align="center"><img src="docs/images/ivy-logo.png" alt="ivy" width="420"></p>

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

New to ivy? Start with the [getting-started guide](docs/guide.md) and the runnable
[examples](examples/README.md).

Templates are `${...}`, assertions are expressions; see [docs/expressions.md](docs/expressions.md).
Secrets are hidden in logs and reports; see [docs/secrets.md](docs/secrets.md).

## Install

| How | What you get |
|---|---|
| `brew install fokion/tap/ivy` | the native binary (macOS on Apple Silicon, Linux x64 and arm64) |
| [Releases](https://github.com/fokion/ivy/releases) | native binaries for Linux x64/arm64, macOS arm64 and Windows x64; `ivy-<version>-jvm.tar.gz`, ivy on Java 25 with every connector bundle |
| `docker run --rm -v "$PWD:/work" ghcr.io/fokion/ivy run tests/` | ivy with every connector bundle but the browser |
| `ghcr.io/fokion/ivy:latest-browser` | the same, plus the browser connector and Chromium |

The native binary has the built-in steps (`exec`, `http`, `readfile`); the other connectors are
bundles for the JVM, served to it by `ivy-connector-server` (included in the JVM archive). A binary
downloaded with a browser on macOS is quarantined: `xattr -d com.apple.quarantine ivy`.

As an MCP server from Docker:

```shell
claude mcp add ivy -- docker run --rm -i -v "$PWD:/work" ghcr.io/fokion/ivy mcp --workspace /work
```

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
prints the first error of each test case (with `file:line`). It warns about assertions reading a
result field the step type does not have, such as `result.statuscode` for `http`, and about keys a
step would ignore, such as `methd` for `method`. Use it in CI before running suites.

`retry` is a whole number; `delay` and `timeout` are seconds and may have a fraction (`delay: 0.5`).
A step without a type runs nothing: it checks variables and the result of the step before it.

## Waiting for a condition

`until` runs a step again until an expression holds, then checks its assertions on that result:

```yaml
- type: http
  url: ${base}/orders/${id}
  until: result.body.status == "PLACED"   # an expression on the result
  within: 10                               # seconds to wait at most (default 30)
  every: 0.5                               # seconds between attempts (default 1)
  assertions:
  - result.body.barista != null
```

An attempt that fails to run, such as a request to a service that is not up yet, counts as "not yet".
When `within` runs out, the step fails with the values the condition read:

```
until result.body.status == "PLACED" was still false after 10.2s (21 attempts, within 10s)
  result.body.status = "RECEIVED"
```

`retry` is for the other case: running a step again while its assertions fail, such as a flaky call.
A step uses one or the other.

## Writing tests with Claude (MCP)

`ivy mcp` serves tools over stdio, so a model can write suites from a description or a Gherkin
file, check them, run them and fix them:

```shell
claude mcp add ivy -- ivy mcp --workspace .            # add --no-run to only validate and write
```

The variables the suites run with (hosts, credentials) can be given to every tool:
`ivy mcp --var-from-file env.yml --secret-from-file secrets.yml`; a tool call can also pass `vars` and
`secrets`. Secrets are hidden in every answer.

| Tool | What it does |
|---|---|
| `list_step_types`, `describe_syntax` | step types with their properties and result fields; the syntax |
| `validate_suite` | checks a suite: errors per test case, warnings (such as a misspelled property) |
| `write_suite` | writes a suite only when it is valid (checked in its folder first) |
| `delete_suite` | deletes a suite or step definitions, such as a scratch suite |
| `run_suite` | runs a suite: status per test case, `info` lines, failed steps with their values (every step's result with `details`); secrets hidden |
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
| Docker image | `docker build -t ivy .` (`--target browser` for the browser connector and Chromium) |

### Releasing

Every merge to `master` publishes a release; changes to Markdown, `docs/` and `examples/` alone do not.
The [release workflow](.github/workflows/release.yml) takes the next version after the last `vX.Y.Z`
tag, from the messages of the commits merged since:

| Commit message | Version after v1.4.2 |
|---|---|
| anything | v1.4.3 |
| starts with `feat`, or contains `#minor` | v1.5.0 |
| contains `#major` or `BREAKING CHANGE` | v2.0.0 |

It builds the native binaries with GraalVM for JDK 25 on Linux x64/arm64, macOS arm64 and Windows x64,
the JVM archive (after the tests pass) and the Docker images (`linux/amd64`, `linux/arm64`). Only when
all of them are built does it create the tag and the GitHub release, with `checksums.txt`. The first
release is v0.1.0. A tag pushed by hand (`git tag v1.2.3 && git push origin v1.2.3`) releases that
version, and "Run workflow" on the Actions tab releases `master` with the bump you pick.

To make a Homebrew formula for a release:
`packaging/homebrew/render-formula.sh <version> checksums.txt fokion/ivy > ivy.rb`.

## License

[Apache License 2.0](LICENSE). ivy started as a port of [Venom](https://github.com/ovh/venom) (Apache
2.0); see [NOTICE](NOTICE).
