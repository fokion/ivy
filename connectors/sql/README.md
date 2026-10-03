# sql and dbfixtures

```yaml
- type: dbfixtures
  database: postgres            # postgres, mysql, sqlite
  dsn: "jdbc:postgresql://localhost/app?user=app&password=secret"
  migrations: migrations        # or schemas: [schema.sql]
  folder: fixtures              # one <table>.yml per table
- type: sql
  driver: postgres
  dsn: "jdbc:postgresql://localhost/app?user=app&password=secret"
  commands: ["SELECT count(*) AS n FROM players"]
  assertions:
  - result.queries[0].rows[0].n == 2
```

DSNs are JDBC URLs or Go driver DSNs. Fixture values starting with `RAW=` are SQL expressions.
