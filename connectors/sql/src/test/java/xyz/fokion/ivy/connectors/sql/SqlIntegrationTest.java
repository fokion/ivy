package xyz.fokion.ivy.connectors.sql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import xyz.fokion.ivy.core.testing.SuiteRunner;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SqlIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void loadsFixturesAndQueriesPostgres(@TempDir Path dir) throws Exception {
        // a Go-style DSN, translated to JDBC by the connectors
        String dsn = "user=" + POSTGRES.getUsername() + " password=" + POSTGRES.getPassword() + " dbname="
                + POSTGRES.getDatabaseName() + " host=" + POSTGRES.getHost() + " port=" + POSTGRES.getMappedPort(5432)
                + " sslmode=disable";
        SuiteRunner.Outcome outcome = SuiteRunner.run(SqlIntegrationTest.class, "/db-suite", dir, "postgres.yml",
                Map.of("pg_dsn", dsn));
        assertTrue(outcome.passed(), outcome.describe());
    }

    @Test
    void migratesAndLoadsSqlite(@TempDir Path dir) throws Exception {
        SuiteRunner.Outcome outcome = SuiteRunner.run(SqlIntegrationTest.class, "/db-suite", dir, "sqlite.yml", Map.of());
        assertTrue(outcome.passed(), outcome.describe());
    }
}
