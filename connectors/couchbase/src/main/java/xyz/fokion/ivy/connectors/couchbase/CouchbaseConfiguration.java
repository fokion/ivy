package xyz.fokion.ivy.connectors.couchbase;

import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

public final class CouchbaseConfiguration implements Configuration {

    private String dsn = "couchbase://localhost";
    private String username = "";
    private String password = "";
    private String bucket = "";
    private String scope = "_default";
    private String collection = "_default";
    private String transcoder = "json";
    private Double expiry;
    private double waitUntilReadyTimeout = 10;
    private List<Map<String, Object>> actions = List.of();

    @ConfigurationProperty(help = "connection string, couchbase://localhost by default")
    public void setDsn(String dsn) {
        this.dsn = dsn;
    }

    @ConfigurationProperty
    public void setUsername(String username) {
        this.username = username;
    }

    @ConfigurationProperty(secret = true)
    public void setPassword(String password) {
        this.password = password;
    }

    @ConfigurationProperty(help = "the default bucket of the actions")
    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    @ConfigurationProperty
    public void setScope(String scope) {
        this.scope = scope;
    }

    @ConfigurationProperty
    public void setCollection(String collection) {
        this.collection = collection;
    }

    @ConfigurationProperty(help = "json (default), rawjson, rawstring, raw or legacy")
    public void setTranscoder(String transcoder) {
        this.transcoder = transcoder;
    }

    @ConfigurationProperty(help = "default expiry of written documents, in seconds")
    public void setExpiry(Double expiry) {
        this.expiry = expiry;
    }

    @ConfigurationProperty(help = "seconds to wait for the bucket to be ready, 0 to skip")
    public void setWaitUntilReadyTimeout(double waitUntilReadyTimeout) {
        this.waitUntilReadyTimeout = waitUntilReadyTimeout;
    }

    @ConfigurationProperty(required = true, help = "get, insert, upsert, replace, delete, touch, exists, query")
    public void setActions(List<Map<String, Object>> actions) {
        this.actions = actions;
    }

    @Override
    public void validate() {
        if (actions.isEmpty()) {
            throw new ConnectorException("actions is required");
        }
    }

    String dsn() {
        return dsn;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    String bucket() {
        return bucket;
    }

    String scope() {
        return scope;
    }

    String collection() {
        return collection;
    }

    String transcoder() {
        return transcoder;
    }

    Double expiry() {
        return expiry;
    }

    double waitUntilReadyTimeout() {
        return waitUntilReadyTimeout;
    }

    List<Map<String, Object>> actions() {
        return actions;
    }
}
