package xyz.fokion.ivy.connectors.mongo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.UpdateResult;

import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Actions on a MongoDB database. Filters, documents and pipelines are Extended JSON strings or
 * YAML maps. Result: {@code actions[]}, one entry per action: {@code count}, {@code results},
 * {@code insertedIds}, {@code matchedCount}/{@code modifiedCount}/{@code upsertedId} or
 * {@code deletedCount}. Documents are returned as relaxed Extended JSON.
 */
@ConnectorClass(type = "mongo", configurationClass = MongoConfiguration.class)
public final class MongoConnector implements Connector<MongoConfiguration> {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "actions", "one entry per action: count, results[], insertedIds, matchedCount, modifiedCount, upsertedId, deletedCount");
    }

    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    /** Clients of the test case, by URI. */
    private final Map<String, MongoClient> clients = new HashMap<>();

    @Override
    public void close() {
        clients.values().forEach(MongoClient::close);
        clients.clear();
    }

    @Override
    public Object run(MongoConfiguration config, StepContext context) throws Exception {
        // the client lives as long as the test case: close() closes it
        MongoClient client = clients.computeIfAbsent(config.uri(), MongoClients::create);
        MongoDatabase db = client.getDatabase(config.database());
        List<Object> results = new ArrayList<>();
        for (Map<String, Object> action : config.actions()) {
            String type = String.valueOf(action.get("type"));
            try {
                results.add(apply(type, action, db, config, context.workdir()));
            } catch (MongoException e) {
                throw new ConnectorException(type + " failed: " + e.getMessage(), e);
            }
        }
        return Struct.of("Result", Map.of("actions", results));
    }

    private Map<String, Object> apply(String type, Map<String, Object> action, MongoDatabase db, MongoConfiguration config,
            Path workdir) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (type) {
            case "loadFixtures" -> loadFixtures(db, workdir, string(action.get("folder")));
            case "dropCollection" -> collection(db, config).drop();
            case "createCollection" -> db.createCollection(collectionName(config));
            case "count" -> {
                CountOptions options = new CountOptions();
                Map<String, Object> o = map(action.get("options"));
                if (o.get("limit") instanceof Number n) {
                    options.limit(n.intValue());
                }
                out.put("count", collection(db, config).countDocuments(document(action.get("filter")), options));
            }
            case "insert" -> {
                List<Document> documents = new ArrayList<>();
                if (action.get("file") != null) {
                    for (String json : JsonDocuments.split(Files.readString(workdir.resolve(string(action.get("file")))))) {
                        documents.add(Document.parse(json));
                    }
                }
                if (action.get("documents") instanceof List<?> l) {
                    for (Object d : l) {
                        documents.add(document(d));
                    }
                }
                if (documents.isEmpty()) {
                    throw new ConnectorException("insert needs documents or a file");
                }
                InsertManyResult r = collection(db, config).insertMany(documents);
                List<Object> ids = new ArrayList<>();
                r.getInsertedIds().values().forEach(id -> ids.add(value(id)));
                out.put("insertedIds", ids);
            }
            case "find" -> {
                FindIterable<Document> find = collection(db, config).find(document(action.get("filter")));
                Map<String, Object> o = map(action.get("options"));
                if (o.get("limit") instanceof Number n) {
                    find.limit(n.intValue());
                }
                if (o.get("skip") instanceof Number n) {
                    find.skip(n.intValue());
                }
                if (o.get("sort") != null) {
                    find.sort(document(o.get("sort")));
                }
                if (o.get("projection") != null) {
                    find.projection(document(o.get("projection")));
                }
                out.put("results", documents(find));
            }
            case "update" -> {
                UpdateOptions options = new UpdateOptions();
                if (map(action.get("options")).get("upsert") instanceof Boolean b) {
                    options.upsert(b);
                }
                UpdateResult r = collection(db, config).updateMany(document(action.get("filter")),
                        document(action.get("update")), options);
                out.put("matchedCount", r.getMatchedCount());
                out.put("modifiedCount", r.getModifiedCount());
                out.put("upsertedId", r.getUpsertedId() == null ? null : value(r.getUpsertedId()));
            }
            case "delete" -> {
                DeleteResult r = collection(db, config).deleteMany(document(action.get("filter")));
                out.put("deletedCount", r.getDeletedCount());
            }
            case "aggregate" -> {
                List<Bson> pipeline = new ArrayList<>();
                if (action.get("pipeline") instanceof List<?> l) {
                    for (Object stage : l) {
                        pipeline.add(document(stage));
                    }
                }
                out.put("results", documents(collection(db, config).aggregate(pipeline)));
            }
            default -> throw new ConnectorException("unknown action \"" + type + "\"");
        }
        return out;
    }

    /** Drops every collection, then loads one collection per {@code <name>.yml} file of the folder. */
    private static void loadFixtures(MongoDatabase db, Path workdir, String folder) throws Exception {
        if (folder.isEmpty()) {
            throw new ConnectorException("folder is required");
        }
        for (String name : db.listCollectionNames()) {
            if (!name.startsWith("system.")) {
                db.getCollection(name).drop();
            }
        }
        Load yaml = new Load(LoadSettings.builder().build());
        try (Stream<Path> files = Files.list(workdir.resolve(folder))) {
            for (Path f : files.sorted().toList()) {
                String file = f.getFileName().toString();
                if (Files.isDirectory(f) || !(file.endsWith(".yml") || file.endsWith(".yaml"))) {
                    continue;
                }
                Object loaded = yaml.loadFromString(Files.readString(f));
                if (!(loaded instanceof List<?> items)) {
                    throw new ConnectorException("fixture " + f + " must be a list of documents");
                }
                List<Document> documents = new ArrayList<>();
                for (Object item : items) {
                    documents.add(document(item));
                }
                if (!documents.isEmpty()) {
                    db.getCollection(file.substring(0, file.lastIndexOf('.'))).insertMany(documents);
                }
            }
        }
    }

    private static String collectionName(MongoConfiguration config) {
        if (config.collection().isEmpty()) {
            throw new ConnectorException("collection is required");
        }
        return config.collection();
    }

    private static MongoCollection<Document> collection(MongoDatabase db, MongoConfiguration config) {
        return db.getCollection(collectionName(config));
    }

    /** A document from Extended JSON text or from a map. */
    private static Document document(Object o) {
        if (o == null || "".equals(o)) {
            return new Document();
        }
        String json = o instanceof String s ? s : Json.write(o, Json.COMPACT);
        try {
            return Document.parse(json);
        } catch (RuntimeException e) {
            throw new ConnectorException("invalid document " + json + ": " + e.getMessage(), e);
        }
    }

    private static List<Object> documents(Iterable<Document> docs) {
        List<Object> out = new ArrayList<>();
        for (Document d : docs) {
            out.add(Json.parse(d.toJson(RELAXED)));
        }
        return out;
    }

    /** A BSON value as relaxed Extended JSON. */
    private static Object value(BsonValue v) {
        return Json.parse(new org.bson.BsonDocument("v", v).toJson(RELAXED)) instanceof Map<?, ?> m ? m.get("v") : null;
    }

    private static String string(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
