# browser

Drives Chromium, Firefox or WebKit with [Playwright](https://playwright.dev/java/).

```yaml
- type: browser
  browser: chromium          # firefox, webkit
  headless: true
  wsEndpoint: ${pw_ws}       # optional: a Playwright server instead of a local browser
  actionTimeout: 10000       # milliseconds an action may wait (default 10000)
  actions:
  - goto: ${base}/login
  - fill: {selector: "#user", value: ada}
  - fill: {selector: "#password", value: "${password}"}
  - click: "button[type=submit]"
  - waitFor: {selector: ".welcome", state: visible, timeout: 5000}
  - text: ".welcome"
    as: welcome              # result.values.welcome
  - attribute: {selector: "a.profile", name: href}
    as: profile
  - evaluate: "() => document.title"
    as: title
  - screenshot: home.png     # in the output directory
  assertions:
  - result.url.endsWith("/home")
  - result.values.welcome == "Hello ada"
```

Actions: `goto`, `fill`, `click`, `press` (`{selector, key}`), `check`, `select` (`{selector, value}`),
`waitFor` (a selector, or `{selector, state, timeout}`), `text`, `attribute`, `evaluate`, `screenshot`.
`text`, `attribute` and `evaluate` need `as`: the name of their value in `result.values`.

Result: `url`, `title`, `values`, `screenshots[]` (absolute paths), `durationMs`, `error`. The first
failing action sets `error` (`action #2 (click): ...`), saves a screenshot and stops the step; the
default assertion is `result.error == ""`. The HTML report shows the screenshots of each step.

A test case keeps one page from step to step, so a login carries over; each test case has a
browser context of its own, so no cookie or storage leaks from one to the next. Browsers are
shared: test cases reuse a browser, and suites running in parallel (`--parallel`) get browsers of
their own.

## Browsers

Browsers are not bundled and never downloaded during a run:

- `ivy browser install chromium --bundles-dir <dir>` installs one with the Playwright of the bundle
  (no Node.js needed); `npx playwright install chromium` does the same;
- or `wsEndpoint` points to a Playwright server: `npx playwright run-server --port 3000`, or the
  `mcr.microsoft.com/playwright` image.

The bundle is large (about 200 MB): it carries the Playwright driver (Node.js) of every platform,
which Playwright for Java needs even with `wsEndpoint`. With the native binary, the bundle runs in
`ivy-connector-server`; screenshots are then written on the machine of the server.

Screenshots are images: secrets typed into the page cannot be hidden in them.
