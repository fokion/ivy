package xyz.fokion.ivy.cli.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.fokion.ivy.cli.Cli;
import xyz.fokion.ivy.core.engine.Ivy;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.spi.util.Json;

/**
 * The MCP server driven through piped streams, as a client does.
 * <pre>
 *  test ── Client.request ──► pipe ──► McpServer.serve ──► Tools ──► Ivy
 *       ◄─ Client.next ◄── lines ◄────── stdout (one JSON message per line)
 * </pre>
 */
class McpServerTest {

    @TempDir
    Path workspace;

    private Client client;

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    /** A client of a server running on another thread. */
    private static final class Client implements AutoCloseable {
        private final PipedOutputStream toServer = new PipedOutputStream();
        private final BlockingQueue<Map<String, Object>> fromServer = new LinkedBlockingQueue<>();
        private final List<Map<String, Object>> notifications = new ArrayList<>();
        private final ByteArrayOutputStream log = new ByteArrayOutputStream();
        private final Thread thread;
        private long nextId = 1;

        Client(Path workspace, boolean noRun) throws IOException {
            PipedInputStream in = new PipedInputStream(toServer, 1 << 16);
            McpServer server = new McpServer(console -> new Ivy(console).colors(false), workspace, noRun, List.of(),
                    new PrintStream(log, true, StandardCharsets.UTF_8));
            PrintStream out = new PrintStream(new LineSplitter(fromServer), true, StandardCharsets.UTF_8);
            thread = Thread.ofVirtual().start(() -> {
                try {
                    server.serve(in, out);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        void sendRaw(String line) throws IOException {
            toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            toServer.flush();
        }

        long send(String method, Map<String, Object> params) throws IOException {
            long id = nextId++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jsonrpc", "2.0");
            m.put("id", id);
            m.put("method", method);
            m.put("params", params);
            sendRaw(Json.write(m, Json.COMPACT));
            return id;
        }

        void notify(String method, Map<String, Object> params) throws IOException {
            sendRaw(Json.write(Map.of("jsonrpc", "2.0", "method", method, "params", params), Json.COMPACT));
        }

        /** The answer to a request; notifications received meanwhile are kept. */
        Map<String, Object> response(long id) throws InterruptedException {
            while (true) {
                Map<String, Object> m = fromServer.poll(20, TimeUnit.SECONDS);
                assertNotNull(m, "no answer to request " + id);
                if (m.containsKey("method")) {
                    notifications.add(m);
                } else {
                    assertEquals(id, Cast.toLong(m.get("id")), "answers come in request order here: " + m);
                    return m;
                }
            }
        }

        Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
            return response(send(method, params));
        }

        /** The result of a tool call. */
        Map<String, Object> tool(String name, Map<String, Object> args) throws Exception {
            Map<String, Object> r = request("tools/call", Map.of("name", name, "arguments", args));
            assertNull(r.get("error"), String.valueOf(r.get("error")));
            return Cast.toStringMap(r.get("result"));
        }

        String log() {
            return log.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws Exception {
            toServer.close();
            thread.join(20_000);
            assertFalse(thread.isAlive(), "the server returns at the end of its input");
        }
    }

    /** Splits what the server writes into JSON messages. */
    private static final class LineSplitter extends OutputStream {
        private final BlockingQueue<Map<String, Object>> queue;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineSplitter(BlockingQueue<Map<String, Object>> queue) {
            this.queue = queue;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                queue.add(Cast.toStringMap(Json.parse(line.toString(StandardCharsets.UTF_8))));
                line.reset();
            } else {
                line.write(b);
            }
        }
    }

    private static Map<String, Object> structured(Map<String, Object> result) {
        return Cast.toStringMap(result.get("structuredContent"));
    }

    private static boolean isError(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get("isError"));
    }

    private static String text(Map<String, Object> result) {
        return Cast.toString(Cast.toStringMap(((List<?>) result.get("content")).getFirst()).get("text"));
    }

    private Client client() throws IOException {
        client = new Client(workspace, false);
        return client;
    }

    // ------------------------------------------------------------ protocol

    @Test
    void negotiatesTheProtocolVersion() throws Exception {
        Client c = client();
        Map<String, Object> r = Cast.toStringMap(c.request("initialize",
                Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1"))).get("result"));
        assertEquals("2025-06-18", r.get("protocolVersion"));
        assertTrue(Cast.toStringMap(r.get("capabilities")).containsKey("tools"));
        assertEquals("ivy", Cast.toStringMap(r.get("serverInfo")).get("name"));
        assertTrue(Cast.toString(r.get("instructions")).contains("write_suite"));

        Map<String, Object> unknown = Cast.toStringMap(c.request("initialize",
                Map.of("protocolVersion", "1999-01-01")).get("result"));
        assertEquals(McpServer.PROTOCOL_VERSIONS.getFirst(), unknown.get("protocolVersion"));
    }

    @Test
    void answersErrorsAsJsonRpc() throws Exception {
        Client c = client();
        c.sendRaw("{not json");
        Map<String, Object> parse = c.fromServer.poll(10, TimeUnit.SECONDS);
        assertNotNull(parse);
        assertEquals((long) McpServer.PARSE_ERROR, Cast.toLong(Cast.toStringMap(parse.get("error")).get("code")));

        Map<String, Object> unknown = c.request("nope/nothing", Map.of());
        assertEquals((long) McpServer.METHOD_NOT_FOUND, Cast.toLong(Cast.toStringMap(unknown.get("error")).get("code")));

        // a notification is never answered: the next message is the answer to ping
        c.notify("notifications/initialized", Map.of());
        assertEquals(Map.of(), c.request("ping", Map.of()).get("result"));

        Map<String, Object> noTool = c.request("tools/call", Map.of("name", "nope", "arguments", Map.of()));
        assertEquals((long) McpServer.INVALID_PARAMS, Cast.toLong(Cast.toStringMap(noTool.get("error")).get("code")));
    }

    @Test
    void listsToolsAndHidesRunWithNoRun() throws Exception {
        Client c = client();
        List<String> names = toolNames(c);
        assertEquals(List.of("list_step_types", "describe_syntax", "validate_suite", "write_suite", "run_suite",
                "evaluate_expression", "list_step_definitions", "gherkin_steps"), names);
        c.close();

        client = new Client(workspace, true);
        assertFalse(toolNames(client).contains("run_suite"));
        Map<String, Object> run = client.request("tools/call", Map.of("name", "run_suite", "arguments", Map.of("path", "x.yml")));
        assertEquals((long) McpServer.INVALID_PARAMS, Cast.toLong(Cast.toStringMap(run.get("error")).get("code")));
    }

    private static List<String> toolNames(Client c) throws Exception {
        List<?> tools = (List<?>) Cast.toStringMap(c.request("tools/list", Map.of()).get("result")).get("tools");
        List<String> names = new ArrayList<>();
        for (Object t : tools) {
            Map<String, Object> m = Cast.toStringMap(t);
            names.add(Cast.toString(m.get("name")));
            assertTrue(m.containsKey("inputSchema") && m.containsKey("annotations"), m.toString());
        }
        return names;
    }

    // ------------------------------------------------------------ knowledge

    @Test
    void listsStepTypesWithTheirResultFieldsAndAnExample() throws Exception {
        Map<String, Object> r = client().tool("list_step_types", Map.of());
        assertFalse(isError(r));
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        for (Object t : (List<?>) structured(r).get("stepTypes")) {
            Map<String, Object> m = Cast.toStringMap(t);
            byType.put(Cast.toString(m.get("type")), m);
        }
        assertTrue(byType.keySet().containsAll(List.of("exec", "http", "readfile")), byType.keySet().toString());
        assertTrue(Cast.toStringMap(byType.get("http").get("resultFields")).containsKey("status"));
        assertTrue(Cast.toStringMap(byType.get("exec").get("resultFields")).containsKey("exitCode"));
        assertTrue(Cast.toString(byType.get("http").get("example")).startsWith("- type: http\n"));
        // Java types are shown without their package
        assertTrue(Json.write(byType.get("http").get("properties")).contains("\"type\":\"String\""),
                Json.write(byType.get("http").get("properties")));
    }

    @Test
    void describesTheSyntax() throws Exception {
        Map<String, Object> s = structured(client().tool("describe_syntax", Map.of()));
        assertTrue(Cast.toString(s.get("expressions")).contains("${"), "docs/expressions.md is bundled");
        assertTrue(Cast.toString(s.get("secrets")).toLowerCase().contains("secret"));
        assertTrue(Cast.toString(s.get("suite")).contains("testcases:"));
        assertTrue(Cast.toString(s.get("builtins")).contains("cases.<test case>"));
    }

    @Test
    void evaluatesExpressionsAgainstTheGivenScopeOnly() throws Exception {
        Client c = client();
        Map<String, Object> ok = c.tool("evaluate_expression", Map.of("expression", "result.items.length > 1",
                "scope", Map.of("result", Map.of("items", List.of(1, 2, 3)))));
        assertEquals(true, structured(ok).get("value"));
        assertEquals("x-2", structured(c.tool("evaluate_expression",
                Map.of("expression", "${a}-${b}", "scope", Map.of("a", "x", "b", 2)))).get("value"));

        Map<String, Object> bad = c.tool("evaluate_expression", Map.of("expression", "1 +"));
        assertTrue(isError(bad));

        Map<String, Object> env = c.tool("evaluate_expression", Map.of("expression", "env.HOME"));
        String home = System.getenv("HOME");
        assertFalse(home != null && Json.write(env).contains(home), "the environment is not visible: " + env);
    }

    // ------------------------------------------------------------ validate and write

    private static final String VALID = """
            name: valid
            testcases:
            - name: hello
              steps:
              - script: echo hello
                assertions:
                - result.stdout == "hello"
            """;

    @Test
    void validatesSuitesReportingOneErrorPerTestCase() throws Exception {
        Files.writeString(workspace.resolve("ok.yml"), VALID);
        Files.writeString(workspace.resolve("broken.yml"), """
                name: broken
                testcases:
                - name: one
                  steps:
                  - script: echo ${nope(}
                - name: two
                  steps:
                  - type: unknown-type
                - name: three
                  steps:
                  - script: echo ok
                    assertions:
                    - result.stdout ==
                """);
        Files.writeString(workspace.resolve("typo.yml"), """
                name: typo
                testcases:
                - name: one
                  steps:
                  - script: echo ok
                    assertions:
                    - result.stdou == "ok"
                """);
        Client c = client();
        Map<String, Object> ok = c.tool("validate_suite", Map.of("path", "ok.yml"));
        assertFalse(isError(ok), text(ok));
        assertEquals(List.of("hello"), structured(ok).get("testcases"));

        Map<String, Object> broken = c.tool("validate_suite", Map.of("path", "broken.yml"));
        assertTrue(isError(broken));
        String errors = Cast.toString(structured(broken).get("errors"));
        for (String name : List.of("\"one\"", "\"two\"", "\"three\"")) {
            assertTrue(errors.contains("test case " + name), errors);
        }
        assertTrue(errors.contains("broken.yml:5"), errors);

        Map<String, Object> typo = c.tool("validate_suite", Map.of("path", "typo.yml"));
        assertFalse(isError(typo));
        String warnings = Json.write(structured(typo).get("warnings"));
        assertTrue(warnings.contains("result.stdou is not a field of exec results"), warnings);
    }

    @Test
    void writesOnlyValidSuites(@TempDir Path elsewhere) throws Exception {
        Client c = client();
        Map<String, Object> written = c.tool("write_suite", Map.of("path", "tests/hello.yml", "content", VALID));
        assertFalse(isError(written), text(written));
        assertEquals(VALID, Files.readString(workspace.resolve("tests/hello.yml")));

        Map<String, Object> invalid = c.tool("write_suite", Map.of("path", "tests/hello.yml",
                "content", VALID.replace("result.stdout == \"hello\"", "result.stdout ==")));
        assertTrue(isError(invalid));
        assertTrue(Cast.toString(structured(invalid).get("errors")).contains("tests/hello.yml"),
                "errors name the target, not the temporary file: " + text(invalid));
        assertEquals(VALID, Files.readString(workspace.resolve("tests/hello.yml")), "an invalid suite replaces nothing");
        try (var files = Files.list(workspace.resolve("tests"))) {
            assertEquals(List.of("hello.yml"), files.map(f -> f.getFileName().toString()).toList(), "no temporary file left");
        }
    }

    @Test
    void writesNothingOutsideTheWorkspace(@TempDir Path elsewhere) throws Exception {
        Files.createSymbolicLink(workspace.resolve("link"), elsewhere);
        Client c = client();
        for (String path : List.of("../escape.yml", elsewhere.resolve("abs.yml").toString(), "link/through.yml")) {
            Map<String, Object> r = c.tool("write_suite", Map.of("path", path, "content", VALID));
            assertTrue(isError(r), path);
            assertTrue(text(r).contains("outside the workspace"), text(r));
        }
        try (var files = Files.list(elsewhere)) {
            assertEquals(0, files.count());
        }
        assertTrue(isError(c.tool("write_suite", Map.of("path", "notes.txt", "content", "x"))));
        assertTrue(isError(c.tool("validate_suite", Map.of("path", "link/../../x.yml"))));
    }

    // ------------------------------------------------------------ run

    @Test
    void runsSuitesAndReportsFailuresWithTheirValuesAndNoSecrets() throws Exception {
        Files.writeString(workspace.resolve("run.yml"), """
                name: run
                secrets: [token]
                testcases:
                - name: passes
                  steps:
                  - script: echo hello
                - name: fails
                  steps:
                  - script: echo tok-SECRET-42
                    set:
                      token: result.stdout
                  - script: echo ${token} and more
                    assertions:
                    - result.stdout == "something else"
                """);
        Map<String, Object> r = client().tool("run_suite", Map.of("path", "run.yml"));
        assertTrue(isError(r), "a failing suite is reported as an error result");
        Map<String, Object> s = structured(r);
        assertEquals("FAIL", s.get("status"));
        String all = Json.write(r);
        assertFalse(all.contains("tok-SECRET-42"), all);
        assertTrue(all.contains("__hidden__"), all);
        assertTrue(all.contains("result.stdout == \\\"something else\\\""), all);
        Map<String, Object> suite = Cast.toStringMap(((List<?>) s.get("suites")).getFirst());
        List<?> cases = (List<?>) suite.get("testcases");
        assertEquals("PASS", Cast.toStringMap(cases.get(0)).get("status"));
        Map<String, Object> failed = Cast.toStringMap(((List<?>) Cast.toStringMap(cases.get(1)).get("failedSteps")).getFirst());
        assertEquals(2L, Cast.toLong(failed.get("step")));
        assertTrue(Cast.toString(failed.get("result")).contains("exitCode"), "the actual result: " + failed);
    }

    @Test
    void runsOnlyTheSelectedTestCasesWithTheGivenVariables() throws Exception {
        Files.writeString(workspace.resolve("select.yml"), """
                name: select
                testcases:
                - name: first
                  steps:
                  - script: echo ${who}
                    assertions:
                    - result.stdout == "ada"
                - name: second
                  steps:
                  - script: exit 1
                """);
        Client c = client();
        Map<String, Object> r = c.tool("run_suite", Map.of("path", "select.yml", "testcases", List.of("first"),
                "vars", Map.of("who", "ada")));
        assertFalse(isError(r), text(r));
        assertTrue(isError(c.tool("run_suite", Map.of("path", "select.yml", "testcases", List.of("third")))));
    }

    @Test
    void reportsProgressOnlyWhenAskedFor() throws Exception {
        Files.writeString(workspace.resolve("progress.yml"), """
                name: progress
                testcases:
                - name: one
                  steps:
                  - script: echo 1
                - name: two
                  steps:
                  - script: echo 2
                """);
        Client c = client();
        c.tool("run_suite", Map.of("path", "progress.yml"));
        assertEquals(List.of(), c.notifications);

        Map<String, Object> params = Map.of("name", "run_suite", "arguments", Map.of("path", "progress.yml"),
                "_meta", Map.of("progressToken", "tok-1"));
        c.response(c.send("tools/call", params));
        assertEquals(2, c.notifications.size(), c.notifications.toString());
        Map<String, Object> last = Cast.toStringMap(c.notifications.getLast().get("params"));
        assertEquals("tok-1", last.get("progressToken"));
        assertEquals(2L, Cast.toLong(last.get("progress")));
        assertEquals(2L, Cast.toLong(last.get("total")));
        assertEquals("notifications/progress", c.notifications.getLast().get("method"));
    }

    @Test
    void cancelsARunAndKeepsAnswering() throws Exception {
        Files.writeString(workspace.resolve("slow.yml"), """
                name: slow
                testcases:
                - name: slow
                  steps:
                  - script: sleep 30
                - name: never
                  steps:
                  - script: echo never
                """);
        Client c = client();
        long start = System.nanoTime();
        long run = c.send("tools/call", Map.of("name", "run_suite", "arguments", Map.of("path", "slow.yml")));
        Thread.sleep(500);
        // the server answers while the run goes on
        assertEquals(Map.of(), c.request("ping", Map.of()).get("result"));
        c.notify("notifications/cancelled", Map.of("requestId", run, "reason", "user pressed Esc"));
        // a cancelled request gets no answer; the run ends: the server stops in time
        c.close();
        client = null;
        assertTrue((System.nanoTime() - start) / 1e9 < 15, "the sleep was interrupted");
        assertTrue(c.log().contains("cancelling request " + run), c.log());
        assertTrue(c.fromServer.stream().noneMatch(m -> Cast.toLong(m.get("id")) == run), "no answer to " + run);
    }

    // ------------------------------------------------------------ gherkin

    @Test
    void listsGherkinStepsAndDefinitions() throws Exception {
        Files.createDirectories(workspace.resolve("steps"));
        Files.writeString(workspace.resolve("steps/greet.steps.yml"), """
                steps:
                  - expression: 'I say {string}'
                    step: { script: "echo ${arg1}" }
                """);
        Files.writeString(workspace.resolve("greet.feature"), """
                Feature: greetings
                  Scenario: hello
                    When I say "hello"
                    Then the answer is 42
                """);
        Client c = client();
        Map<String, Object> defs = structured(c.tool("list_step_definitions", Map.of()));
        Map<String, Object> d = Cast.toStringMap(((List<?>) defs.get("definitions")).getFirst());
        assertEquals("I say {string}", d.get("expression"));
        assertEquals("steps/greet.steps.yml", d.get("file"));

        Map<String, Object> steps = structured(c.tool("gherkin_steps", Map.of("path", "greet.feature")));
        assertEquals(1L, Cast.toLong(steps.get("problems")));
        String problems = Json.write(steps.get("scenarios"));
        assertTrue(problems.contains("the answer is {int}"), "a definition to start from: " + problems);
    }

    // ------------------------------------------------------------ command line

    @Test
    void keepsStdoutForTheProtocol() throws Exception {
        Files.writeString(workspace.resolve("shout.yml"), """
                name: shout
                testcases:
                - name: shout
                  steps:
                  - type: shout
                    text: loud
                """);
        String input = Json.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18")), Json.COMPACT) + "\n"
                + Json.write(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                        "params", Map.of("name", "run_suite", "arguments", Map.of("path", "shout.yml"))), Json.COMPACT) + "\n";
        ByteArrayOutputStream protocol = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InputStream stdin = System.in;
        PrintStream stdout = System.out;
        // stdin stays open until both answers are written: its end cancels running calls
        PipedOutputStream feed = new PipedOutputStream();
        try {
            System.setIn(new PipedInputStream(feed, 1 << 16));
            // as in the binary, the stdout of the command line is System.out
            PrintStream protocolOut = new PrintStream(protocol, true, StandardCharsets.UTF_8);
            System.setOut(protocolOut);
            Cli cli = new Cli(Map.of("IS_TTY", "false"), protocolOut,
                    new PrintStream(err, true, StandardCharsets.UTF_8), workspace, workspace);
            var exit = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> cli.run("mcp", "--workspace", workspace.toString()));
            feed.write(input.getBytes(StandardCharsets.UTF_8));
            feed.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (protocol.toString(StandardCharsets.UTF_8).lines().count() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            feed.close();
            assertEquals(0, exit.get(20, TimeUnit.SECONDS));
        } finally {
            System.setIn(stdin);
            System.setOut(stdout);
        }
        List<String> lines = protocol.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, lines.size(), protocol.toString(StandardCharsets.UTF_8));
        for (String line : lines) {
            Cast.toStringMap(Json.parse(line));
        }
        assertTrue(lines.get(1).contains("\\\"status\\\": \\\"PASS\\\"") || lines.get(1).contains("\"status\":\"PASS\""), lines.get(1));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("SHOUT loud"), "printed to stderr instead");
    }
}
