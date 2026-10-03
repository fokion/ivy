package xyz.fokion.ivy.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Runs the suites of {@code src/test/resources/suites} with ivy in a separate JVM whose working
 * directory is a copy of them. An {@code ivy} script there runs ivy again, for the suite that
 * checks the console output and reports of failing runs. HTTP steps use a local server.
 */
class AcceptanceSuitesTest {

    @TempDir
    static Path dir;

    private static HttpServer server;

    @BeforeAll
    static void setUp() throws IOException, URISyntaxException {
        Path source = Path.of(Objects.requireNonNull(AcceptanceSuitesTest.class.getResource("/suites")).toURI());
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path target = dir.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path script = dir.resolve("ivy");
        Files.writeString(script, "#!/bin/sh\nexec \"" + java() + "\" -cp \"" + System.getProperty("java.class.path")
                + "\" xyz.fokion.ivy.cli.Main \"$@\"\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        startServer();
    }

    @AfterAll
    static void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            ex.getResponseHeaders().add("Content-Type", contentType);
        }
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    private static String json(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json", ex -> respond(ex, 200, "application/json; charset=utf-8",
                "{\"token\":\"abc\",\"echo\":{\"header\":" + json(ex.getRequestHeaders().getFirst("X-Request"))
                        + ",\"query\":" + json(ex.getRequestURI().getRawQuery())
                        + ",\"auth\":" + json(ex.getRequestHeaders().getFirst("Authorization")) + "}}"));
        server.createContext("/echo", ex -> respond(ex, 200, "application/json",
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/json");
            respond(ex, 302, null, "");
        });
        server.createContext("/status", ex -> {
            String[] parts = ex.getRequestURI().getPath().split("/");
            respond(ex, Integer.parseInt(parts[parts.length - 1]), "text/plain", "status");
        });
        server.start();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    record Run(int code, String output) {
    }

    private static Run ivy(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(java(), "-cp", System.getProperty("java.class.path"),
                "xyz.fokion.ivy.cli.Main"));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("IS_TTY", "false");
        pb.environment().put("IVY_VAR_from_env", "env");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out);
    }

    private static List<String> topLevelSuites() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".yml")).sorted().toList();
        }
    }

    @Test
    void suitesPass() throws Exception {
        List<String> args = new ArrayList<>(List.of("run", "--format=xml", "--output-dir=out", "--html-report",
                "--var=array_from_var=[\"biz\",\"buz\"]", "--var-from-file", "vars/vars.yml",
                "--var", "http_url=http://127.0.0.1:" + server.getAddress().getPort()));
        args.addAll(topLevelSuites());
        Run run = ivy(args);
        assertEquals(0, run.code(), run.output());
        assertTrue(run.output().contains("final status: PASS"));
        assertTrue(Files.exists(dir.resolve("out/test_results_exec.xml")));
        assertTrue(Files.exists(dir.resolve("out/test_results.html")));
    }

    @Test
    void suitesInParallelGiveTheSameResults() throws Exception {
        java.util.Map<String, String> reports = new java.util.TreeMap<>();
        for (String parallel : List.of("1", "4")) {
            String out = "out-parallel-" + parallel;
            List<String> args = new ArrayList<>(List.of("run", "--parallel", parallel, "--format=xml", "--output-dir=" + out,
                    "--var=array_from_var=[\"biz\",\"buz\"]", "--var-from-file", "vars/vars.yml",
                    "--var", "http_url=http://127.0.0.1:" + server.getAddress().getPort()));
            args.addAll(topLevelSuites());
            Run run = ivy(args);
            assertEquals(0, run.code(), run.output());
            try (Stream<Path> files = Files.list(dir.resolve(out))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                    // timings differ from run to run, everything else must not
                    String content = Files.readString(f).replaceAll("time=\"[^\"]*\"", "time=\"\"")
                            .replaceAll("timestamp=\"[^\"]*\"", "timestamp=\"\"");
                    String previous = reports.put(f.getFileName().toString(), content);
                    if (previous != null) {
                        assertEquals(previous, content, f.getFileName() + " differs with --parallel " + parallel);
                    }
                }
            }
        }
        assertTrue(reports.size() > 1, reports.keySet().toString());
    }

    @Test
    void failingSuitesFail() throws Exception {
        try (Stream<Path> files = Files.list(dir.resolve("failing"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                Run run = ivy(List.of("run", "--output-dir=out-failing", "--lib-dir=failing/lib", "failing/" + f.getFileName()));
                assertEquals(2, run.code(), f + "\n" + run.output());
                assertTrue(run.output().contains("final status: FAIL"), run.output());
            }
        }
    }

    @Test
    void featuresRun() throws Exception {
        Run run = ivy(List.of("run", "--format=cucumber", "--output-dir=out-features", "--tags", "not @wip",
                "--var", "http_url=http://127.0.0.1:" + server.getAddress().getPort(), "features"));
        assertEquals(0, run.code(), run.output());
        String report = Files.readString(dir.resolve("out-features/test_results_shell.cucumber.json"));
        assertTrue(report.contains("\"name\": \"Shell steps\""), report);
        assertTrue(report.contains("\"name\": \"arithmetic 5 + 5 (example 2)\""), report);
        assertFalse(report.contains("excluded by the tag filter"));
        assertFalse(report.contains("\"failed\""));
        assertTrue(Files.exists(dir.resolve("out-features/test_results_http.cucumber.json")));
    }

    @Test
    void failingFeaturesFail() throws Exception {
        Run undefined = ivy(List.of("run", "--output-dir=out-undefined", "failing/undefined.feature"));
        assertEquals(2, undefined.code(), undefined.output());
        assertTrue(undefined.output().contains("undefined step: \"When I teleport to \"mars\"\""), undefined.output());
        assertTrue(undefined.output().contains("- expression: 'I teleport to {string}'"), undefined.output());

        Run then = ivy(List.of("run", "--format=cucumber", "--output-dir=out-then", "failing/then.feature"));
        assertEquals(2, then.code(), then.output());
        String report = Files.readString(dir.resolve("out-then/test_results_then.cucumber.json"));
        // the failing assertion is reported on the Then step, the When step passed
        int when = report.indexOf("I run \\\"echo a\\\"");
        int thenStep = report.indexOf("the output is \\\"b\\\"");
        assertTrue(when > 0 && thenStep > when, report);
        assertTrue(report.substring(when, thenStep).contains("\"passed\""), report);
        assertTrue(report.substring(thenStep).contains("\"failed\""), report);
    }

    @Test
    void reportsHideSecrets() throws Exception {
        Run run = ivy(List.of("run", "--format=json", "--output-dir=out-secrets", "secrets.yml"));
        assertEquals(0, run.code(), run.output());
        String report = Files.readString(dir.resolve("out-secrets/test_results_secrets.json"));
        assertFalse(report.contains("s3cr3t-value"));
        assertTrue(report.contains("__hidden__"));
    }
}
