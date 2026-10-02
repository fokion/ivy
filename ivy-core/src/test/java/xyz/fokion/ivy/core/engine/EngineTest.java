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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestStepResult;
import xyz.fokion.ivy.core.model.TestSuite;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.core.yaml.Yaml.TestCaseLines;
import xyz.fokion.ivy.spi.util.GoFormat;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Ported from venom's root package tests: quote_escaping_test.go, process_files_test.go,
 * venom_output_test.go, log_test.go, read_partial_test.go, process_testcase_test.go,
 * types_test.go and assertion_test.go.
 */
class EngineTest {

    // ------------------------------------------------------------ quote_escaping_test.go

    @Test
    void escapesQuotes() {
        assertEquals("{\\\"errors\\\":[{\\\"message\\\":\\\"ERROR: conflicting key value violates exclusion constraint "
                        + "\\\\\\\"age_group_id_range_unique\\\\\\\" (SQLSTATE 23P01)\\\",\\\"path\\\":[\\\"ageGroup\\\"]}],\\\"data\\\":null}",
                PartialYaml.escapeQuotes("{\"errors\":[{\"message\":\"ERROR: conflicting key value violates exclusion "
                        + "constraint \\\"age_group_id_range_unique\\\" (SQLSTATE 23P01)\",\"path\":[\"ageGroup\"]}],\"data\":null}"));
        assertEquals("simple \\\"quoted\\\" text", PartialYaml.escapeQuotes("simple \"quoted\" text"));
        assertEquals("no quotes here", PartialYaml.escapeQuotes("no quotes here"));
        assertEquals("", PartialYaml.escapeQuotes(""));
    }

    @Test
    void escapedJsonSurvivesInterpolationAndYaml() throws Exception {
        for (String json : List.of(
                "{\"errors\":[{\"message\":\"ERROR: constraint \\\"age_group_id_range_unique\\\" (SQLSTATE 23P01)\"}],\"data\":null}",
                "{\"data\":{\"message\":\"success\"}}",
                "{\"user\":{\"name\":\"John \\\"Johnny\\\" Doe\"}}")) {
            String interpolated = "{\"result\":{\"body\":\"{{.body}}\"}}".replace("{{.body}}", PartialYaml.escapeQuotes(json));
            Json.parse(interpolated);
            Map<String, Object> m = Yaml.loadMap(interpolated);
            assertEquals(json, ((Map<?, ?>) m.get("result")).get("body"));
        }
        Map<String, Object> m = Yaml.loadMap("\nresult:\n  body: \"" + PartialYaml.escapeQuotes(
                "{\"message\":\"constraint \\\"age_group_id_range_unique\\\"\"}") + "\"\n  statuscode: 200\n");
        assertTrue(((Map<?, ?>) m.get("result")).get("body").toString().contains("age_group_id_range_unique"));
    }

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
                    - result.statuscode ShouldEqual 200
                    - result.body ShouldContainSubstring hello

