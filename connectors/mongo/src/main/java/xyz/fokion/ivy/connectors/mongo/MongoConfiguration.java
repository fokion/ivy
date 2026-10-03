package xyz.fokion.ivy.connectors.mongo;

import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

public final class MongoConfiguration implements Configuration {

    private String uri = "";
    private String database = "";
    private String collection = "";
    private List<Map<String, Object>> actions = List.of();

    @ConfigurationProperty(required = true, secret = true, help = "mongodb://...")
    public void setUri(String uri) {
        this.uri = uri;
    }

    @ConfigurationProperty(required = true)
    public void setDatabase(String database) {
        this.database = database;
    }

    @ConfigurationProperty(help = "the collection of the actions, except loadFixtures")
    public void setCollection(String collection) {
        this.collection = collection;
    }

    @ConfigurationProperty(required = true, help = "loadFixtures, insert, find, count, update, delete, aggregate, "
            + "createCollection, dropCollection")
    public void setActions(List<Map<String, Object>> actions) {
        this.actions = actions;
    }

    @Override
    public void validate() {
        if (actions.isEmpty()) {
            throw new ConnectorException("actions is required");
        }
    }

    String uri() {
        return uri;
    }

    String database() {
        return database;
    }

    String collection() {
        return collection;
    }

    List<Map<String, Object>> actions() {
        return actions;
    }
}
