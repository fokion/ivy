# Using ivy from Claude (MCP)

`ivy mcp` serves ivy's tools over stdio. An MCP client such as Claude Code can then turn a plain
request into a suite: it learns the step types, writes a suite, runs it and fixes the suite until it
passes. This guide follows one real session, built on the request *"open news.ycombinator.com and
open the 3rd item"*. The suite it produced is
[`examples/browser/hacker-news.yml`](../examples/browser/hacker-news.yml).

## Setup

Build the CLI and the browser connector bundle, then register the server with Claude Code:

```shell
./gradlew build
claude mcp add ivy -- java --enable-native-access=ALL-UNNAMED \
    -jar ivy-cli/build/quarkus-app/quarkus-run.jar mcp \
    --workspace examples --bundles-dir connectors/browser/build/bundle
```

- `--workspace` confines every path. `write_suite` and `run_suite` take paths relative to it and
  refuse anything outside it, including the client's scratch folders.
- `--bundles-dir` loads connectors that are not built in. Without it, `list_step_types` shows only
  `exec`, `http` and `readfile`, and the `browser` step does not exist.
- `--no-run` gives a server that only validates and writes suites.

## The loop

The server's instructions tell the client to follow these steps:

1. **Learn**: `list_step_types` and `describe_syntax`.
2. **Write**: `write_suite` saves a suite only when it is valid. Otherwise it saves nothing and
   returns the errors.
3. **Run and fix**: `run_suite`, then correct the suite from the values it reports.

`validate_suite` checks a suite without writing it. `evaluate_expression` tries out an assertion
against a value from a run.

## Walkthrough

### 1. Learn the step types

`list_step_types` returned four types. `browser` was the one that fitted the request:

```json
{"type": "browser",
 "properties": [{"name": "actions", "required": true,
                 "help": "goto, fill, click, press, check, select, waitFor, text, attribute, evaluate, screenshot; text, attribute and evaluate need 'as', the name of their value in result.values"},
                {"name": "wsEndpoint", "help": "ws://... of a Playwright server ..."}, ...],
 "resultFields": {"url": "...", "title": "...", "values": "...", "screenshots": "...", "error": "..."},
 "defaultAssertions": ["result.error == \"\""]}
```

`describe_syntax` gave the suite layout, the `${...}` templates and the expression language used by
`assertions` and `set`.

### 2. Write the first draft

On Hacker News, each story is a `tr.athing` row and its link is `.titleline > a`. The Playwright
selector `tr.athing >> nth=2 >> .titleline > a` therefore points at the 3rd story. The first call
to `write_suite`, path `hn.yml`:

```yaml
name: hacker news third item
testcases:
- name: open third item
  steps:
  - type: browser
    actions:
    - goto: https://news.ycombinator.com
    - text: "tr.athing >> nth=2 >> .titleline > a"
      as: title
    - attribute: "tr.athing >> nth=2 >> .titleline > a"
      name: href
      as: href
    - click: "tr.athing >> nth=2 >> .titleline > a"
    - screenshot: third-item.png
    info: "3rd item: ${result.values.title} -> ${result.url}"
```

The file was written: it was valid YAML, and every action had a known name.

### 3. Run it and read the failure

`run_suite` failed, and the failed step's result showed how far the run got:

```
assertion failed: result.error == ""
  result.error = "action #3 (attribute): name is required"
result: {"url": "https://news.ycombinator.com/", "values": {"title": "Court agrees with EFF: ..."},
         "screenshots": [".../hn-open_third_item-step1-failure.png"], ...}
```

The `text` action had already worked. `attribute` expects its arguments in one map, as in
`attribute: {selector: ..., name: href}`, and not as a separate `name:` key next to it. The
validator cannot catch this, because action arguments are only read at run time. A failed action
also saves a screenshot named after the suite, the test case and the step.

### 4. Make the run show its values

After the fix, `run_suite` returned only `PASS`. A passing run does not report step values or
`info`. Two ways to see them:

- **Temporarily**: add an assertion that fails, such as `result.url == "show-me"`. The report then
  includes the whole result. Remove the assertion afterwards.
- **For good**: have the suite write its findings to a file, as the final suite below does.

### 5. Keep the output

`run_suite` uses a temporary `--output-dir` and deletes it when the run ends, together with
`ivy.log` and any relative screenshot paths. To keep files, use absolute paths built from
`ivy.suite.workdir`, the suite's folder. They then stay next to the suite, whether the run comes
from MCP or from `ivy run --output-dir ...`.

### 6. The final suite

[`examples/browser/hacker-news.yml`](../examples/browser/hacker-news.yml):

```yaml
name: hacker news
vars:
  pw_ws: ""
  out: ${ivy.suite.workdir}/output
  story: "tr.athing >> nth=2 >> .titleline > a"
testcases:
- name: open third item
  steps:
  - type: browser
    name: open the 3rd story
    wsEndpoint: ${pw_ws}
    actions:
    - goto: https://news.ycombinator.com
    - screenshot: ${out}/hacker-news-front-page.png
    - text: ${story}
      as: title
    - attribute: {selector: "${story}", name: href}
      as: href
    - click: ${story}
    - screenshot: ${out}/hacker-news-third-item.png
    info: "3rd item: ${result.values.title} -> ${result.url}"
    assertions:
    - result.error == ""
    - result.url != "https://news.ycombinator.com/"
    set:
      item: "{rank: 3, title: result.values.title, href: result.values.href, url: result.url, pageTitle: result.title, screenshots: result.screenshots}"
  - name: save what was found
    script: cat > "${out}/hacker-news.json"
    stdin: ${toJson(item)}
```

