package xyz.fokion.ivy.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import xyz.fokion.ivy.core.model.Status;

/**
 * Parallel suites against a server that answers every request after {@link #LATENCY_MILLIS}: the
 * run waits on the network, not on the CPU, so the speedup does not depend on the machine.
 * Run with {@code ./gradlew :ivy-core:perfTest} (heap limited to 256 MB).
 */
@Tag("perf")
class PerformanceTest {

    static final int SUITES = 100;
    static final int CASES = 10;
    static final int LATENCY_MILLIS = 50;
    static final long HEAP_AFTER_RUN_LIMIT = 128L * 1024 * 1024;

    @Test
    void runsSuitesInParallelFasterAndWithinMemory(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(LATENCY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            List<String> paths = suites(dir, "http://127.0.0.1:" + server.getAddress().getPort());
            double serial = timedRun(dir.resolve("out-1"), paths, 1);
            double parallel = timedRun(dir.resolve("out-32"), paths, 32);
            System.out.printf("%d suites x %d cases, %d ms per request: --parallel 1: %.1f s, --parallel 32: %.1f s (%.1fx)%n",
                    SUITES, CASES, LATENCY_MILLIS, serial, parallel, serial / parallel);
            assertTrue(serial / parallel >= 5, "speedup " + serial / parallel + " is below 5x");

            System.gc();
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            System.out.printf("heap used after the runs: %d MB%n", used / (1024 * 1024));
            assertTrue(used < HEAP_AFTER_RUN_LIMIT, "heap after the runs: " + used / (1024 * 1024) + " MB");
        } finally {
            server.stop(0);
        }
    }

    private static List<String> suites(Path dir, String base) throws IOException {
        List<String> paths = new ArrayList<>();
        for (int s = 0; s < SUITES; s++) {
            StringBuilder yaml = new StringBuilder("name: suite-" + s + "\ntestcases:\n");
            for (int c = 0; c < CASES; c++) {
                yaml.append("- name: case-").append(c).append("\n  steps:\n  - type: http\n    url: ")
                        .append(base).append("/s").append(s).append("/c").append(c).append('\n');
            }
            paths.add(Files.writeString(dir.resolve("suite-" + s + ".yml"), yaml).toString());
        }
        return paths;
    }

    private static double timedRun(Path out, List<String> paths, int parallel) throws Exception {
        Ivy ivy = new Ivy(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)).colors(false)
                .parallel(parallel).outputDir(out.toString());
        ivy.initLogger();
        long start = System.nanoTime();
        try {
            ivy.parse(paths);
            ivy.process();
        } finally {
            ivy.close();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        assertEquals(Status.PASS, ivy.tests().status);
        assertEquals(SUITES, ivy.tests().nbTestsuitesPass);
        return seconds;
    }
}
