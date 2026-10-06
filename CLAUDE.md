# CLAUDE.md

ivy is a declarative integration-test runner (a Java port of OVH's Venom). Suites are YAML or Gherkin
`.feature` files; each step runs a *connector* (`exec`, `http`, `sql`, `kafka`, `browser`...) and its
`assertions` are expressions over the step's `result`. Java 25, Gradle, Quarkus (CLI only).

## Commands

```shell
./gradlew build                 # compile, unit + acceptance tests, spotlessCheck
./gradlew spotlessApply         # fix formatting (the pre-commit hook in .githooks runs spotlessCheck)
./gradlew :ivy-core:test --tests '*EngineTest'      # one test class
./gradlew integrationTest       # connector tests against Testcontainers (needs Docker)
./gradlew bundle                # connector bundles -> connectors/*/build/bundle
./gradlew ivyReport             # rebuild the HTML report (Node); output is committed, see below
./gradlew :ivy-core:perfTest    # parallel-suite / memory benchmark

# run the CLI from a build
java --enable-native-access=ALL-UNNAMED -jar ivy-cli/build/quarkus-app/quarkus-run.jar run <paths> \
    [--bundles-dir connectors/browser/build/bundle] [--output-dir out] [--html-report]
ivy validate <paths>            # parse and check without running; exit 2 on errors
```

Exit code is 0 when all suites pass, 2 otherwise. Settings precedence: env (`IVY_*`) < `.ivyrc`
(cwd, then home) < flags. `IVY_VAR_x=` / `IVY_SECRET_x=` set variables.

## Modules

| Module | Role |
|---|---|
| `ivy-api` | The connector SPI (`xyz.fokion.ivy.spi`): `Connector<C>`, `@ConnectorClass(type, configurationClass)`, `Configuration`, `@ConfigurationProperty`, `StepContext`, `Struct`, JSON helpers. Connector bundles depend on it `compileOnly`. |
| `ivy-core` | The engine. `engine/` (`Ivy` loads suites and runs them, `StepProcessor` runs a step, `Scheduler` runs suites in parallel on virtual threads, `SuiteValidator`, `AssertionChecker`, `ExecutorRunner` for `until`/user executors, `Outputs` writes reports), `expr/` (own parser + evaluator for `${}` templates and the JS-subset expressions), `gherkin/`, `connector/` (loading, config binding, remote protocol), `connectors/` (built-in `exec`, `http`, `readfile`), `model/`, `log/Secrets` (masking). |
| `ivy-cli` | `Cli` (arg parsing, `run`/`validate`/`mcp`/`browser`/`version`), `Main`/`IvyMain` (Quarkus entry), `mcp/` (MCP server over stdio: `McpServer`, `Tools`), native-image registrations. |
| `ivy-connector-server` | Serves JVM connector bundles over a socket to the GraalVM native binary (which only has the built-ins). |
| `connectors/*` | `sql` (+`dbfixtures`), `kafka`, `redis`, `mongo`, `couchbase`, `browser` (Playwright). Each is a bundle: its classes plus deps in `lib/`, loaded in an isolated class loader (`BundleClassLoader`). Each has a README documenting its steps. |
| `ivy-report` | Vite + Tailwind + TypeScript HTML report. Built output is **committed** to `ivy-core/src/main/resources/xyz/fokion/ivy/core/output/report.html`; CI fails if it is stale. After editing `ivy-report/`, run `./gradlew ivyReport` and commit both. |

## How a run flows

`Cli` → `Ivy` reads suite files (`yaml/Yaml` keeps line numbers; `gherkin/FeatureLoader` turns
scenarios into test cases using `*.steps.yml` from `steps/` and `lib/` dirs) → `SuiteValidator` →
`Scheduler` → per test case a `RunContext` and `StepProcessor`: render `${}` templates (except
`CONTROL_KEYS`: `assertions if retryIf until set info range with`) → bind the step keys to the
connector's `Configuration` (`ConfigurationBinder`, validated via `Configuration.validate()`) →
`Connector.run` → result is flattened into `result.*` → `until`/`retry` → assertions
(`AssertionChecker`) → `set` → `info`. Reports are written by `Outputs` (xml/json/yaml/tap/cucumber/html).

The engine reaches connectors through `ConnectorInfoManager`/`ConnectorFacade`: `Local*` for
classpath/bundle connectors, `Remote*` (`connector/remote`, `Frames`, `WireCodec`) for
`ivy-connector-server`. A new `Connector` instance is created per test case (`open` before the first
step, `close` at the end), so sessions may live in fields.

## Suite syntax (what exists)

- Suite: `name`, `vars`, `secrets` (names to hide), `parallel: false` (runs alone), `testcases`.
- Test case: `name`, `vars`, `if` (expression), `skip`, `steps`.
- Step: `type` (omitted = checks only, sees the previous step's result), `name`, the connector's own
  keys, `assertions` (string, `{must: expr}` stops the case, `{that, with}`), `set`, `info`, `if`,
  `retry` + `retryIf` + `delay`, `until` + `within` + `every` (mutually exclusive with `retry`),
  `timeout`, `range` (list, map, number or variable → `index`/`key`/`value`), `with` (step-local vars,
  each may use earlier ones), `script` (shorthand for an `exec` step).
- Durations (`delay`, `timeout`, `within`, `every`) are seconds and may be fractional.
- User executors: files with `executor: name`, `input`, `steps`, `output`, found in `lib/` or
  `--lib-dir`; called as a step `type: name`.
- Gherkin: `*.steps.yml` entries use `expression:` (Cucumber expressions) or `match:` (regex), with a
  `step:` and/or `assertions:`; captures are `arg1…` or named groups. Tags filter with `--tags`.
- Expression variables: suite/case `vars`, `set` names, `cases.<name>.<v>`, `result`, `index/key/value`,
  `input`, `env`, `ivy.*`. Full reference: `docs/expressions.md`, `docs/secrets.md`.
- A connector's `resultFields()` drives validation warnings (`result.statuscode` on `http`) and the
  MCP `list_step_types` tool; keep it accurate when adding fields.

## Adding a connector

New module `connectors/<name>` (add to `settings.gradle`): a `Configuration` bean (`@ConfigurationProperty`
setters, `validate()`), a `Connector` with `@ConnectorClass`, an entry in
`META-INF/services/xyz.fokion.ivy.spi.Connector`, runtime deps in the `bundled` configuration, a README,
and an `@Tag("integration")` test that runs a suite in `src/test/resources/<name>-suite/` through
`SuiteRunner` (from `ivy-core` testFixtures). Mark secret properties `secret = true`. Add the row to
`docs/connectors.md` and the README table. See `connectors/redis` for the smallest example.

## Conventions and gotchas

- Formatting is Spotless (unused imports, trailing whitespace, final newline). Run `spotlessApply`.
- JUnit 6; unit tests next to code; acceptance suites for the CLI live in
  `ivy-cli/src/test/resources/suites/` and run via `AcceptanceSuitesTest` — add a suite there when
  adding engine behaviour. `failing/` holds suites that must fail.
- Secrets: anything written (log, console, every report) must go through `Secrets.hide`. Don't add a
  new output path that bypasses it. A perf TODO for it is in `TODOS.md`.
- Native image: the native binary only has `exec`, `http`, `readfile`; reflection needs
  `NativeRegistrations`. Other connectors need `ivy-connector-server` + `--connector-server key@host:port`.
- `docs/expressions.md` and `docs/secrets.md` are bundled into the CLI and served by the MCP
  `describe_syntax` tool — edits there change what Claude sees via MCP.
- Release: every merge to `master` releases (`.github/workflows/release.yml`); `feat…`/`#minor` bumps
  minor, `#major`/`BREAKING CHANGE` bumps major. Docs/examples-only changes do not release.
- The `ivy` MCP server (tools `list_step_types`, `describe_syntax`, `write_suite`, `validate_suite`,
  `run_suite`, ...) may be available in a session: use it to try suites rather than guessing syntax.

## Keeping this file current

This file describes the code as it is, so it goes stale. When a change alters engine behaviour, add or
remove a step key, module, connector or CLI flag, update the matching section here (Modules, How a run
flows, Suite syntax, Adding a connector) in the same commit, and the docs it points to
(`docs/guide.md`, `docs/expressions.md`, connector READMEs). If something here contradicts the code,
trust the code and fix this file.

## Docs map

`README.md` (overview, install, CLI features) · `docs/guide.md` (getting started tutorial) ·
`docs/expressions.md` · `docs/secrets.md` · `docs/connectors.md` · `docs/mcp.md` · connector READMEs ·
`examples/` (runnable suites, see `examples/README.md`).
