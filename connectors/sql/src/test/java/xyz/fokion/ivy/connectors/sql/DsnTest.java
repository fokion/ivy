package xyz.fokion.ivy.connectors.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DsnTest {

    @Test
    void translatesGoDsns() {
        assertEquals("jdbc:sqlite::memory:", Dsn.toJdbc("sqlite", ":memory:", null));
        assertEquals("jdbc:postgresql://localhost:1234/yo?user=test&password=test&sslmode=disable",
                Dsn.toJdbc("postgres", "user=test password=test dbname=yo host=localhost port=1234 sslmode=disable", null));
        assertEquals("jdbc:postgresql://db:5432/venom?user=venom&password=p%40ss&sslmode=disable",
                Dsn.toJdbc("postgres", "postgres://venom:p%40ss@db/venom?sslmode=disable", null));
        assertEquals("jdbc:mysql://localhost:13306/venom?user=venom&password=venom",
                Dsn.toJdbc("mysql", "venom:venom@tcp(localhost:13306)/venom?parseTime=true", null));
        assertEquals("jdbc:h2:mem:x", Dsn.toJdbc("anything", "jdbc:h2:mem:x", null));
    }
}
