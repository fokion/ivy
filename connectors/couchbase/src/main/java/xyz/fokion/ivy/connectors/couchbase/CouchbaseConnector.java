package xyz.fokion.ivy.connectors.couchbase;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.DocumentExistsException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.codec.JsonTranscoder;
import com.couchbase.client.java.codec.LegacyTranscoder;
import com.couchbase.client.java.codec.RawBinaryTranscoder;
import com.couchbase.client.java.codec.RawJsonTranscoder;
import com.couchbase.client.java.codec.RawStringTranscoder;
import com.couchbase.client.java.codec.Transcoder;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.ReplaceOptions;
import com.couchbase.client.java.kv.UpsertOptions;
import com.couchbase.client.java.query.QueryOptions;

import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Key-value actions and SQL++ queries on Couchbase. Each action may set its own {@code bucket},
 * {@code scope}, {@code collection} and {@code expiry}.
 * <p>
 * Result: {@code actions[]}, one entry per action, keyed by document id for key-value actions:
 * get {@code found}, {@code data}, {@code expiry}; insert {@code inserted}; upsert
 * {@code upserted}; replace {@code replaced}; delete {@code deleted}; touch {@code touched};
 * exists {@code found}. A query gives {@code results[]}.
 */
@ConnectorClass(type = "couchbase", configurationClass = CouchbaseConfiguration.class)
public final class CouchbaseConnector implements Connector<CouchbaseConfiguration> {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "actions", "one entry per action: found, data, expiry for key-value actions, results[] for queries");
    }

    /** Clusters of the test case, by connection string and user. */
    private final Map<String, Cluster> clusters = new HashMap<>();
    private final Map<String, Bucket> readyBuckets = new HashMap<>();

    @Override
    public void close() {
        clusters.values().forEach(Cluster::disconnect);
        clusters.clear();
        readyBuckets.clear();
    }

    @Override
    public Object run(CouchbaseConfiguration c, StepContext context) {
        Cluster cluster = clusters.computeIfAbsent(c.dsn() + "|" + c.username(),
                _ -> Cluster.connect(c.dsn(), ClusterOptions.clusterOptions(c.username(), c.password())));
        List<Object> results = new ArrayList<>();
        for (Map<String, Object> action : c.actions()) {
            String type = string(action.get("type"));
            try {
                results.add(type.equals("query") ? query(cluster, action) : keyValue(type, action, c, cluster));
            } catch (CouchbaseException e) {
                throw new ConnectorException(type + " failed: " + e.getMessage(), e);
            }
        }
        return Struct.of("Result", Map.of("actions", results));
    }

    private Map<String, Object> keyValue(String type, Map<String, Object> action, CouchbaseConfiguration c, Cluster cluster) {
        Collection collection = collection(action, c, cluster);
        Transcoder transcoder = transcoder(string(action.getOrDefault("transcoder", c.transcoder())));
        Duration expiry = expiry(action.get("expiry"), c.expiry());
        Map<String, Object> out = new LinkedHashMap<>();
        switch (type) {
            case "get" -> {
                boolean withExpiry = Boolean.TRUE.equals(action.get("with_expiry")) || Boolean.TRUE.equals(action.get("withExpiry"));
                for (String id : ids(action)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    try {
                        GetResult g = action.get("expiry") != null
                                ? collection.getAndTouch(id, expiry, com.couchbase.client.java.kv.GetAndTouchOptions
                                        .getAndTouchOptions().transcoder(transcoder))
                                : collection.get(id, GetOptions.getOptions().withExpiry(withExpiry).transcoder(transcoder));
                        r.put("found", true);
                        r.put("data", content(g, transcoder));
                        if (withExpiry) {
                            r.put("expiry", g.expiryTime().map(java.time.Instant::getEpochSecond).orElse(0L));
                        }
                    } catch (DocumentNotFoundException e) {
                        r.put("found", false);
                    }
                    out.put(id, r);
                }
            }
            case "insert" -> entries(action).forEach((id, value) -> {
                boolean inserted = true;
                try {
                    InsertOptions o = InsertOptions.insertOptions().transcoder(transcoder);
                    if (expiry != null) {
                        o.expiry(expiry);
                    }
                    collection.insert(id, encode(value, transcoder), o);
                } catch (DocumentExistsException e) {
                    inserted = false;
                }
                out.put(id, Map.of("inserted", inserted));
            });
            case "upsert" -> entries(action).forEach((id, value) -> {
                UpsertOptions o = UpsertOptions.upsertOptions().transcoder(transcoder).preserveExpiry(preserve(action));
                if (expiry != null) {
                    o.expiry(expiry);
                }
                collection.upsert(id, encode(value, transcoder), o);
                out.put(id, Map.of("upserted", true));
            });
            case "replace" -> entries(action).forEach((id, value) -> {
                boolean replaced = true;
                try {
                    ReplaceOptions o = ReplaceOptions.replaceOptions().transcoder(transcoder).preserveExpiry(preserve(action));
                    if (expiry != null) {
                        o.expiry(expiry);
                    }
                    collection.replace(id, encode(value, transcoder), o);
                } catch (DocumentNotFoundException e) {
                    replaced = false;
                }
                out.put(id, Map.of("replaced", replaced));
            });
            case "delete" -> {
                for (String id : ids(action)) {
                    boolean deleted = true;
                    try {
                        collection.remove(id);
                    } catch (DocumentNotFoundException e) {
                        deleted = false;
                    }
                    out.put(id, Map.of("deleted", deleted));
                }
            }
            case "touch" -> {
                if (expiry == null) {
                    throw new ConnectorException("touch needs an expiry");
                }
                for (String id : ids(action)) {
                    boolean touched = true;
                    try {
                        collection.touch(id, expiry);
                    } catch (DocumentNotFoundException e) {
                        touched = false;
                    }
                    out.put(id, Map.of("touched", touched));
                }
            }
            case "exists" -> {
                for (String id : ids(action)) {
                    out.put(id, Map.of("found", collection.exists(id).exists()));
                }
            }
            default -> throw new ConnectorException("action type \"" + type + "\" not supported");
        }
        return out;
    }

    private static Map<String, Object> query(Cluster cluster, Map<String, Object> action) {
        String statement = string(action.get("statement"));
        if (statement.isEmpty()) {
            throw new ConnectorException("query needs a statement");
        }
        QueryOptions options = QueryOptions.queryOptions();
        Object parameters = action.get("parameters");
        if (parameters instanceof Map<?, ?> m) {
            options.parameters(JsonObject.fromJson(Json.write(m, Json.COMPACT)));
        } else if (parameters instanceof List<?> l) {
            options.parameters(JsonArray.fromJson(Json.write(l, Json.COMPACT)));
        }
        List<Object> rows = new ArrayList<>();
        for (JsonObject row : cluster.query(statement, options).rowsAsObject()) {
            rows.add(Json.parse(row.toString()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", rows);
        return out;
    }

    private Collection collection(Map<String, Object> action, CouchbaseConfiguration c, Cluster cluster) {
        String bucketName = string(action.getOrDefault("bucket", c.bucket()));
        if (bucketName.isEmpty()) {
            throw new ConnectorException("bucket is required");
        }
        Bucket bucket = readyBuckets.computeIfAbsent(c.dsn() + "|" + bucketName, _ -> {
            Bucket b = cluster.bucket(bucketName);
            if (c.waitUntilReadyTimeout() > 0) {
                b.waitUntilReady(Duration.ofMillis((long) (c.waitUntilReadyTimeout() * 1000)));
            }
            return b;
        });
        return bucket.scope(string(action.getOrDefault("scope", c.scope())))
                .collection(string(action.getOrDefault("collection", c.collection())));
    }

    private static Transcoder transcoder(String name) {
        return switch (name) {
            case "json", "" -> JsonTranscoder.create(com.couchbase.client.java.codec.DefaultJsonSerializer.create());
            case "rawjson" -> RawJsonTranscoder.INSTANCE;
            case "rawstring" -> RawStringTranscoder.INSTANCE;
            case "raw" -> RawBinaryTranscoder.INSTANCE;
            case "legacy" -> LegacyTranscoder.create(com.couchbase.client.java.codec.DefaultJsonSerializer.create());
            default -> throw new ConnectorException("unknown transcoder \"" + name + "\"");
        };
    }

    /** A value in the form its transcoder accepts. */
    private static Object encode(Object value, Transcoder transcoder) {
        String text = value instanceof String s ? s : Json.write(value, Json.COMPACT);
        if (transcoder instanceof RawStringTranscoder) {
            return text;
        }
        if (transcoder instanceof RawBinaryTranscoder || transcoder instanceof RawJsonTranscoder) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if (value instanceof Map<?, ?> m) {
            return JsonObject.fromJson(Json.write(m, Json.COMPACT));
        }
        if (value instanceof List<?> l) {
            return JsonArray.fromJson(Json.write(l, Json.COMPACT));
        }
        return value;
    }

    private static Object content(GetResult g, Transcoder transcoder) {
        if (transcoder instanceof RawStringTranscoder) {
            return g.contentAs(String.class);
        }
        if (transcoder instanceof RawBinaryTranscoder || transcoder instanceof RawJsonTranscoder) {
            String text = new String(g.contentAsBytes(), StandardCharsets.UTF_8);
            Object parsed = Json.tryParse(text);
            return parsed == null ? text : parsed;
        }
        byte[] raw = g.contentAsBytes();
        Object parsed = Json.tryParse(new String(raw, StandardCharsets.UTF_8));
        return parsed == null ? new String(raw, StandardCharsets.UTF_8) : parsed;
    }

    private static Duration expiry(Object actionExpiry, Double defaultExpiry) {
        Object e = actionExpiry != null ? actionExpiry : defaultExpiry;
        if (e == null) {
            return null;
        }
        double seconds = e instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(e));
        return seconds <= 0 ? null : Duration.ofMillis((long) (seconds * 1000));
    }

    private static boolean preserve(Map<String, Object> action) {
        return Boolean.TRUE.equals(action.get("preserve_expiry")) || Boolean.TRUE.equals(action.get("preserveExpiry"));
    }

    private static List<String> ids(Map<String, Object> action) {
        if (!(action.get("ids") instanceof List<?> l) || l.isEmpty()) {
            throw new ConnectorException("ids is required");
        }
        return l.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entries(Map<String, Object> action) {
        if (!(action.get("entries") instanceof Map<?, ?> m) || m.isEmpty()) {
            throw new ConnectorException("entries is required");
        }
        return (Map<String, Object>) m;
    }

    private static String string(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