                - name: post http testcase
                  steps:
                  - type: http
                    method: POST
                    url: https://example.com/api
                    assertions:
                    - result.statuscode ShouldEqual 201
                  - type: exec
                    script: echo done
                    assertions:
                    - result.code ShouldEqual 0
                    - result.systemout ShouldContainSubstring done
                """;
        List<TestCaseLines> infos = Yaml.lineNumbers(yaml);
        assertEquals(2, infos.size());
        assertEquals(6, infos.get(0).testCaseLine());
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

    // ------------------------------------------------------------ venom_output_test.go

    private static Ivy ivy() {
        return new Ivy(new PrintStream(PrintStream.nullOutputStream()));
    }

    private static TestSuite authSuite() {
        TestSuite ts = new TestSuite();
        ts.name = "HTTP Auth Test";
        ts.secrets = List.of("basic_auth_password");
        ts.vars.put("url", "http://127.0.0.1:8000");
        ts.vars.put("basic_auth_user", "testuser");
        ts.vars.put("basic_auth_password", "my_secret");
        return ts;
    }

    @Test
    void cleansUpSecrets() {
        TestSuite ts = authSuite();
        TestCase tc = new TestCase();
        tc.name = "GET with credentials";
        tc.vars.put("basic_auth_password", "my_secret");
        TestStepResult r = new TestStepResult();
        r.name = "GET-with-credentials";
        r.inputVars = new LinkedHashMap<>(Map.of("basic_auth_user", "testuser", "basic_auth_password", "my_secret"));
        r.raw = "type: http\nbasic_auth_password: \"{{.basic_auth_password}}\"\n".getBytes(StandardCharsets.UTF_8);
        r.interpolated = "type: http\nbasic_auth_password: my_secret\nbasic_auth_user: testuser\nmethod: GET\n"
                .getBytes(StandardCharsets.UTF_8);
        r.systemout = "Authorization: Basic dGVzdHVzZXI6bXlfc2VjcmV0";
        tc.testStepResults.add(r);
        ts.testCases.add(tc);

        TestSuite cleaned = Outputs.cleanUpSecrets(ivy(), ts);
        assertEquals("__hidden__", cleaned.vars.get("basic_auth_password"));
        assertEquals("testuser", cleaned.vars.get("basic_auth_user"));
        TestStepResult result = cleaned.testCases.getFirst().testStepResults.getFirst();
        assertEquals("__hidden__", result.inputVars.get("basic_auth_password"));
        assertFalse(new String(result.raw, StandardCharsets.UTF_8).contains("my_secret"));
        assertFalse(new String(result.interpolated, StandardCharsets.UTF_8).contains("my_secret"));
        assertFalse(result.systemout.contains("my_secret"));
        assertFalse(result.systemout.contains("dGVzdHVzZXI6bXlfc2VjcmV0"));
        assertFalse(Json.write(cleaned.toJson()).contains("my_secret"));
    }

    @Test
    void replacesLongestSecretsFirst() {
        assertEquals("__hidden__", IvyLog.replaceSecrets("foobar", List.of("foo", "foobar")));
        assertEquals("basic_auth_password: __hidden__\n",
                IvyLog.hideSensitive("basic_auth_password: my_secret\n", List.of("my_secret")));
    }

    @Test
    void derivesBasicAuthSecrets() {
        List<String> secrets = ivy().computeSecrets(authSuite(), null);
        String token = Base64.getEncoder().encodeToString("testuser:my_secret".getBytes(StandardCharsets.UTF_8));
        assertTrue(secrets.contains(token));
        assertEquals("__hidden__", IvyLog.hideSensitive(token, secrets));
    }

    @Test
    void derivesSecretsFromTestCaseVars() {
        TestSuite ts = new TestSuite();
        ts.secrets = List.of("basic_auth_password");
        TestCase tc = new TestCase();
        tc.vars.put("basic_auth_user", "testuser");
        tc.vars.put("basic_auth_password", "my_secret");
        ts.testCases.add(tc);
        List<String> secrets = ivy().computeSecrets(ts, tc);
        String token = Base64.getEncoder().encodeToString("testuser:my_secret".getBytes(StandardCharsets.UTF_8));
        assertEquals("__hidden__", IvyLog.hideSensitive(token, secrets));
        assertEquals("__hidden__", IvyLog.hideSensitive(Base64.getEncoder().encodeToString("my_secret".getBytes()), secrets));
        assertFalse(IvyLog.hideSensitive("Authorization: Basic " + token, secrets).contains(token));
    }

    @Test
    void hidesSuiteSecrets() {
        TestSuite ts = new TestSuite();
        ts.secrets = List.of("token");
        ts.vars.put("token", "secret-value");
        assertEquals("__hidden__", IvyLog.hideSensitive("secret-value", ivy().computeSecrets(ts, null)));
    }

    // ------------------------------------------------------------ log_test.go

    @Test
    void hidesSensitiveValues() {
        List<String> secrets = List.of("Joe", "Doe");
        assertEquals("__hidden__", IvyLog.hideSensitive("Joe", secrets));
        assertEquals("__hidden__ tests something", IvyLog.hideSensitive("Joe tests something", secrets));
        assertEquals("Dave tests something", IvyLog.hideSensitive("Dave tests something", secrets));
        assertEquals("1234", IvyLog.hideSensitive(1234L, secrets));
        assertEquals("__hidden__!", IvyLog.hideSensitive("Doe!", secrets));
        assertEquals("__hidden__ __hidden__", IvyLog.hideSensitive("Joe Doe", secrets));
    }

    @Test
    void logRedactsSecrets() {
        StringWriter out = new StringWriter();
        IvyLog log = new IvyLog(out, IvyLog.Level.DEBUG);
        IvyLog.Fields f = IvyLog.Fields.EMPTY.withTestsuite("suite").withSecrets(List.of("my_secret"));
        log.info(f, "step content: basic_auth_password: my_secret");
        log.debug(f, "with vars: " + GoFormat.sprint(Map.of("basic_auth_password", "my_secret")));
        log.debug(f, "count=42 ratio=1.50 ok=true");
        String s = out.toString();
        assertFalse(s.contains("my_secret"));
        assertTrue(s.contains("[INFO] [suite] step content: basic_auth_password: __hidden__"));
        assertTrue(s.contains("[DEBU] [suite] count=42 ratio=1.50 ok=true"));
    }

    // ------------------------------------------------------------ read_partial_test.go

    @Test
    void readsPartialYaml() {
        String content = """

