package xyz.fokion.ivy.connectors.sql;

import java.util.List;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

public final class DbFixturesConfiguration implements Configuration {

    private String database = "";
    private String dsn = "";
    private List<String> schemas = List.of();
    private String migrations = "";
    private String migrationsTable = "ivy_migrations";
    private boolean skipResetSequences;
    private List<String> files = List.of();
    private String folder = "";

    @ConfigurationProperty(required = true, help = "postgres, mysql or sqlite")
    public void setDatabase(String database) {
        this.database = database;
    }

    @ConfigurationProperty(required = true, secret = true, help = "a JDBC URL, or a Go driver DSN")
    public void setDsn(String dsn) {
        this.dsn = dsn;
    }

    @ConfigurationProperty(help = "SQL files run before the fixtures; they take precedence over migrations")
    public void setSchemas(List<String> schemas) {
        this.schemas = schemas;
    }

    @ConfigurationProperty(help = "a folder of SQL migrations, applied once each in name order")
    public void setMigrations(String migrations) {
        this.migrations = migrations;
    }

    @ConfigurationProperty(help = "the table recording applied migrations, ivy_migrations by default")
    public void setMigrationsTable(String migrationsTable) {
        this.migrationsTable = migrationsTable;
    }

    @ConfigurationProperty(help = "PostgreSQL: keep the sequences as they are")
    public void setSkipResetSequences(boolean skipResetSequences) {
        this.skipResetSequences = skipResetSequences;
    }

    @ConfigurationProperty(help = "fixture files, used when no folder is set")
    public void setFiles(List<String> files) {
        this.files = files;
    }

    @ConfigurationProperty(help = "a folder of fixtures, one <table>.yml file per table")
    public void setFolder(String folder) {
        this.folder = folder;
    }

    @Override
    public void validate() {
        if (!migrationsTable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ConnectorException("invalid migrationsTable \"" + migrationsTable + "\"");
        }
    }

    String database() {
        return database;
    }

    String dsn() {
        return dsn;
    }

    List<String> schemas() {
        return schemas;
    }

    String migrations() {
        return migrations;
    }

    String migrationsTable() {
        return migrationsTable;
    }

    boolean skipResetSequences() {
        return skipResetSequences;
    }

    List<String> files() {
        return files;
    }

    String folder() {
        return folder;
    }
}
