package xyz.fokion.ivy.core.gherkin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import xyz.fokion.ivy.core.gherkin.Gherkin.Feature;
import xyz.fokion.ivy.core.gherkin.Gherkin.Scenario;
import xyz.fokion.ivy.core.gherkin.Gherkin.Step;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestSuite;

class GherkinTest {

    private static final String FEATURE = """
            # language: en
            @api
            Feature: Accounts
              Manage accounts
              over HTTP.

              Background:
                Given the service is up

              @smoke
              Scenario: create an account
                When I POST "/accounts" with:
                  \"\"\"json
                  {"name": "ada"}
                  \"\"\"
                Then the status is 201
                And the body contains:
                  | field | value |
                  | name  | ada   |

              Scenario Outline: get <id>
                When I GET "/accounts/<id>"
                Then the status is <status>

                @slow
                Examples: existing
                  | id | status |
                  | 1  | 200    |
                Examples: missing
                  | id | status |
                  | 9  | 404    |

              Rule: archived accounts
                Background:
                  Given an archived account
                Scenario: cannot log in
                  * the status is 403
            """;

    @Test
    void parsesFeatures() throws Exception {
        Feature f = GherkinParser.parse("accounts.feature", FEATURE);
        assertEquals("Accounts", f.name());
        assertEquals("Manage accounts\nover HTTP.", f.description());
        assertEquals("@api", f.tags().getFirst().name());
        assertEquals(1, f.background().steps().size());
        assertEquals(2, f.scenarios().size());

        Scenario create = f.scenarios().getFirst();
        assertEquals(List.of("@smoke"), create.tags().stream().map(Gherkin.Tag::name).toList());
        Step post = create.steps().getFirst();
        assertEquals("When ", post.keyword());
        assertEquals("I POST \"/accounts\" with:", post.text());
        assertEquals("{\"name\": \"ada\"}", post.docString());
        Step table = create.steps().get(2);
        assertEquals(List.of(List.of("field", "value"), List.of("name", "ada")), table.table());
        assertNull(table.docString());

        Scenario outline = f.scenarios().get(1);
        assertTrue(outline.isOutline());
        assertEquals(2, outline.examples().size());
        assertEquals(List.of("id", "status"), outline.examples().get(0).header());
        assertEquals("@slow", outline.examples().get(0).tags().getFirst().name());

        assertEquals(1, f.rules().size());
        assertEquals("* ", f.rules().getFirst().scenarios().getFirst().steps().getFirst().keyword());
        assertEquals(1, f.rules().getFirst().background().steps().size());
    }

    @Test
    void reportsSyntaxErrors() {
        assertThrows(GherkinParser.GherkinException.class, () -> GherkinParser.parse("x", "Scenario: no feature"));
        assertThrows(GherkinParser.GherkinException.class,
                () -> GherkinParser.parse("x", "Feature: f\n  Scenario Outline: o\n    Given a <x>\n"));
        assertEquals(List.of("a", "b|c", "d"), GherkinParser.cells("| a | b\\|c | d |"));
    }

