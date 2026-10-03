package xyz.fokion.ivy.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestSuite;

/**
 * Suites run in parallel ({@code --parallel n}).
 * <p>
 * Overlap is forced, not timed: steps call a local server whose endpoints block until other
 * suites reach them.
 * <pre>
 *  /meet?n=2     waits until 2 requests are there (CyclicBarrier): passes only when suites overlap
 *  /track?s=x    records the requests in flight when suite x arrives, and the order of arrivals
 *  /gate         waits until /open is called
 *  /slow         answers after 300 ms
 * </pre>
 */
class ParallelTest {

    private HttpServer server;
    private String base;
    private final Map<Integer, CyclicBarrier> barriers = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    /** For each suite of /track, the most requests in flight seen while it was there. */
    private final Map<String, Integer> seenInFlight = new ConcurrentHashMap<>();
    private final List<String> arrivals = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch gate;

    @BeforeEach
    void startServer() throws IOException {
        gate = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            int status = 200;
            try {
                switch (path) {
                    case "/meet" -> {
                        int n = Integer.parseInt(query.substring("n=".length()));
                        barriers.computeIfAbsent(n, CyclicBarrier::new).await(3, TimeUnit.SECONDS);
                    }
                    case "/track" -> {
                        String suite = query.substring("s=".length());
                        arrivals.add(suite);
                        int now = inFlight.incrementAndGet();
                        Thread.sleep(150);
                        seenInFlight.merge(suite, Math.max(now, inFlight.get()), Math::max);
                        inFlight.decrementAndGet();
                    }
                    case "/gate" -> status = gate.await(5, TimeUnit.SECONDS) ? 200 : 504;
                    case "/open" -> gate.countDown();
                    case "/slow" -> Thread.sleep(300);
                    default -> status = 404;
                }
            } catch (Exception e) {
                status = 500;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Writes the suites, named after their file, and returns their paths in order. */
    private static List<String> write(Path dir, Map<String, String> suites) throws IOException {
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, String> e : suites.entrySet()) {
            paths.add(Files.writeString(dir.resolve(e.getKey() + ".yml"), e.getValue()).toString());
        }
        return paths;
    }

    private record Run(Ivy ivy, String console, Path out) {
        TestSuite suite(String shortName) {
            return ivy.tests().testSuites.stream().filter(ts -> ts.shortName.equals(shortName)).findFirst().orElseThrow();
        }

        String log() throws IOException {
            try (var files = Files.list(out)) {
                Path log = files.filter(f -> f.getFileName().toString().endsWith(".log")).findFirst().orElseThrow();
                return Files.readString(log);
            }
        }
    }

    private static Run run(Path dir, int parallel, int verbose, List<String> paths) throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Path out = dir.resolve("out");
        Ivy ivy = new Ivy(new PrintStream(console, true, StandardCharsets.UTF_8)).colors(false).verbose(verbose)
                .parallel(parallel).outputDir(out.toString());
        ivy.initLogger();
        try {
            ivy.parse(paths);
            ivy.process();
        } finally {
            ivy.close();
        }
        return new Run(ivy, console.toString(StandardCharsets.UTF_8), out);
    }

    private String meeting(String name, int n) {
        return """
                name: %s
                testcases:
                - name: meet
                  steps:
                  - type: http
                    url: %s/meet?n=%d
                """.formatted(name, base, n);
    }

    // ------------------------------------------------------------ scheduling

    @Test
    void runsSuitesAtTheSameTime(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        suites.put("a", meeting("a", 2));
        suites.put("b", meeting("b", 2));
        Run parallel = run(dir, 2, 0, write(dir, suites));
        assertEquals(Status.PASS, parallel.ivy().tests().status, parallel.console());

        // one at a time, they never meet: the test proves the overlap
        barriers.clear();
        Run serial = run(dir, 1, 0, write(dir, suites));
        assertEquals(Status.FAIL, serial.ivy().tests().status);
    }

