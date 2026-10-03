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
    void readsTheFieldsOfAVariable() {
        assertEquals(Set.of("body", "status"),
                Expression.compile("result.body.id == 1 && result[\"status\"] < 300 && other.x").members("result"));
        assertEquals(Set.of(), Expression.compile("list.map(result => result.x)").members("result"), "a parameter hides it");
        assertEquals(Set.of("stdout"), Template.compile("got ${result.stdout} ${x.result}").members("result"));
    }
}
