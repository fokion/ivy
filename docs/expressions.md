# Expressions and templates

## Templates

Any string in a step can hold `${...}`: `url: ${base}/users/${user.id}`. A value that is only
`${...}` keeps its type (`range: ${items}` is a list). Write `$${` for a literal `${`, for instance to
pass `$${HOME}` to a shell.

## Expressions

`assertions`, `if`, `retryIf`, `range` and the values of `set` are expressions, a subset of JavaScript:

```yaml
- script: curl -s localhost:8080/orders
  if: env.CI != "true"
  assertions:
  - result.exitCode == 0
  - result.json.orders.length > 2
  - result.json.orders.some(o => o.status == "paid")
  - result.stdout contains "paid"
  - must: result.json.total >= 100        # stops the test case when false
  set:
    firstId: result.json.orders[0].id
    token: result.stdout.match("token=(\\w+)")[1] ?? "none"
```

- Paths: `a.b`, `a[0]`, `a[-1]`, `a["x-y"]`, `a?.b`, `.length`. A missing key is `null`; an unknown
  variable is an error.
- Operators: `== !=` (numbers and numeric strings compare as numbers, lists and objects deeply),
  `=== !==`, `< <= > >=`, `&& || ! ??`, `? :`, `+ - * / %`, `contains`, `matches` (regex), `in`, and
  `!contains`, `!matches`, `!in`.
- Methods: strings (`startsWith`, `endsWith`, `includes`, `split`, `replace`, `trim`, `toUpperCase`,
  `match`, `lines`...), arrays (`map`, `filter`, `find`, `some`, `every`, `includes`, `join`, `slice`,
  `sort`...), objects (`keys`, `values`, `has`).
- Functions: `len`, `keys`, `values`, `isEmpty`, `json`, `toJson`, `number`, `string`, `typeOf`,
  `between`, `approx`, `date` (epoch ms), `now`, `base64`, `unbase64`, `uuid`, `randomInt`, `abs`,
  `round`, `floor`, `ceil`, `min`, `max`.

A failed assertion shows the values it read:

```
Testcase "orders", step #1 (orders.yml:8): assertion failed: result.json.orders.length > 2
  result.json.orders.length = 1
```

In YAML, quote an expression that starts with `!` or holds `: `.

## Variables

| Name | What |
|---|---|
| suite and test case `vars`, `--var`, `--var-from-file` | your values, nested as written |
| names from `set` | values set by earlier steps of the test case |
| `cases.<name>.<value>` | values set by an earlier test case (`cases["get token"].token`) |
| `result` | the result of the step, in its assertions, `retryIf`, `info` and `set` |
| `index`, `key`, `value` | the item of a ranged step |
| `with` | variables of one step, in order: each value may use the ones before it (Gherkin captures come first) |
| `input` | the inputs of a user executor |
| `env` | environment variables |
| `ivy` | `ivy.suite.{name, file, path, workdir}`, `ivy.case.{name, tags}`, `ivy.step.number`, `ivy.outputDir`... |
