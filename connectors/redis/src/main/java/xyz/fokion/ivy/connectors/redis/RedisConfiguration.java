package xyz.fokion.ivy.connectors.redis;

import java.util.List;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

public final class RedisConfiguration implements Configuration {

    private String dialUrl = "";
    private List<String> commands = List.of();
    private String path = "";

    @ConfigurationProperty(name = "dialURL", secret = true,
            help = "redis://[user:password@]host:port[/db], or the variable redis.dialURL")
    public void setDialUrl(String dialUrl) {
        this.dialUrl = dialUrl;
    }

    @ConfigurationProperty(help = "commands, one per item, e.g. SET key \"a value\"")
    public void setCommands(List<String> commands) {
        this.commands = commands;
    }

    @ConfigurationProperty(help = "a file of commands, one per line")
    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public void validate() {
        if (commands.isEmpty() && path.isEmpty()) {
            throw new ConnectorException("commands or path is required");
        }
    }

    String dialUrl() {
        return dialUrl;
    }

    List<String> commands() {
        return commands;
    }

    String path() {
        return path;
    }
}
