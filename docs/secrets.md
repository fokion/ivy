# Secrets

Secret values are replaced by `__hidden__` wherever ivy writes: `ivy.log`, the console, every report
format (xml, json, yaml, tap, cucumber, html) and the `-vv` step dumps. Their base64, URL, JSON and
XML encodings are hidden too, and so is the basic auth token of a secret `basic_auth_password`.

A value is secret when:

- it is given on the command line: `IVY_SECRET_token=...`, `--secret-from-file secrets.yml`, or
  `--secret token=...` (visible to other users of the machine; prefer the first two);
- a suite lists its variable's name under `secrets`, whoever sets it: suite and test case `vars`,
  a step's `set` (even when the step fails), `with` and executor inputs. Names may be paths:

```yaml
secrets: [password, token, db.password]
vars:
  db: {host: localhost, password: ${env.DB_PASSWORD}}
testcases:
- name: login
  steps:
  - type: http
    url: ${base}/login
    body: '{"password": "${db.password}"}'
    set:
      token: result.body.token        # hidden from now on, in every report
```

The log is written at the end of each test case, so a token captured by a step is also hidden in
the lines logged before it was captured. A secret shorter than 4 characters is hidden all the same,
with a warning in the log, since every occurrence of it is replaced.
