# couchbase

```yaml
- type: couchbase
  dsn: couchbase://localhost
  username: admin
  password: secret
  bucket: travel
  actions:
  - type: upsert
    entries:
      airline_1: {name: first}
  - type: get
    ids: [airline_1]
  assertions:
  - result.actions[1].airline_1.data.name == "first"
```

Actions: `get`, `insert`, `upsert`, `replace`, `delete`, `touch`, `exists` (by `ids` or `entries`),
`query` (`statement`, `parameters`). Each action may override `bucket`, `scope`, `collection`, `expiry`,
`transcoder`.
