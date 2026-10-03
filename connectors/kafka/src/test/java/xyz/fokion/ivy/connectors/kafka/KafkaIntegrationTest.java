package xyz.fokion.ivy.connectors.kafka;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import xyz.fokion.ivy.core.testing.SuiteRunner;
import xyz.fokion.ivy.spi.util.Json;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KafkaIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.3.1");

    private static HttpServer registry;

    /** A minimal schema registry: register, latest version and schema by id. */
    @BeforeAll
    static void startRegistry() throws IOException {
        List<String> schemas = new ArrayList<>();
        Map<String, Integer> latest = new java.util.concurrent.ConcurrentHashMap<>();
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            Matcher versions = Pattern.compile("/subjects/([^/]+)/versions(/latest)?").matcher(path);
            Matcher byId = Pattern.compile("/schemas/ids/(\\d+)").matcher(path);
            if (versions.matches() && ex.getRequestMethod().equals("POST")) {
                String schema = String.valueOf(((Map<?, ?>) Json.parse(new String(ex.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8))).get("schema"));
                int id;
                synchronized (schemas) {
                    id = schemas.indexOf(schema) + 1;
                    if (id == 0) {
                        schemas.add(schema);
                        id = schemas.size();
                    }
                }
                latest.put(versions.group(1), id);
                respond(ex, 200, Map.of("id", id));
            } else if (versions.matches() && versions.group(2) != null && latest.containsKey(versions.group(1))) {
                int id = latest.get(versions.group(1));
                respond(ex, 200, Map.of("id", id, "schema", schemas.get(id - 1), "version", 1));
            } else if (byId.matches() && Integer.parseInt(byId.group(1)) <= schemas.size()) {
                respond(ex, 200, Map.of("schema", schemas.get(Integer.parseInt(byId.group(1)) - 1)));
            } else {
                respond(ex, 404, Map.of("error_code", 40401, "message", "not found"));
            }
        });
        registry.start();
    }

    @AfterAll
    static void stopRegistry() {
        registry.stop(0);
    }

    private static void respond(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] b = Json.write(body, Json.COMPACT).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/vnd.schemaregistry.v1+json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void producesAndConsumes(@TempDir Path dir) throws Exception {
        SuiteRunner.Outcome outcome = SuiteRunner.run(KafkaIntegrationTest.class, "/kafka-suite", dir, "kafka.yml",
                Map.of("kafka", KAFKA.getBootstrapServers(),
                        "registry", "http://127.0.0.1:" + registry.getAddress().getPort()));
        assertTrue(outcome.passed(), outcome.describe());
    }
}
