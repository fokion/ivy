# mongo

```yaml
- type: mongo
  uri: mongodb://localhost:27017
  database: shop
  collection: cards
  actions:
  - type: loadFixtures        # drops the collections, loads <collection>.yml files
    folder: fixtures
  - type: find
    filter: '{"suit": "clubs"}'
    options: {limit: 3, sort: '{"value": -1}'}
  assertions:
  - result.actions[1].results.length == 2
```

Actions: `loadFixtures`, `insert` (`documents`, `file`), `find`, `count`, `update`, `delete`,
`aggregate` (`pipeline`), `createCollection`, `dropCollection`. Filters are Extended JSON or YAML maps.
