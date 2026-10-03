package xyz.fokion.ivy.connectors.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import com.sun.net.httpserver.HttpServer;

import xyz.fokion.ivy.core.engine.Ivy;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;

/**
 * The browser connector against a Playwright server in a container, browsing pages served by
 * the test.
 * <pre>
 *  test ── Ivy ── BrowserConnector ── BrowserHosts ══ ws ══► container: playwright run-server
 *                                                                │ chromium
 *  HttpServer (site/, /meet) ◄──── host.testcontainers.internal ─┘
 * </pre>
 * The server image is built from Docker Hub images (playwright-server/Dockerfile), or taken from
 * {@code -Dplaywright.server.image}, such as {@code mcr.microsoft.com/playwright:v<version>-noble}.
 */
@Tag("integration")
class BrowserIntegrationTest {

    static final String VERSION = System.getProperty("playwright.version");

    private static HttpServer site;
    private static GenericContainer<?> server;
    private static Map<String, Object> vars;
    private static volatile CyclicBarrier meeting;

    @BeforeAll
    static void start() throws Exception {
        site = HttpServer.create(new InetSocketAddress(0), 0);
        site.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Path pages = Path.of(Objects.requireNonNull(BrowserIntegrationTest.class.getResource("/browser-suite/site")).toURI());
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            String type = "text/html";
            if (path.equals("/meet")) {
                // answers once two browsers are here
                try {
                    meeting.await(10, TimeUnit.SECONDS);
                    body = "met".getBytes(StandardCharsets.UTF_8);
                } catch (Exception e) {
                    body = "alone".getBytes(StandardCharsets.UTF_8);
                }
                type = "text/plain";
            } else {
                Path file = pages.resolve(path.substring(1)).normalize();
                if (file.startsWith(pages) && Files.isRegularFile(file)) {
                    body = Files.readAllBytes(file);
                } else {
                    status = 404;
                    body = new byte[0];
                }
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        site.start();
        int port = site.getAddress().getPort();
        Testcontainers.exposeHostPorts(port);

        String image = System.getProperty("playwright.server.image", "");
        server = image.isEmpty()
                ? new GenericContainer<>(new ImageFromDockerfile("ivy-playwright-server:" + VERSION, false)
                        .withFileFromClasspath("Dockerfile", "playwright-server/Dockerfile")
                        .withBuildArg("PLAYWRIGHT_VERSION", VERSION))
                : new GenericContainer<>(image).withCommand("npx", "-y", "playwright@" + VERSION, "run-server",
                        "--port", "3000", "--host", "0.0.0.0");
        server.withExposedPorts(3000)
                .waitingFor(Wait.forLogMessage(".*Listening on.*", 1).withStartupTimeout(Duration.ofMinutes(10)))
                .start();
        vars = Map.of("pw_ws", "ws://" + server.getHost() + ":" + server.getMappedPort(3000) + "/",
                "base", "http://host.testcontainers.internal:" + port);
    }

    @AfterAll
    static void stop() {
        BrowserHosts.closeAll();
        if (server != null) {
            server.stop();
        }
        if (site != null) {
            site.stop(0);
        }
    }

    @BeforeEach
    void freshHosts() {
        BrowserHosts.closeAll();
        meeting = new CyclicBarrier(2);
    }

    private record Run(Ivy ivy, String console, Path out) {
        TestCase testCase(String name) {
            return ivy.tests().testSuites.stream().flatMap(ts -> ts.testCases.stream())
                    .filter(tc -> tc.originalName.equals(name)).findFirst().orElseThrow();
        }

        String everything() throws IOException {
            StringBuilder sb = new StringBuilder(console);
            try (Stream<Path> files = Files.walk(out)) {
                for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.toString().endsWith(".png")).toList()) {
                    sb.append(Files.readString(f));
                }
            }
            return sb.toString();
        }
    }

    private static Run run(Path dir, int parallel, String... suites) throws Exception {
        Path source = Path.of(Objects.requireNonNull(BrowserIntegrationTest.class.getResource("/browser-suite")).toURI());
        for (String suite : suites) {
            Files.copy(source.resolve(suite), dir.resolve(suite), StandardCopyOption.REPLACE_EXISTING);
        }
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Path out = dir.resolve("out");
        Ivy ivy = new Ivy(new PrintStream(console, true, StandardCharsets.UTF_8)).colors(false).verbose(1)
                .parallel(parallel).outputDir(out.toString()).htmlReport(true);
        ivy.initLogger();
        ivy.addVariables(vars);
        try {
            ivy.parse(Stream.of(suites).map(s -> dir.resolve(s).toString()).toList());
            ivy.process();
            xyz.fokion.ivy.core.engine.Outputs.write(ivy);
        } finally {
            ivy.close();
        }
        return new Run(ivy, console.toString(StandardCharsets.UTF_8), out);
    }

    @Test
    void browsesAcrossStepsWithAFreshContextPerTestCaseAndHidesSecrets(@TempDir Path dir) throws Exception {
        Run run = run(dir, 1, "browser.yml");
        assertEquals(Status.PASS, run.ivy().tests().status, run.console());
        assertTrue(Files.exists(dir.resolve("out/shots/home.png")), "the screenshot is in the output directory");
        String all = run.everything();
        assertFalse(all.contains("pa55-SECRET-word"), "the password leaked");
        // the test cases of a suite share one browser, each in a context of its own
        assertEquals(1, BrowserHosts.open());
    }

    @Test
    void reportsFailedActionsWithAScreenshotAndRecoversFromTimeouts(@TempDir Path dir) throws Exception {
        long start = System.nanoTime();
        Run run = run(dir, 1, "failing.yml");
        assertEquals(Status.FAIL, run.testCase("missing button").status);
        String missing = String.join("\n", run.testCase("missing button").testStepResults.getFirst().errorList()
                .stream().map(f -> f.value).toList());
        assertTrue(missing.contains("action #2 (click):"), missing);
        try (Stream<Path> files = Files.list(dir.resolve("out"))) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().equals("failing-missing_button-step1-failure.png")),
                    "a screenshot of the failure");
        }
        assertEquals(Status.FAIL, run.testCase("step timeout").status);
        assertTrue(run.testCase("step timeout").testStepResults.getFirst().errorList().getFirst().value
                .contains("Timeout after 2 second(s)"));
        assertEquals(Status.PASS, run.testCase("still works").status, run.console());
        assertTrue((System.nanoTime() - start) / 1e9 < 20, "the timed out step did not hold the run");
    }

    @Test
    void parallelSuitesUseBrowsersOfTheirOwn(@TempDir Path dir) throws Exception {
        Run run = run(dir, 2, "meet.yml", "meet2.yml");
        assertEquals(Status.PASS, run.ivy().tests().status, run.console());
        assertEquals(2, BrowserHosts.open());
    }

    @Test
    void replacesABrowserThatIsGone(@TempDir Path dir) throws Exception {
        assertEquals(Status.PASS, run(dir, 1, "browser.yml").ivy().tests().status);
        BrowserHosts.Host idle = BrowserHosts.idle().getFirst();
        idle.call(() -> {
            idle.browser().close();
            return null;
        });
        Run again = run(dir, 1, "browser.yml");
        assertEquals(Status.PASS, again.ivy().tests().status, again.console());
        assertEquals(1, BrowserHosts.open(), "the closed browser was replaced, not kept");
    }

    @Test
    void closesBrowsersAndTheirDriversOnShutdown(@TempDir Path dir) throws Exception {
        assertEquals(Status.PASS, run(dir, 2, "meet.yml", "meet2.yml").ivy().tests().status);
        BrowserHosts.closeAll();
        assertEquals(0, BrowserHosts.open());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (drivers() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(0, drivers(), "no Node driver process left");
    }

    /** The Playwright driver processes started by this JVM. */
    private static long drivers() {
        return ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .filter(p -> p.info().commandLine().orElse("").contains("playwright")).count();
    }
}
