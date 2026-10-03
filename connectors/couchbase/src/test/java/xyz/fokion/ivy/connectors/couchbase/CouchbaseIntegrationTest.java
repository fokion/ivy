package xyz.fokion.ivy.connectors.couchbase;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.couchbase.BucketDefinition;
import org.testcontainers.couchbase.CouchbaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import xyz.fokion.ivy.core.testing.SuiteRunner;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class CouchbaseIntegrationTest {

    @Container
    static final CouchbaseContainer COUCHBASE = new CouchbaseContainer("couchbase/server:community-8.0.2")
            .withBucket(new BucketDefinition("ivy"));

    @Test
    void runsActions(@TempDir Path dir) throws Exception {
        SuiteRunner.Outcome outcome = SuiteRunner.run(CouchbaseIntegrationTest.class, "/couchbase-suite", dir,
                "couchbase.yml", Map.of("cb_dsn", COUCHBASE.getConnectionString(),
                        "cb_user", COUCHBASE.getUsername(), "cb_password", COUCHBASE.getPassword()));
        assertTrue(outcome.passed(), outcome.describe());
    }
}
