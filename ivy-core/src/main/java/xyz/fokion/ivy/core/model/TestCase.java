package xyz.fokion.ivy.core.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.yaml.Yaml.TestCaseLines;

/** A test case as read from the suite, with its results once run. */
public final class TestCase {
    // input
    public String name = "";
    /** The declared variables; once run, their values. */
    public Map<String, Object> vars = new LinkedHashMap<>();
    /** {@code if}: an expression; the test case runs when it is true. */
    public String condition = "";
    /** The steps as parsed from YAML; their templates are rendered just before each one runs. */
    public List<Map<String, Object>> steps = new ArrayList<>();
    public String id = "";

    // computed
    public String originalName = "";
    public int number;
    public List<Skipped> skipped;
    public Status status;
    public double duration;
    public OffsetDateTime start;
    public OffsetDateTime end;
    public final List<TestStepResult> testStepResults = new ArrayList<>();
    /** The values set by the steps ({@code set:}). */
    public Map<String, Object> computedVars = new LinkedHashMap<>();
    public final List<String> computedVerbose = new ArrayList<>();
    public boolean isExecutor;
    public boolean isEvaluated;
    public TestCaseLines lines = TestCaseLines.EMPTY;
    /** The Gherkin scenario this test case was built from, or null. */
    public GherkinInfo.Scenario gherkin;

    public void addSkipped(String value) {
        if (skipped == null) {
            skipped = new ArrayList<>();
        }
        skipped.add(new Skipped(value));
    }

    public boolean hasSkipped() {
        return skipped != null && !skipped.isEmpty();
    }

    /** The best known source line: the assertion, else the step, else the test case. */
    public int findSourceLine(int stepNumber, int assertionIndex) {
        List<List<Integer>> assertionLines = lines.assertionLines();
        if (assertionIndex >= 0 && stepNumber < assertionLines.size()) {
            List<Integer> l = assertionLines.get(stepNumber);
            if (assertionIndex < l.size() && l.get(assertionIndex) > 0) {
                return l.get(assertionIndex);
            }
        }
        List<Integer> stepLines = lines.stepLines();
        if (stepNumber < stepLines.size() && stepLines.get(stepNumber) > 0) {
            return stepLines.get(stepNumber);
        }
        return Math.max(lines.testCaseLine(), 0);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("vars", vars);
        if (!condition.isEmpty()) {
            m.put("if", condition);
        }
        m.put("steps", steps);
        m.put("set", computedVars);
        m.put("id", id);
        m.put("skipped", xyz.fokion.ivy.core.model.Json.list(skipped, Skipped::toJson));
        m.put("status", Status.name(status));
        m.put("duration", duration);
        m.put("start", xyz.fokion.ivy.core.model.Json.time(start));
        m.put("end", xyz.fokion.ivy.core.model.Json.time(end));
        List<Object> results = new ArrayList<>();
        testStepResults.forEach(r -> results.add(r.toJson()));
        m.put("results", results);
        if (gherkin != null) {
            m.put("gherkin", gherkin.toJson());
        }
        return m;
    }
}