    @Test
    void evaluatesTagExpressions() {
        TagExpression e = TagExpression.parse("@smoke and not (@slow or @wip)");
        assertTrue(e.matches(Set.of("@smoke")));
        assertFalse(e.matches(Set.of("@smoke", "@slow")));
        assertFalse(e.matches(Set.of("@api")));
        assertTrue(TagExpression.parse("").matches(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> TagExpression.parse("@a and"));
    }

    private static StepLibrary library() throws Exception {
        StepLibrary lib = new StepLibrary();
        lib.add("test.steps.yml", """
                steps:
                  - expression: the service is up
                    step: { script: "true" }
                  - match: 'I (?<method>GET|POST) "(?<path>[^"]+)"(?: with:)?'
                    step: { script: "echo ${method} ${path} ${docstring}" }
                  - expression: 'the status is {int}'
                    assertions: [ "result.exitCode == arg1" ]
                  - expression: 'the body contains:'
                    assertions: [ "result.stdout contains rows[0].value" ]
                  - expression: an archived account
                    step: { script: "true" }
                  - expression: 'I say {string} {word} time(s)'
                    step: { script: "echo ${arg1}-${arg2}" }
                """);
        return lib;
    }

    @Test
    void matchesStepDefinitions() throws Exception {
        StepLibrary lib = library();
        StepLibrary.Match m = lib.find("I GET \"/x\"").getFirst();
        assertEquals("GET", m.args().get("method"));
        assertEquals("/x", m.args().get("path"));
        StepLibrary.Match say = lib.find("I say 'hello world' twice time").getFirst();
        assertEquals("hello world", say.args().get("arg1"));
        assertEquals("twice", say.args().get("arg2"));
        assertEquals(1, lib.find("I say \"a\" b times").size());
        assertTrue(lib.find("nothing").isEmpty());
        assertTrue(StepLibrary.snippet("the price is 42 for \"x\"").contains("the price is {int} for {string}"));
    }

    @Test
    void loadsFeaturesAsSuites() throws Exception {
        Feature f = GherkinParser.parse("accounts.feature", FEATURE);
        TestSuite ts = FeatureLoader.load("accounts.feature", f, library(), TagExpression.ALL);
        assertEquals("Accounts", ts.name);
        assertEquals(List.of("create an account", "get 1 (example 1)", "get 9 (example 2)", "cannot log in"),
                ts.testCases.stream().map(tc -> tc.name).toList());

        TestCase create = ts.testCases.getFirst();
        // assertion-only definitions are steps of their own, without a type
        assertEquals(4, create.steps.size());
        Map<String, Object> post = create.steps.get(1);
        assertEquals("echo ${method} ${path} ${docstring}", post.get("script"));
        assertEquals(Map.of("method", "POST", "path", "/accounts", "arg1", "POST", "arg2", "/accounts",
                "docstring", "{\"name\": \"ada\"}"), post.get("with"));
        assertEquals("When I POST \"/accounts\" with:", post.get("name"));
        assertNull(post.get("assertions"));
        assertEquals(Map.of("name", "Then the status is 201", "assertions", List.of(
                Map.of("that", "result.exitCode == arg1", "with", Map.of("arg1", "201")))), create.steps.get(2));
        assertEquals(List.of(Map.of("that", "result.stdout contains rows[0].value", "with", Map.of(
                "table", List.of(List.of("field", "value"), List.of("name", "ada")),
                "rows", List.of(Map.of("field", "name", "value", "ada"))))), create.steps.get(3).get("assertions"));
        assertEquals(List.of(List.of(), List.of(), List.of(16), List.of(17)), create.lines.assertionLines());
        assertTrue(create.gherkin.problems().isEmpty());
        assertEquals(List.of("@api", "@smoke"), create.gherkin.tags());

        TestCase example = ts.testCases.get(1);
        assertEquals("/accounts/1", ((Map<?, ?>) example.steps.get(1).get("with")).get("path"));
        assertEquals(Map.of("that", "result.exitCode == arg1", "with", Map.of("arg1", "200")),
                ((List<?>) example.steps.get(2).get("assertions")).getFirst());
        assertEquals(List.of("@api", "@slow"), example.gherkin.tags());

        TestCase rule = ts.testCases.get(3);
        assertEquals(3, rule.steps.size());
    }

    @Test
    void filtersByTagsAndReportsUndefinedSteps() throws Exception {
        Feature f = GherkinParser.parse("accounts.feature", FEATURE);
        TestSuite smoke = FeatureLoader.load("accounts.feature", f, library(), TagExpression.parse("@smoke"));
        assertEquals(List.of("create an account"), smoke.testCases.stream().map(tc -> tc.name).toList());
        TestSuite notSlow = FeatureLoader.load("accounts.feature", f, library(), TagExpression.parse("not @slow"));
        assertEquals(3, notSlow.testCases.size());

        StepLibrary empty = new StepLibrary();
        TestSuite undefined = FeatureLoader.load("accounts.feature", f, empty, TagExpression.ALL);
        List<String> problems = undefined.testCases.getFirst().gherkin.problems();
        assertTrue(problems.getFirst().startsWith("undefined step: \"Given the service is up\" (accounts.feature:8)"),
                problems.getFirst());
    }
}
