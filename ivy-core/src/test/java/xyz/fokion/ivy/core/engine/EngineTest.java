package xyz.fokion.ivy.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.core.log.Secrets;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestStepResult;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.core.yaml.Yaml.TestCaseLines;

/**
 * Engine units (file discovery, line numbers, secrets, logging) and suites run end to end.
 */
class EngineTest {

    // ------------------------------------------------------------ process_files_test.go

    @Test
    void findsSuiteFiles(@TempDir Path dir) throws Exception {
        assertThrows(IvyException.class, () -> SuiteFiles.find(List.of(dir.toString())));

        Files.writeString(dir.resolve("d1.yml"), "hello");
        Path dir2 = Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir2.resolve("d2.yml"), "hello");
        Files.writeString(dir2.resolve("d3.yml"), "hello");
        Path dir4 = Files.createDirectories(dir2.resolve("deep/deeper"));
        Files.writeString(dir4.resolve("d4.yml"), "hello");
        Files.writeString(dir4.resolve("ignored.txt"), "hello");

        assertEquals(List.of("d1.yml"), names(SuiteFiles.find(List.of(dir.toString()))));
        assertEquals(List.of("d1.yml", "d2.yml", "d3.yml"), names(SuiteFiles.find(List.of(dir.toString(), dir2.toString()))));
        assertEquals(List.of("d1.yml", "d2.yml", "d3.yml", "d4.yml"), names(SuiteFiles.find(List.of(dir + "/**/*.yml"))));
        // duplicates are removed
        assertEquals(4, SuiteFiles.find(List.of(dir2.toString(), dir4.toString(), dir + "/**/*.yml")).size());
    }

    @Test
    void keepsTheOrderOfFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b.yml"), "hello");
        Files.writeString(dir.resolve("a.yml"), "hello");
        List<Path> out = SuiteFiles.find(List.of(dir + "/b.yml", dir + "/a.yml"));
        assertEquals(List.of("b.yml", "a.yml"), names(out));
    }

    private static List<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).toList();
    }

    @Test
    void extractsLineNumbers() {
        String yaml = """
                name: HTTP testsuite
                vars:
                  input: "this my input"

                testcases:
                - name: get http testcase
                  steps:
                  - type: http
                    method: GET
                    url: https://example.com
                    assertions:
                    - result.status == 200
                    - result.body contains "hello"

                - name: post http testcase
                  steps:
                  - type: http
                    method: POST
                    url: https://example.com/api
                    assertions:
                    - result.status == 201
                  - type: exec
                    script: echo done
                    assertions:
                    - result.exitCode == 0
                    - result.stdout contains "done"
                """;
        List<TestCaseLines> infos = Yaml.lineNumbers(yaml);
        assertEquals(2, infos.size());
        assertEquals(6, infos.getFirst().testCaseLine());
        assertEquals(List.of(8), infos.get(0).stepLines());
        assertEquals(List.of(List.of(12, 13)), infos.get(0).assertionLines());
        assertEquals(15, infos.get(1).testCaseLine());
        assertEquals(List.of(17, 22), infos.get(1).stepLines());
        assertEquals(List.of(List.of(21), List.of(25, 26)), infos.get(1).assertionLines());
    }

    @Test
    void findsSourceLines() {
        TestCase tc = new TestCase();
        tc.lines = new TestCaseLines(6, List.of(8, 17), List.of(List.of(12, 13), List.of(21, 25, 26)));
        assertEquals(12, tc.findSourceLine(0, 0));
        assertEquals(13, tc.findSourceLine(0, 1));
        assertEquals(21, tc.findSourceLine(1, 0));
        assertEquals(25, tc.findSourceLine(1, 1));
        assertEquals(26, tc.findSourceLine(1, 2));
        assertEquals(8, tc.findSourceLine(0, -1));
        assertEquals(17, tc.findSourceLine(1, -1));
        assertEquals(8, tc.findSourceLine(0, 5));
        assertEquals(17, tc.findSourceLine(1, 10));
        assertEquals(6, tc.findSourceLine(5, -1));
        assertEquals(0, new TestCase().findSourceLine(0, 0));
    }

    // ------------------------------------------------------------ secrets

    private static Ivy ivy() {
        return new Ivy(new PrintStream(PrintStream.nullOutputStream()));
    }

    @Test
    void logHidesSecrets() {
        StringWriter out = new StringWriter();
        Secrets secrets = new Secrets();
        secrets.add("my_secret");
        IvyLog log = new IvyLog(out, IvyLog.Level.DEBUG, secrets);
        IvyLog.Fields f = IvyLog.Fields.EMPTY.withTestsuite("suite");
        log.info(f, "step content: basic_auth_password: my_secret");
        log.debug(f, "count=42 ratio=1.50 ok=true");
        log.flush();
        String s = out.toString();
        assertFalse(s.contains("my_secret"));
        assertTrue(s.contains("[INFO] [suite] step content: basic_auth_password: __hidden__"));
        assertTrue(s.contains("[DEBU] [suite] count=42 ratio=1.50 ok=true"));
    }

    @Test
    void hidesSecretsEverywhere(@TempDir Path dir) throws Exception {
        Path suite = Files.writeString(dir.resolve("suite.yml"), """
                name: secrets
                secrets: [password, token, db.password, api_key]
                vars:
                  password: "p<a>ss&word"
                  db:
                    host: localhost
                    password: db-pa55
                testcases:
                - name: login
                  steps:
                  - script: echo "token=tok-123456 ${password} ${db.password} ${cli_secret}"
                    set:
                      token: result.stdout.match("token=([\\w-]+)")[1]
                    assertions:
                    - result.stdout contains "never"
                  - type: http
                    url: http://127.0.0.1:1/
                    basic_auth_user: ada
                    basic_auth_password: ${password}
                - name: reuse
                  steps:
                  - script: echo ${cases.login.token ?? "none"} | base64
                """);
        java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
        Ivy ivy = new Ivy(new PrintStream(console, true, StandardCharsets.UTF_8)).colors(false).verbose(2)
                .outputDir(dir.resolve("out").toString()).outputFormat("xml").htmlReport(true);
        ivy.addSecrets(Map.of("cli_secret", "from-the-command-line"));
        ivy.initLogger();
        try {
            ivy.parse(List.of(suite.toString()));
            ivy.process();
            Outputs.write(ivy);
        } finally {
            ivy.close();
        }
        StringBuilder everything = new StringBuilder(console.toString(StandardCharsets.UTF_8));
        try (var files = Files.list(dir.resolve("out"))) {
            for (Path f : files.toList()) {
                everything.append(Files.readString(f));
            }
        }
        String all = everything.toString();
        for (String secret : List.of("p<a>ss&word", "p&lt;a&gt;ss&amp;word", "db-pa55", "tok-123456", "from-the-command-line",
                Base64.getEncoder().encodeToString("ada:p<a>ss&word".getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString("tok-123456\n".getBytes(StandardCharsets.UTF_8)).replace("=", ""))) {
            assertFalse(all.contains(secret), secret + " leaked: " + all.substring(Math.max(0, all.indexOf(secret) - 300),
                    Math.min(all.length(), all.indexOf(secret) + 100)));
        }
        assertTrue(all.contains("__hidden__"));
        assertTrue(all.contains("localhost"), "values that are not secret stay");
    }

    // ------------------------------------------------------------ strings

    @Test
    void removesNotPrintableCharacters() {
        assertEquals("python-mysqldb :  [34mOK [0m", GoStrings.removeNotPrintable("python-mysqldb : \u001b[34mOK\u001b[0m"));
    }

    // ------------------------------------------------------------ regressions

    private static Ivy runSuite(Path dir, String yaml) throws Exception {
        Path suite = Files.writeString(dir.resolve("suite.yml"), yaml);
        Ivy ivy = ivy().outputDir(dir.resolve("out").toString());
        ivy.initLogger();
        try {
            ivy.parse(List.of(suite.toString()));
            ivy.process();
        } finally {
            ivy.close();
        }
        return ivy;
    }

    private static List<String> errors(Ivy ivy) {
        return ivy.tests().testSuites.getFirst().testCases.stream()
                .flatMap(tc -> tc.testStepResults.stream())
                .flatMap(r -> r.errorList().stream())
                .map(f -> f.value)
                .toList();
    }

    @Test
    void failingAssertionsOnMissingValuesFailTheStepOnly(@TempDir Path dir) throws Exception {
        Ivy ivy = runSuite(dir, """
                name: missing
                testcases:
                - name: first
                  steps:
                  - script: echo '{}'
                    assertions:
                    - result.json.missing.deeper in ["a", "b"]
                - name: second
                  steps:
                  - script: echo ok
                """);
        assertEquals(Status.FAIL, ivy.tests().status);
        assertEquals(Status.PASS, ivy.tests().testSuites.getFirst().testCases.get(1).status);
    }

    @Test
    void reportsTheValuesOfAFailedAssertion(@TempDir Path dir) throws Exception {
        Ivy ivy = runSuite(dir, """
                name: values
                testcases:
                - name: compare
                  steps:
                  - script: echo '{"items":[{"id":1},{"id":2}]}'
                    assertions:
                    - result.json.items.length > 2 && result.exitCode == 0
                """);
        String error = errors(ivy).getFirst();
        assertTrue(error.startsWith("Testcase \"compare\", step #1 (" + dir.resolve("suite.yml") + ":7): assertion failed: "
                + "result.json.items.length > 2 && result.exitCode == 0"), error);
        assertTrue(error.endsWith("\n  result.json.items.length = 2"), error);
    }

    @Test
    void rendersTypedTemplatesAndRanges(@TempDir Path dir) throws Exception {
        Ivy ivy = runSuite(dir, """
                name: typed
                vars:
                  a: hello
                  items: [1, 2, 3]
                  greeting: ${a} world
                testcases:
                - name: ranged
                  steps:
                  - range: ${items}
                    script: echo ${value * 10} ${greeting}
                    assertions:
                    - result.stdout == (value * 10) + " hello world"
                    - index < 3
                  - range: items.filter(i => i > 1)
                    script: echo ${value}
                    assertions:
                    - result.stdout > 1
                """);
        assertEquals(List.of(), errors(ivy));
        assertEquals(5, ivy.tests().testSuites.getFirst().testCases.getFirst().testStepResults.size());
    }

    @Test
    void timesOutSteps(@TempDir Path dir) throws Exception {
        long start = System.nanoTime();
        Ivy ivy = runSuite(dir, """
                name: timeout
                testcases:
                - name: slow
                  steps:
                  - script: sleep 5
                    timeout: 1
                """);
        assertTrue((System.nanoTime() - start) / 1e9 < 4);
        assertTrue(errors(ivy).getFirst().contains("Timeout after 1 second(s)"), errors(ivy).toString());
    }

    @Test
    void setsValuesForLaterStepsAndTestCases(@TempDir Path dir) throws Exception {
        Ivy ivy = runSuite(dir, """
                name: set
                testcases:
                - name: extract id
                  steps:
                  - script: echo id=42
                    assertions:
                    - result.stdout matches "id=(?P<id>[0-9]+)"
                    set:
                      id: result.stdout.match("id=(?P<id>[0-9]+)")[1]
                      label: "id ${result.stdout}"
                  - script: echo ${id}
                    assertions:
                    - result.stdout == 42
                    - label == "id id=42"
                - name: reuse
                  steps:
                  - script: echo ${cases["extract-id"].id}
                    assertions:
                    - result.stdout === "42"
                """);
        assertEquals(List.of(), errors(ivy));
    }

    @Test
    void skipsWithConditions(@TempDir Path dir) throws Exception {
        Ivy ivy = runSuite(dir, """
                name: conditions
                vars:
                  enabled: false
                testcases:
                - name: never
                  if: enabled
                  steps:
                  - script: exit 1
                - name: some steps
                  steps:
                  - script: exit 1
                    if: enabled
                  - script: echo ran
                    if: "!enabled"
                """);
        assertEquals(List.of(), errors(ivy));
        List<TestCase> cases = ivy.tests().testSuites.getFirst().testCases;
        assertEquals(Status.SKIP, cases.get(0).status);
        assertEquals(Status.SKIP, cases.get(1).testStepResults.get(0).status);
        assertEquals(Status.PASS, cases.get(1).testStepResults.get(1).status);
    }

    @Test
    void stopsOnRequiredAssertions(@TempDir Path dir) throws Exception {
        Ivy ivy = runSuite(dir, """
                name: must
                testcases:
                - name: required
                  steps:
                  - script: echo a
                    assertions:
                    - must: result.stdout == "b"
                  - script: echo never
                """);
        TestCase tc = ivy.tests().testSuites.getFirst().testCases.getFirst();
        assertEquals(1, tc.testStepResults.size());
        assertTrue(errors(ivy).getLast().contains("the remaining steps are skipped"), errors(ivy).toString());
    }

    @Test
    void retriesWhileTheConditionHolds(@TempDir Path dir) throws Exception {
        Ivy ivy = runSuite(dir, """
                name: retry
                testcases:
                - name: retried
                  steps:
                  - script: echo nope
                    retry: 3
                    retryIf: result.stdout != "nope"
                    assertions:
                    - result.stdout == "yes"
                """);
        TestStepResult r = ivy.tests().testSuites.getFirst().testCases.getFirst().testStepResults.getFirst();
        assertEquals(0, r.retries);
        assertTrue(errors(ivy).stream().anyMatch(e -> e.contains("retryIf result.stdout != \"nope\" is false")),
                errors(ivy).toString());
    }

    @Test
    void reportsMissingVariables(@TempDir Path dir) {
        IvyException e = assertThrows(IvyException.class, () -> runSuite(dir, """
                name: typo
                vars:
                  url: x
                testcases:
                - name: first
                  steps:
                  - script: echo ${ulr} ${url} ${cases.first.out} ${env.HOME}
                    assertions:
                    - result.exitCode == 0 && out != ""
                    set:
                      out: result.stdout
                """));
        assertTrue(e.getMessage().startsWith("missing variables [ulr]"), e.getMessage());
    }

    @Test
    void explainsTheSyntaxOfOlderVersions(@TempDir Path dir) {
        for (String[] old : new String[][] {
                {"script: echo {{.a}}", "templates are written ${...} now"},
                {"script: echo\n    assertions:\n    - result.code ShouldEqual 0", "assertions are expressions now"},
                {"script: echo\n    skip:\n    - a ShouldBeTrue", "'skip' is now 'if'"},
                {"script: echo\n    retry_if:\n    - a ShouldBeTrue", "'retry_if' is now 'retryIf'"},
                {"script: echo\n    vars:\n      x:\n        from: result.stdout", "step 'vars' is now 'set'"}}) {
            IvyException e = assertThrows(IvyException.class, () -> runSuite(dir, """
                    name: old
                    vars:
                      a: 1
                    testcases:
                    - name: first
                      steps:
                      - %s
                    """.formatted(old[0])));
            assertTrue(e.getMessage().contains(old[1]), e.getMessage());
            assertTrue(e.getMessage().startsWith(dir.resolve("suite.yml") + ":7: "), e.getMessage());
        }
    }

    @Test
    void reportsSyntaxErrorsWithTheirLine(@TempDir Path dir) {
        IvyException e = assertThrows(IvyException.class, () -> runSuite(dir, """
                name: syntax
                testcases:
                - name: first
                  steps:
                  - script: echo
                    assertions:
                    - result.exitCode ==
                """));
        assertTrue(e.getMessage().startsWith(dir.resolve("suite.yml") + ":5: test case \"first\", step #1: "
                + "unexpected end of expression"), e.getMessage());
    }

    @Test
    void writesReportsWithoutSecretsAndCutsLongValues(@TempDir Path dir) throws Exception {
        Path suite = Files.writeString(dir.resolve("suite.yml"), """
                name: report
                secrets: [token]
                vars:
                  token: s3cr3t-value
                testcases:
                - name: long
                  steps:
                  - script: printf 'x%.0s' $(seq 1 500); echo ${token}
                """);
        Ivy ivy = ivy().outputDir(dir.resolve("out").toString()).outputFormat("json").htmlReport(true).reportMaxValue(100);
        ivy.initLogger();
        try {
            ivy.parse(List.of(suite.toString()));
            ivy.process();
            Outputs.write(ivy);
        } finally {
            ivy.close();
        }
        String json = Files.readString(dir.resolve("out/test_results_suite.json"));
        String html = Files.readString(dir.resolve("out/test_results.html"));
        assertFalse(json.contains("s3cr3t-value"));
        assertFalse(html.contains("s3cr3t-value"));
        assertTrue(json.contains("more characters"), json);
        assertFalse(json.contains("x".repeat(101)));
    }
}
