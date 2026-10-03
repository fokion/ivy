package xyz.fokion.ivy.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.GherkinInfo;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestStepResult;
import xyz.fokion.ivy.core.model.TestSuite;
import xyz.fokion.ivy.core.model.Tests;
import xyz.fokion.ivy.core.util.Slug;
import xyz.fokion.ivy.spi.util.Json;

/**
 * The Cucumber JSON report, read by CI dashboards. Features keep their Gherkin steps; a Then step
 * that only added assertions fails when one of its assertions failed. YAML suites are reported as
 * features whose steps are ivy steps.
 */
final class CucumberReport {

    private CucumberReport() {
    }

    static String write(Tests tests) {
        List<Object> features = new ArrayList<>();
        for (TestSuite ts : tests.testSuites) {
            features.add(feature(ts));
        }
        return Json.write(features, Json.GO_INDENT);
    }

    private static Map<String, Object> feature(TestSuite ts) {
        Map<String, Object> f = new LinkedHashMap<>();
        GherkinInfo.Feature g = ts.feature;
        String id = Slug.make(ts.name).toLowerCase();
        f.put("uri", g == null ? ts.filepath : g.uri());
        f.put("id", id);
        f.put("keyword", g == null ? "Feature" : g.keyword());
        f.put("name", ts.name);
        f.put("description", ts.description);
        f.put("line", g == null ? 1 : g.line());
        f.put("tags", tags(g == null ? List.of() : g.tags(), g == null ? 1 : g.line() - 1));
        List<Object> elements = new ArrayList<>();
        for (TestCase tc : ts.testCases) {
            elements.add(scenario(id, tc));
        }
        f.put("elements", elements);
        return f;
    }

    private static List<Object> tags(List<String> names, int line) {
        List<Object> out = new ArrayList<>();
        for (String n : names) {
            out.add(Map.of("name", n, "line", line));
        }
        return out;
    }

    private static Map<String, Object> scenario(String featureId, TestCase tc) {
        Map<String, Object> e = new LinkedHashMap<>();
        GherkinInfo.Scenario g = tc.gherkin;
        e.put("id", featureId + ";" + tc.name.toLowerCase());
        e.put("keyword", g == null ? "Scenario" : g.keyword());
        e.put("name", g == null ? tc.originalName : g.name());
        e.put("description", g == null ? "" : g.description());
        e.put("line", g == null ? tc.lines.testCaseLine() : g.line());
        e.put("type", "scenario");
        e.put("tags", tags(g == null ? List.of() : g.tags(), g == null ? 0 : g.line() - 1));
        List<Object> steps = new ArrayList<>();
        if (g == null) {
            for (TestStepResult r : tc.testStepResults) {
                steps.add(step("* ", r.name, tc.findSourceLine(r.number - 1, -1), status(r, null), r.duration,
                        r.errorList()));
            }
            if (tc.hasSkipped() && tc.testStepResults.isEmpty()) {
                steps.add(step("* ", tc.originalName, tc.lines.testCaseLine(), "skipped", 0, List.of()));
            }
        } else {
            boolean blocked = !g.problems().isEmpty();
            for (GherkinInfo.Step s : g.steps()) {
                if (s.stepIndex() < 0) {
                    steps.add(step(s.keyword(), s.text(), s.line(), "undefined", 0, List.of()));
                    continue;
                }
                TestStepResult r = blocked ? null : result(tc, s.stepIndex());
                String status;
                List<Failure> errors;
                if (r == null) {
                    status = "skipped";
                    errors = List.of();
                } else {
                    errors = relevant(r, s, g);
                    status = s.createsStep() || !errors.isEmpty() ? status(r, errors) : "passed";
                    if (r.status == Status.SKIP) {
                        status = "skipped";
                    }
                }
                steps.add(step(s.keyword(), s.text(), s.line(), status, s.createsStep() && r != null ? r.duration : 0,
                        errors));
            }
        }
        e.put("steps", steps);
        return e;
    }

    /** The result of the step at an index (the first iteration of a ranged step). */
    private static TestStepResult result(TestCase tc, int stepIndex) {
        for (TestStepResult r : tc.testStepResults) {
            if (r.number == stepIndex + 1) {
                return r;
            }
        }
        return null;
    }

    /**
     * The failures to report on a Gherkin step: those of its own assertions, plus, for the step
     * that created the ivy step, those not caused by another Gherkin step's assertions.
     */
    private static List<Failure> relevant(TestStepResult r, GherkinInfo.Step s, GherkinInfo.Scenario g) {
        List<Failure> out = new ArrayList<>();
        for (Failure f : r.errorList()) {
            boolean own = s.assertions().stream().anyMatch(a -> a == f.declared);
            boolean other = false;
            for (GherkinInfo.Step o : g.steps()) {
                if (o != s && o.stepIndex() == s.stepIndex() && o.assertions().stream().anyMatch(a -> a == f.declared)) {
                    other = true;
                    break;
                }
            }
            if (own || (s.createsStep() && !other)) {
                out.add(f);
            }
        }
        return out;
    }

    private static String status(TestStepResult r, List<Failure> errors) {
        if (r.status == Status.SKIP) {
            return "skipped";
        }
        List<Failure> e = errors == null ? r.errorList() : errors;
        return e.isEmpty() ? "passed" : "failed";
    }

    private static Map<String, Object> step(String keyword, String name, int line, String status, double seconds,
            List<Failure> errors) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("keyword", keyword);
        s.put("name", name);
        s.put("line", line);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("duration", (long) (seconds * 1e9));
        if (!errors.isEmpty()) {
            result.put("error_message", String.join("\n", errors.stream().map(f -> f.value).toList()));
        }
        s.put("result", result);
        return s;
    }
}
