# kafka

```yaml
- type: kafka
  clientType: producer            # or consumer
  addrs: ["localhost:9092"]
  messages:
  - topic: orders
    key: "42"
    headers: {source: ivy}
    value: '{"id": 42}'           # or valueFile; with withAvro, avroSchemaFile
- type: kafka
  clientType: consumer
  addrs: ["localhost:9092"]
  groupID: tests
  topics: [orders]
  initialOffset: oldest
  messageLimit: 1                 # or waitFor: seconds
  assertions:
  - result.messages[0].value.id == 42
```

Also: `withTLS`, `withSASL` (`user`, `password`, `saslMechanism`), `withAvro` + `schemaRegistryAddr`,
`keyFilter`, `timeout`, `messagesFile`, `properties` (any Kafka client setting).
Result: `messages[]` (`topic`, `partition`, `offset`, `key`, `headers`, `value`: parsed when it is JSON,
`raw`: the text), `durationMs`, `error`. The default assertion is `isEmpty(result.error)`.
