package xyz.fokion.ivy.core.connectors;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

public final class ReadfileConfiguration implements Configuration {

    private String path = "";

    public String getPath() {
        return path;
    }

    @ConfigurationProperty(help = "a file, a directory or a glob pattern, relative to the suite")
    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public void validate() {
        if (path.isEmpty()) {
            throw new ConnectorException("Invalid path");
        }
    }
}
