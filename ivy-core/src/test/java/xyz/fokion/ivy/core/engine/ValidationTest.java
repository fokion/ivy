package xyz.fokion.ivy.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.expr.Expression;
import xyz.fokion.ivy.core.expr.Template;

/** What parse reports before a run: one error per test case, and warnings about result fields. */
class ValidationTest {

    private static Ivy parse(Path... suites) throws IvyException {
        Ivy ivy = new Ivy(new PrintStream(OutputStream.nullOutputStream()));
        ivy.parse(java.util.Arrays.stream(suites).map(Path::toString).toList());
        return ivy;
    }

    @Test
    void reportsTheFirstErrorOfEveryTestCaseOfEverySuite(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.yml"), """
                name: a
                testcases:
                - name: one
                  steps:
                  - script: echo ${nope(}
                  - script: echo ${also broken(}
                - name: two
                  steps:
                  - type: unknown-type
                - name: fine
                  steps:
                  - script: echo ok
                """);
        Path b = Files.writeString(dir.resolve("b.yml"), """
                name: b
                testcases:
                - name: three
                  steps:
                  - script: echo ${missing_variable}
                """);
        IvyException e = assertThrows(IvyException.class, () -> parse(a, b));
        String m = e.getMessage();
        assertTrue(m.contains("a.yml:5: test case \"one\", step #1"), m);
        assertTrue(!m.contains("step #2"), "only the first error of a test case: " + m);
        assertTrue(m.contains("test case \"two\", step #1: unknown step type \"unknown-type\""), m);
        assertTrue(m.contains("missing variables [missing_variable] in " + b), m);
        assertEquals(3, m.lines().filter(l -> l.contains(".yml")).count(), m);
    }

    @Test
    void refusesRetriesDelaysAndTimeoutsThatAreNotUsable(@TempDir Path dir) throws Exception {
        Path suite = Files.writeString(dir.resolve("s.yml"), """
                name: s
                vars:
                  later: 2
                testcases:
                - name: half a retry
                  steps:
                  - script: echo ok
                    retry: 1.5
                - name: no number
                  steps:
                  - script: echo ok
                    delay: soon
                - name: negative
                  steps:
                  - script: echo ok
                    timeout: -1
                - name: fine
                  steps:
                  - script: echo ok
                    retry: 2
                    delay: 0.25
                    timeout: ${later}
                """);
        String m = assertThrows(IvyException.class, () -> parse(suite)).getMessage();
        assertTrue(m.contains("\"half a retry\", step #1: attribute \"retry\" is not a whole number"), m);
        assertTrue(m.contains("\"no number\", step #1: attribute \"delay\" is not a number of seconds"), m);
        assertTrue(m.contains("\"negative\", step #1: attribute \"timeout\" must be 0 or more seconds"), m);
        assertTrue(!m.contains("\"fine\""), m);
    }

    @Test
    void refusesWaitsThatCannotWork(@TempDir Path dir) throws Exception {
        Path suite = Files.writeString(dir.resolve("s.yml"), """
                name: s
                testcases:
                - name: both
                  steps:
                  - script: echo ok
                    until: result.stdout == "ok"
                    retry: 3
                - name: lonely within
                  steps:
                  - script: echo ok
                    within: 5
                - name: busy
                  steps:
                  - script: echo ok
                    until: result.stdout == "ok"
                    every: 0
                - name: nothing to wait for
                  steps:
                  - script: echo ok
                  - until: result.stdout == "ok"
                - name: fine
                  steps:
                  - script: echo ok
                    until: result.stdout == "ok"
                    within: 2.5
                    every: 0.5
                """);
        String m = assertThrows(IvyException.class, () -> parse(suite)).getMessage();
        assertTrue(m.contains("\"both\", step #1: a step waits with 'until'"), m);
        assertTrue(m.contains("\"lonely within\", step #1: 'within' and 'every' go with 'until'"), m);
        assertTrue(m.contains("\"busy\", step #1: 'every' must be more than 0 seconds"), m);
        assertTrue(m.contains("\"nothing to wait for\", step #2: a step without a type runs nothing"), m);
        assertTrue(!m.contains("\"fine\""), m);
    }

    @Test
    void warnsAboutResultFieldsTheConnectorDoesNotReport(@TempDir Path dir) throws Exception {
        Path suite = Files.writeString(dir.resolve("s.yml"), """
                name: s
                testcases:
                - name: typos
                  steps:
                  - script: echo ok
                    assertions:
                    - result.stdou == "ok"
                    - result.json.anything == 1
                    info: "code ${result.exitcod}"
                    set:
                      out: result["stderr"]
                  - type: http
                    url: http://localhost:1
                    retryIf: result.statuscode != 200
                    assertions:
                    - result.body.items.filter(result => result.ok).length > 0
                """);
        List<String> warnings = parse(suite).warnings();
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("s.yml:5: test case \"typos\", step #1: result.stdou is not a field of exec results"),
                warnings.toString());
        assertTrue(warnings.get(1).contains("result.exitcod"), warnings.toString());
        assertTrue(warnings.get(2).contains("step #2: result.statuscode is not a field of http results, which are: status,"),
                warnings.toString());
    }

    @Test
    void warnsAboutKeysTheStepIgnores(@TempDir Path dir) throws Exception {
        Path suite = Files.writeString(dir.resolve("s.yml"), """
                name: s
                testcases:
                - name: typos
                  steps:
                  - type: http
                    url: http://localhost:1
                    methd: POST
                    Skip_Body: true
                  - script: echo ok
                    retryif: result.stdout == ""
                    flavour: vanilla
                  - url: http://localhost:1
                    assertions:
                    - result.exitCode == 0
                """);
        List<String> warnings = parse(suite).warnings();
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).endsWith("step #1: \"methd\" is not a property of http steps and is ignored; "
                + "did you mean \"method\"?"), warnings.toString());
        assertTrue(warnings.get(1).endsWith("step #2: \"retryif\" is not a property of exec steps and is ignored; "
                + "did you mean \"retryIf\"?"), warnings.toString());
        assertTrue(warnings.get(2).endsWith("\"flavour\" is not a property of exec steps and is ignored; "
                + "exec steps take: command, script, stdin"), warnings.toString());
        assertTrue(warnings.get(3).contains("step #3: \"url\" is ignored: the step has no type"), warnings.toString());
    }

    @Test
    void refusesAFirstStepWithoutATypeThatReadsAResult(@TempDir Path dir) throws Exception {
        Path suite = Files.writeString(dir.resolve("s.yml"), """
                name: s
                vars:
                  state: ok
                testcases:
                - name: nothing before
                  steps:
                  - assertions:
                    - result.stdout == "ok"
                - name: variables only
                  steps:
                  - assertions:
                    - state == "ok"
                """);
        String m = assertThrows(IvyException.class, () -> parse(suite)).getMessage();
        assertTrue(m.contains("\"nothing before\", step #1: a step without a type checks the result of the step before it"), m);
        assertTrue(!m.contains("variables only"), m);
    }

    @Test
    void readsTheFieldsOfAVariable() {
        assertEquals(Set.of("body", "status"),
                Expression.compile("result.body.id == 1 && result[\"status\"] < 300 && other.x").members("result"));
        assertEquals(Set.of(), Expression.compile("list.map(result => result.x)").members("result"), "a parameter hides it");
        assertEquals(Set.of("stdout"), Template.compile("got ${result.stdout} ${x.result}").members("result"));
    }
}
