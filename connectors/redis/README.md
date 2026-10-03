# redis

```yaml
- type: redis
  dialURL: redis://localhost:6379   # or the variable redis: {dialURL: ...}
  commands:
  - SET greeting "hello world"
  - GET greeting
  assertions:
  - result.commands[1].response == "hello world"
```

`path` reads commands from a file, one per line. Result: `commands[]` (`name`, `args`, `response`).