    @Test
    void runsASuiteThatIsNotParallelAloneAndInItsTurn(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        for (String name : List.of("b1", "x", "b2", "b3")) {
            suites.put(name, """
                    name: %s
                    %s
                    testcases:
                    - name: track
                      steps:
                      - type: http
                        url: %s/track?s=%s
                    """.formatted(name, name.equals("x") ? "parallel: false" : "", base, name));
        }
        Run run = run(dir, 3, 0, write(dir, suites));
        assertEquals(Status.PASS, run.ivy().tests().status, run.console());
        assertEquals(1, seenInFlight.get("x"), "x ran alone: " + seenInFlight);
        // x waited for b1 and was not overtaken by the suites after it
        assertEquals("b1", arrivals.getFirst(), arrivals.toString());
        assertEquals("x", arrivals.get(1), arrivals.toString());
    }

    @Test
    void rejectsAParallelKeyThatIsNotABoolean(@TempDir Path dir) throws Exception {
        List<String> paths = write(dir, Map.of("a", "name: a\nparallel: sometimes\ntestcases: []\n"));
        IvyException e = assertThrows(IvyException.class, () -> run(dir, 2, 0, paths));
        assertTrue(e.getMessage().contains("'parallel' must be true or false"), e.getMessage());
    }

    @Test
    void stopsStartingSuitesAfterOneCrashesAndLetsRunningOnesFinish(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        suites.put("slow", """
                name: slow
                testcases:
                - name: slow
                  steps:
                  - type: http
                    url: %s/slow
                """.formatted(base));
        suites.put("bad", """
                name: bad
                vars:
                  broken: ${env.IVY_TEST_NOT_SET_ANYWHERE.trim()}
                testcases:
                - name: never
                  steps:
                  - script: echo never
                """);
        suites.put("later", """
                name: later
                testcases:
                - name: later
                  steps:
                  - type: http
                    url: %s/track?s=later
                """.formatted(base));
        List<String> paths = write(dir, suites);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Ivy ivy = new Ivy(new PrintStream(console, true, StandardCharsets.UTF_8)).colors(false).parallel(2)
                .outputDir(dir.resolve("out").toString());
        ivy.initLogger();
        try {
            ivy.parse(paths);
            IvyException e = assertThrows(IvyException.class, ivy::process);
            assertTrue(e.getMessage().contains("error while computing variable broken"), e.getMessage());
        } finally {
            ivy.close();
        }
        TestSuite slow = ivy.tests().testSuites.get(0);
        assertEquals(Status.PASS, slow.status, "the running suite finished");
        assertNull(ivy.tests().testSuites.get(2).status, "no suite started after the crash");
        assertFalse(arrivals.contains("later"));
        assertEquals(1, ivy.tests().nbTestsuitesPass);
    }

