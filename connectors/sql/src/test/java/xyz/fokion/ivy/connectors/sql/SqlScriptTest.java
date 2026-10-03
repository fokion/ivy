package xyz.fokion.ivy.connectors.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class SqlScriptTest {

    @Test
    void splitsStatements() {
        assertEquals(List.of("CREATE TABLE a (s TEXT DEFAULT ';')", "-- a; comment\nINSERT INTO a VALUES ('it''s;')",
                "CREATE FUNCTION f() AS $$ SELECT 1; $$", "SELECT \"x;y\" FROM a"),
                SqlScript.statements("""
                        CREATE TABLE a (s TEXT DEFAULT ';');
                        -- a; comment
                        INSERT INTO a VALUES ('it''s;');
                        CREATE FUNCTION f() AS $$ SELECT 1; $$;
                        SELECT "x;y" FROM a;
                        -- trailing comment
                        """));
    }

    @Test
    void readsUpMigrations() {
        assertEquals("\nCREATE TABLE t (id INT);\n",
                SqlScript.up("-- +migrate Up\nCREATE TABLE t (id INT);\n-- +migrate Down\nDROP TABLE t;\n"));
        assertEquals("CREATE TABLE t (id INT);", SqlScript.up("CREATE TABLE t (id INT);"));
    }
}
