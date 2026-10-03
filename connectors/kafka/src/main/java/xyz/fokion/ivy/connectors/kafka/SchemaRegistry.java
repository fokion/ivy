package xyz.fokion.ivy.connectors.kafka;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.util.Json;

/** A client of the Confluent Schema Registry REST API, over the JDK HTTP client. */
final class SchemaRegistry {

    private static final String CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";

    /** A registered schema. */
    record Schema(int id, String schema) {
    }

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Integer, String> byId = new ConcurrentHashMap<>();

    SchemaRegistry(String address) {
        String a = address.contains("://") ? address : "http://" + address;
        this.base = a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
    }

    /** Registers a schema for a subject, or returns the id of the same schema already registered. */
    int register(String subject, String schema) {
        Map<String, Object> resp = call(HttpRequest.newBuilder(uri("/subjects/" + encode(subject) + "/versions"))
                .header("Content-Type", CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("schema", schema), Json.COMPACT))));
        int id = ((Number) resp.get("id")).intValue();
        byId.put(id, schema);
        return id;
    }

    Schema latest(String subject) {
        Map<String, Object> resp = call(HttpRequest.newBuilder(uri("/subjects/" + encode(subject) + "/versions/latest")).GET());
        Schema s = new Schema(((Number) resp.get("id")).intValue(), String.valueOf(resp.get("schema")));
        byId.put(s.id(), s.schema());
        return s;
    }

    String byId(int id) {
        return byId.computeIfAbsent(id,
                i -> String.valueOf(call(HttpRequest.newBuilder(uri("/schemas/ids/" + i)).GET()).get("schema")));
    }

    private URI uri(String path) {
        return URI.create(base + path);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(HttpRequest.Builder request) {
        HttpResponse<String> resp;
        try {
            resp = http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ConnectorException("schema registry: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("schema registry: interrupted", e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ConnectorException("schema registry: " + resp.request().uri().getPath() + " answered "
                    + resp.statusCode() + ": " + resp.body());
        }
        return (Map<String, Object>) Json.parse(resp.body());
    }
}