                foo:
                  - foo1
                  - foo2

                record:
                  - val
                  - to be recorded

                bar:
                  bar1: bar1v
                  bar2: bar2v
                \t\t\t\t""";
        assertEquals("record:\n  - val\n  - to be recorded\n", PartialYaml.read(content, "record"));
        assertEquals("record:\n- val\n- to be recorded\n",
                PartialYaml.read(content.replace("  - ", "- "), "record"));
    }

    // ------------------------------------------------------------ process_testcase_test.go

    @Test
    void processesVariableAssignments() throws Exception {
        Map<String, Object> vars = Map.of("here.some.value", "this is the \nvalue");
        String step = Json.write(Map.of("vars", Map.of(
                "assignVar", Map.of("from", "here.some.value"),
                "assignVarWithRegex", Map.of("from", "here.some.value", "regex", "this is (?s:(.*))"))));
        Map<String, Object> result = StepProcessor.processVariableAssignments(RunContext.root(), "", vars, step);
        assertEquals("map[assignVar:this is the \nvalue assignVarWithRegex:the \nvalue]", GoFormat.sprint(result));

        assertTrue(StepProcessor.processVariableAssignments(RunContext.root(), "", vars,
                Json.write(Map.of("type", "exec", "script", "echo 'foo'"))).isEmpty());
        assertThrows(IvyException.class, () -> StepProcessor.processVariableAssignments(RunContext.root(), "", vars,
                Json.write(Map.of("vars", Map.of("x", Map.of("from", "missing"))))));
        assertEquals("fallback", StepProcessor.processVariableAssignments(RunContext.root(), "", vars,
                Json.write(Map.of("vars", Map.of("x", Map.of("from", "missing", "default", "fallback"))))).get("x"));
    }

    // ------------------------------------------------------------ types_test.go, assertion_test.go

    @Test
    void removesNotPrintableCharacters() {
        assertEquals("python-mysqldb :  [34mOK [0m", GoStrings.removeNotPrintable("python-mysqldb : \u001b[34mOK\u001b[0m"));
    }

    @Test
    void splitsAssertions() {
        assertEquals(List.of("cmd", "arg"), AssertionChecker.splitAssertion("cmd arg"));
        assertEquals(List.of("cmd", "arg1", "arg 2"), AssertionChecker.splitAssertion("cmd arg1 \"arg 2\""));
        assertEquals(List.of("cmd", "arg 1", "arg 2"), AssertionChecker.splitAssertion("cmd 'arg 1' \"arg 2\""));
        assertEquals(List.of("cmd", "arg 1", "'arg' 2"), AssertionChecker.splitAssertion("cmd 'arg 1' \"'arg' 2\""));
        assertEquals(List.of("cmd", "\"arg 1\"", "'arg' 2"), AssertionChecker.splitAssertion("cmd '\"arg 1\"' \"'arg' 2\""));
    }

    @Test
    void quotesTemplateExpressions() {
        assertEquals("key: \"{{.value}}\"\n\"json\": {{.v}}",
                PartialYaml.quoteTemplateExpressions("key: {{.value}}\n\"json\": {{.v}}"));
    }
}
