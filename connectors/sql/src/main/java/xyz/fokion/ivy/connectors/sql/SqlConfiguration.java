package xyz.fokion.ivy.connectors.sql;

import java.util.List;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

public final class SqlConfiguration implements Configuration {

    private String driver = "";
    private String dsn = "";
    private String file = "";
    private List<String> commands = List.of();

    public String getDriver() {
        return driver;
    }

    @ConfigurationProperty(required = true, help = "sqlite, postgres or mysql")
    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getDsn() {
        return dsn;
    }

    @ConfigurationProperty(required = true, secret = true, help = "a JDBC URL, or a venom (Go) DSN")
    public void setDsn(String dsn) {
        this.dsn = dsn;
    }

    public String getFile() {
        return file;
    }

    @ConfigurationProperty(help = "a SQL file, relative to the test suite")
    public void setFile(String file) {
        this.file = file;
    }

    public List<String> getCommands() {
        return commands;
    }

    @ConfigurationProperty(help = "SQL statements, run in order")
    public void setCommands(List<String> commands) {
        this.commands = commands;
    }

    @Override
    public void validate() {
        if (dsn.isEmpty()) {
            throw new ConnectorException("dsn is required");
        }
    }
}
