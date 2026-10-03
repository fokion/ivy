package xyz.fokion.ivy.connectors.redis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import xyz.fokion.ivy.core.testing.SuiteRunner;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class RedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);

    @Test
    void runsCommands(@TempDir Path dir) throws Exception {
        String url = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        SuiteRunner.Outcome outcome = SuiteRunner.run(RedisIntegrationTest.class, "/redis-suite", dir, "redis.yml",
                Map.of("redis_url", url, "redis", Map.of("dialURL", url)));
        assertTrue(outcome.passed(), outcome.describe());
    }
}