    @Test
    void rethrowsUnexpectedErrorsOfASuite() {
        TestSuite one = new TestSuite();
        TestSuite two = new TestSuite();
        for (int parallel : List.of(1, 2)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> Scheduler.run(List.of(one, two), parallel, ts -> {
                        throw new IllegalStateException("bug in " + parallel);
                    }));
            assertEquals("bug in " + parallel, e.getMessage());
        }
    }

    @Test
    void countsSuitesOnceTheyAllRan(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        suites.put("pass1", "name: pass1\ntestcases:\n- name: ok\n  steps:\n  - script: echo ok\n");
        suites.put("pass2", "name: pass2\ntestcases:\n- name: ok\n  steps:\n  - script: echo ok\n");
        suites.put("fail", "name: fail\ntestcases:\n- name: ko\n  steps:\n  - script: exit 1\n");
        suites.put("skip", "name: skip\ntestcases:\n- name: skipped\n  if: \"false\"\n  steps:\n  - script: echo no\n");
        Run run = run(dir, 4, 0, write(dir, suites));
        var tests = run.ivy().tests();
        assertEquals(2, tests.nbTestsuitesPass);
        assertEquals(1, tests.nbTestsuitesFail);
        assertEquals(1, tests.nbTestsuitesSkip);
        assertEquals(Status.FAIL, tests.status);
    }

    @Test
    void readsTheCasesOfItsOwnSuite(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        for (String name : List.of("a", "b")) {
            suites.put(name, """
                    name: %1$s
                    testcases:
                    - name: make
                      steps:
                      - script: echo %1$s
                        set:
                          v: result.stdout
                      - type: http
                        url: %2$s/meet?n=2
                    - name: use
                      steps:
                      - script: echo ${cases.make.v}
                        assertions:
                        - result.stdout == "%1$s"
                    """.formatted(name, base));
        }
        Run run = run(dir, 2, 0, write(dir, suites));
        assertEquals(Status.PASS, run.ivy().tests().status, run.console());
    }

    // ------------------------------------------------------------ log and console

    @Test
    void writesTheLogLinesOfEachTestCaseTogether(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        for (String name : List.of("a", "b")) {
            suites.put(name, """
                    name: %1$s
                    testcases:
                    - name: one
                      steps:
                      - script: echo 1
                      - type: http
                        url: %2$s/meet?n=2
                      - script: echo 2
                    """.formatted(name, base));
        }
        Run run = run(dir, 2, 1, write(dir, suites));
        assertEquals(Status.PASS, run.ivy().tests().status, run.console());
        // the blocks of [suite] [testcase] lines never interleave
        List<String> keys = new ArrayList<>();
        for (String line : run.log().split("\n")) {
            var m = java.util.regex.Pattern.compile("\\] \\[([ab])\\] \\[(one)\\]").matcher(line);
            if (m.find()) {
                String key = m.group(1) + "/" + m.group(2);
                if (keys.isEmpty() || !keys.getLast().equals(key)) {
                    keys.add(key);
                }
            }
        }
        assertEquals(new LinkedHashSet<>(keys).size(), keys.size(), "interleaved: " + keys);
        // the lines of parse come before those of the suites
        String log = run.log();
        assertTrue(log.indexOf("Parsing testsuite") < log.indexOf("Starting testsuite"), log);
    }

    @Test
    void hidesInTheLogASecretCapturedAfterAnotherSuiteWroteItsLines(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        // b logs the token, waits until a has written its first test case, then captures it as a secret
        suites.put("b", """
                name: b
                secrets: [token]
                testcases:
                - name: login
                  steps:
                  - script: echo tok-XYZ123
                    info: "got ${result.stdout}"
                  - type: http
                    url: %1$s/gate
                  - script: echo tok-XYZ123
                    set:
                      token: result.stdout
                """.formatted(base));
        suites.put("a", """
                name: a
                testcases:
                - name: first
                  steps:
                  - script: echo first
                - name: second
                  steps:
                  - type: http
                    url: %1$s/open
                """.formatted(base));
        Run run = run(dir, 2, 1, write(dir, suites));
        assertEquals(Status.PASS, run.ivy().tests().status, run.console());
        String log = run.log();
        assertFalse(log.contains("tok-XYZ123"), log);
        assertTrue(log.contains("got __hidden__"), log);
    }

    @Test
    void printsEachTestCaseAsOneBlockStartingWithItsSuite(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        for (String name : List.of("a", "b")) {
            suites.put(name, """
                    name: %1$s
                    testcases:
                    - name: bad
                      steps:
                      - type: http
                        url: %2$s/meet?n=2
                      - script: echo %1$s
                        assertions:
                        - result.stdout == "never"
                    """.formatted(name, base));
        }
        Run run = run(dir, 2, 0, write(dir, suites));
        List<String> lines = List.of(run.console().split(System.lineSeparator()));
        for (String name : List.of("a", "b")) {
            int at = lines.indexOf(" \t• [" + name + "] bad FAIL");
            assertTrue(at >= 0, run.console());
            // the details of the failure follow the test case, not another suite's lines
            assertEquals(" \t\t• exec", lines.get(at + 1), run.console());
            assertTrue(lines.get(at + 2).contains("result.stdout == \"never\""), run.console());
        }
    }

    @Test
    void hidesOnTheConsoleASecretCapturedLaterInTheTestCase(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        suites.put("a", """
                name: a
                secrets: [token]
                testcases:
                - name: login
                  steps:
                  - script: echo tok-ABC987
                    info: "got ${result.stdout}"
                  - script: echo tok-ABC987
                    set:
                      token: result.stdout
                """);
        suites.put("b", "name: b\ntestcases:\n- name: other\n  steps:\n  - script: echo other\n");
        Run run = run(dir, 2, 1, write(dir, suites));
        assertFalse(run.console().contains("tok-ABC987"), run.console());
        assertTrue(run.console().contains("got __hidden__"), run.console());
    }

    @Test
    void keepsTheConsoleOfOneSuiteAtATimeUnchanged(@TempDir Path dir) throws Exception {
        Map<String, String> suites = new java.util.LinkedHashMap<>();
        suites.put("a", "name: a\ntestcases:\n- name: ok\n  steps:\n  - script: echo ok\n"
                + "- name: skipped\n  if: \"false\"\n  steps:\n  - script: echo no\n");
        suites.put("b", "name: b\ntestcases:\n- name: ko\n  steps:\n  - script: exit 1\n");
        List<String> paths = write(dir, suites);
        Run run = run(dir, 1, 0, paths);
        String nl = System.lineSeparator();
        String expected = " • a (" + paths.get(0) + ")" + nl
                + " \t• ok PASS" + nl
                + " \t• skipped SKIP" + nl
                + " • b (" + paths.get(1) + ")" + nl
                + " \t• ko FAIL" + nl
                + " \t\t• exec" + nl;
        assertTrue(run.console().contains(expected), run.console());
        assertFalse(run.console().contains("[a]"), "no suite labels with one suite at a time");
    }

    // ------------------------------------------------------------ status

    @Test
    void aggregatesStatuses() {
        assertEquals(Status.PASS, Status.aggregate(List.of(Status.PASS, Status.PASS), Status.SKIP));
        assertEquals(Status.FAIL, Status.aggregate(List.of(Status.PASS, Status.FAIL, Status.SKIP), Status.SKIP));
        assertEquals(Status.SKIP, Status.aggregate(List.of(Status.SKIP, Status.SKIP), Status.PASS));
        assertEquals(Status.PASS, Status.aggregate(List.of(Status.SKIP, Status.PASS), Status.SKIP));
        assertEquals(Status.SKIP, Status.aggregate(List.of(), Status.SKIP));
        assertEquals(Status.PASS, Status.aggregate(List.of(), Status.PASS));
        // children that did not run (null) count as not skipped, as before
        List<Status> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(Status.SKIP);
        assertEquals(Status.PASS, Status.aggregate(withNull, Status.PASS));
    }

    @Test
    void rejectsParallelBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new Ivy(System.out).parallel(0));
    }

    // ------------------------------------------------------------ http client threads

    @Test
    void sharesTheThreadsOfHttpClients(@TempDir Path dir) throws Exception {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        Run run = run(dir, 1, 0, write(dir, Map.of("many", """
                name: many
                testcases:
                - name: many
                  steps:
                  - type: http
                    url: %s/open
                    range: 300
                """.formatted(base))));
        assertEquals(Status.PASS, run.ivy().tests().status, run.console());
        long added = Thread.getAllStackTraces().keySet().stream().filter(t -> !before.contains(t) && t.isAlive()).count();
        assertTrue(added < 20, "platform threads left after 300 requests: " + added);
    }
}
