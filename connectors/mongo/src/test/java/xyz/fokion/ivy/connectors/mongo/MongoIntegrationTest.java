package xyz.fokion.ivy.connectors.mongo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import xyz.fokion.ivy.core.testing.SuiteRunner;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.2");

    @Test
    void runsActions(@TempDir Path dir) throws Exception {
        SuiteRunner.Outcome outcome = SuiteRunner.run(MongoIntegrationTest.class, "/mongo-suite", dir, "mongo.yml",
                Map.of("mongo", MONGO.getReplicaSetUrl()));
        assertTrue(outcome.passed(), outcome.describe());
    }
}
