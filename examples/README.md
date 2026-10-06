# Examples

Runnable suites. Start with `getting-started`; the [guide](../docs/guide.md) explains each one.
From the repository root, with `ivy` meaning the CLI (`./gradlew build` first, then
`alias ivy='java --enable-native-access=ALL-UNNAMED -jar ivy-cli/build/quarkus-app/quarkus-run.jar'`):

## getting-started (no network, no Docker; needs `python3` for 03)

| File | Shows |
|---|---|
| [`01-hello.yml`](getting-started/01-hello.yml) | `exec` steps (`script`, `command`), `result`, assertions, `info` |
| [`02-variables.yml`](getting-started/02-variables.yml) | `vars`, `--var`, `set`, `cases.<name>`, `must`, `if` |
| [`03-local-http.yml`](getting-started/03-local-http.yml) | `http` against a server the suite starts, `until`/`within`/`every` |
| [`04-files-loops-retries.yml`](getting-started/04-files-loops-retries.yml) | `readfile`, `range`, `retry`, `until` |
| [`05-secrets.yml`](getting-started/05-secrets.yml) | `secrets`, `IVY_SECRET_*`, masking in reports |
| [`06-user-executors.yml`](getting-started/06-user-executors.yml) + [`lib/greet.yml`](getting-started/lib/greet.yml) | a reusable step type |
| [`features/calculator.feature`](getting-started/features/calculator.feature) + [`steps/`](getting-started/features/steps/shell.steps.yml) | Gherkin, outlines, tags |

```shell
ivy validate examples/getting-started examples/getting-started/features
ivy run examples/getting-started examples/getting-started/features --output-dir out --html-report
ivy run examples/getting-started/features --tags "not @slow"
IVY_SECRET_api_key=abc-123-xyz ivy run examples/getting-started/05-secrets.yml --output-dir out
```

`ivy run <dir>` reads the suites of that folder only, not its sub-folders.

## reuse (needs the internet: uses the public https://jsonplaceholder.typicode.com API)

Four small blocks in [`reuse/lib`](reuse/lib) (`api_get`, `api_post`, then `user_with_posts` and
`publish_post`, built from them) used by every suite below. Nothing else is needed: no server, no code.

| File | Shows |
|---|---|
| [`01-compose-blocks.yml`](reuse/01-compose-blocks.yml) | one block with defaults, with other inputs (a 404 check), blocks built from blocks, a business action |
| [`02-data-driven.yml`](reuse/02-data-driven.yml) | `range` over a table of data: one block, many cases |
| [`03-flow-with-shared-state.yml`](reuse/03-flow-with-shared-state.yml) | test cases passing values on with `cases.<name>`, blocks mixed with shell steps |
| [`features/users.feature`](reuse/features/users.feature) + [steps](reuse/features/steps/users.steps.yml) | the same blocks driven from Gherkin, with scenario outlines |

```shell
ivy run examples/reuse examples/reuse/features --output-dir out --html-report
```

## browser (needs the internet and a browser)

[`hacker-news.yml`](browser/hacker-news.yml), [`hacker-news.feature`](browser/hacker-news.feature)
and [`fokion-online.yml`](browser/fokion-online.yml) drive Chromium through the `browser` connector.
Build the bundle and install a browser first:

```shell
./gradlew bundle
mkdir -p bundles && cp connectors/*/build/bundle/*.jar bundles/
ivy browser install chromium --bundles-dir bundles
ivy run examples/browser/hacker-news.yml --bundles-dir bundles
```

Screenshots and `hacker-news.json` land in `examples/browser/output`.
[docs/mcp.md](../docs/mcp.md) shows how Claude wrote the Hacker News suite.

## More

Every connector README has a short snippet; the integration suites in `connectors/*/src/test/resources/`
are larger, working examples (they run against Testcontainers: `./gradlew integrationTest`).
