package xyz.fokion.ivy.connectors.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DsnTest {

    @Test
    void translatesGoDsns() {
        assertEquals("jdbc:sqlite::memory:", Dsn.toJdbc("sqlite", ":memory:", null));
        assertEquals("jdbc:postgresql://localhost:1234/yo?user=test&password=test&sslmode=disable",
                Dsn.toJdbc("postgres", "user=test password=test dbname=yo host=localhost port=1234 sslmode=disable", null));
        assertEquals("jdbc:postgresql://db:5432/app?user=app&password=p%40ss&sslmode=disable",
                Dsn.toJdbc("postgres", "postgres://app:p%40ss@db/app?sslmode=disable", null));
        assertEquals("jdbc:mysql://localhost:13306/app?user=app&password=secret",
                Dsn.toJdbc("mysql", "app:secret@tcp(localhost:13306)/app?parseTime=true", null));
        assertEquals("jdbc:h2:mem:x", Dsn.toJdbc("anything", "jdbc:h2:mem:x", null));
    }

    @Test
    void handlesCredentialsWithAt() {
        assertEquals("jdbc:postgresql://db:5433/app?user=me&password=p%40ss%40",
                Dsn.toJdbc("postgres", "postgres://me:p@ss@@db:5433/app", null));
        assertEquals("jdbc:mysql://h:3306/db?user=me&password=a%40b",
                Dsn.toJdbc("mysql", "me:a@b@tcp(h:3306)/db", null));
        org.junit.jupiter.api.Assertions.assertThrows(xyz.fokion.ivy.spi.ConnectorException.class,
                () -> Dsn.toJdbc("postgres", "postgres:///db", null));
        org.junit.jupiter.api.Assertions.assertThrows(xyz.fokion.ivy.spi.ConnectorException.class,
                () -> Dsn.toJdbc("mysql", "me@unix(/tmp/s.sock)/db", null));
    }
}