- `set` builds an object from the browser result. The next step, an `exec` step, writes it out with
  `toJson`. Passing the JSON on `stdin` avoids quoting it for the shell.
- Declared assertions replace the default one, so `result.error == ""` is listed again.
- The second assertion checks that the click left the front page.

The run passed and left the following in `examples/browser/output/`:

| File | What |
|---|---|
| `hacker-news-front-page.png` | the front page before the click |
| `hacker-news-third-item.png` | the page of the 3rd story |
| `hacker-news.json` | rank, title, link, landing URL, page title and the screenshot paths |

```json
{"rank": 3,
 "title": "Court agrees with EFF: Utah's VPN law demands a technical impossibility",
 "href": "https://www.eff.org/deeplinks/2026/10/court-agrees-eff-utahs-vpn-law-demands-technical-impossibility",
 "url": "https://www.eff.org/deeplinks/2026/10/court-agrees-eff-utahs-vpn-law-demands-technical-impossibility",
 "pageTitle": "Court Agrees with EFF: Utah’s VPN Law Demands a Technical Impossibility | Electronic Frontier Foundation",
 "screenshots": ["…/examples/browser/output/hacker-news-front-page.png",
                 "…/examples/browser/output/hacker-news-third-item.png"]}
```

The front page changes all the time, so a later run finds a different story. The suite asserts
only that the click works, never which story is 3rd.

## The same test in Gherkin

The YAML suite can also be written as a `.feature` file, with each line mapped to a step definition.
MCP has two tools for this: `gherkin_steps` and `list_step_definitions`.

### 1. Write the feature first

[`examples/browser/hacker-news.feature`](../examples/browser/hacker-news.feature), written with
`write_suite`:

```gherkin
@browser
Feature: Hacker News

  Scenario: open the 3rd story
    Given I open "https://news.ycombinator.com"
    And I take a screenshot "hacker-news-front-page.png"
    When I open story 3
    Then I am no longer on "https://news.ycombinator.com/"
    And I take a screenshot "hacker-news-third-item.png"
    And I save the story to "hacker-news.json"
```

`write_suite` accepts a feature whose steps have no definition yet.

### 2. Ask for the missing definitions

`gherkin_steps` lists every step and proposes a definition for each step that has none, with its
arguments already turned into parameters:

```
undefined step: "When I open story 3" (hacker-news.feature:9), define it with:
- expression: 'I open story {int}'
  step: { script: echo TODO }
```

### 3. Fill them in

Definitions are looked up in the feature's folder and in its `steps/` and `lib/` folders.
[`examples/browser/steps/browser.steps.yml`](../examples/browser/steps/browser.steps.yml) replaces
each `echo TODO` with a real step:

```yaml
steps:
  - expression: 'I open {string}'
    step: {type: browser, actions: [{goto: "${arg1}"}]}
  - expression: 'I take a screenshot {string}'
    step: {type: browser, actions: [{screenshot: "${ivy.suite.workdir}/output/${arg1}"}]}
  - expression: 'I open story {int}'
    step:
      type: browser
      with:
        link: "tr.athing >> nth=${arg1 - 1} >> .titleline > a"
      actions:
      - text: ${link}
        as: title
      - attribute: {selector: "${link}", name: href}
        as: href
      - click: ${link}
      set:
        story: "{rank: number(arg1), title: result.values.title, href: result.values.href, url: result.url, pageTitle: result.title}"
  - expression: 'I am no longer on {string}'
    assertions: [ "result.url != arg1" ]
  - expression: 'I save the story to {string}'
    step:
      script: cat > "${ivy.suite.workdir}/output/${arg1}"
      stdin: ${toJson(story)}
```

- **One page per scenario.** The browser connector keeps the page from one step to the next within
  a scenario (a test case). So `I open`, `I open story` and `I take a screenshot` all act on the
  same page, and one YAML step became several reusable Gherkin steps.
- **Assertions without a step.** A definition with only `assertions` checks the result of the step
  before it. `I am no longer on` checks the URL left by the click.

### 4. What validation and the first run caught

| Tool | Message | Fix |
|---|---|---|
| `validate_suite` | `missing variables [story]` | `set` goes inside `step:`, not next to it |
| `run_suite` | `unknown variable arg1 ... in template "tr.athing >> nth=${arg1 - 1} ..."` | Gherkin captures reach the step through `with`, merged with the definition's own `with:`. Its values could not read one another, so `link` could not see `arg1`. Fixed in ivy: `with` values now resolve in order, each seeing the ones before it |
| output file | `"rank":"3"` | captures are strings: `number(arg1)` |

### 5. Run it

```
run_suite path=browser/hacker-news.feature tags=@browser
→ PASS: Hacker News / open the 3rd story
```

It writes the same files to `examples/browser/output/` as the YAML suite.

## Running it without Claude

```shell
ivy run examples/browser/hacker-news.yml --bundles-dir connectors/browser/build/bundle
ivy run examples/browser/hacker-news.feature --bundles-dir connectors/browser/build/bundle
# or against a Playwright server:
ivy run examples/browser/hacker-news.yml --bundles-dir connectors/browser/build/bundle \
    --var pw_ws=ws://127.0.0.1:13000/
```

## Tips

- Steps run for real. `run_suite` opens browsers, sends requests and runs commands, so a client asks
  before calling it. Use `--no-run` when that is not wanted.
- Read the `result` of a failed step before changing the suite. It holds every value gathered up to
  the failure.
- When an action fails at run time even though the suite is valid, compare it with the step's
  `example` in `list_step_types`, or with a working suite such as
  [`google-weather.yml`](../examples/browser/google-weather.yml).
- Use `evaluate_expression` to try an assertion against a value copied from a report, without
  running the suite again.
