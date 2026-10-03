package xyz.fokion.ivy.core.gherkin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import xyz.fokion.ivy.core.gherkin.Gherkin.Background;
import xyz.fokion.ivy.core.gherkin.Gherkin.Examples;
import xyz.fokion.ivy.core.gherkin.Gherkin.Feature;
import xyz.fokion.ivy.core.gherkin.Gherkin.Rule;
import xyz.fokion.ivy.core.gherkin.Gherkin.Scenario;
import xyz.fokion.ivy.core.gherkin.Gherkin.Step;
import xyz.fokion.ivy.core.gherkin.Gherkin.Tag;
import xyz.fokion.ivy.core.gherkin.StepLibrary.Match;
import xyz.fokion.ivy.core.model.GherkinInfo;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestSuite;
import xyz.fokion.ivy.core.yaml.Yaml.TestCaseLines;

/**
 * Turns a feature into a suite: each scenario (each example row of an outline) becomes a test
 * case whose steps come from the step definitions matching its Gherkin steps, background first.
 */
public final class FeatureLoader {

    private FeatureLoader() {
    }

    public static TestSuite load(String uri, Feature feature, StepLibrary library, TagExpression filter) {
        TestSuite ts = new TestSuite();
        ts.name = feature.name();
        ts.description = feature.description();
        ts.feature = new GherkinInfo.Feature(uri, feature.keyword(), feature.name(), feature.description(),
                feature.line(), names(feature.tags()));
        List<Step> featureBackground = feature.background() == null ? List.of() : feature.background().steps();
        for (Scenario s : feature.scenarios()) {
            addScenario(ts, uri, s, feature.tags(), List.of(), featureBackground, library, filter);
        }
        for (Rule r : feature.rules()) {
            List<Step> background = new ArrayList<>(featureBackground);
            Background rb = r.background();
            if (rb != null) {
                background.addAll(rb.steps());
            }
            for (Scenario s : r.scenarios()) {
                addScenario(ts, uri, s, feature.tags(), r.tags(), background, library, filter);
            }
        }
        return ts;
    }

    private static List<String> names(List<Tag> tags) {
        return tags.stream().map(Tag::name).toList();
    }

    private static void addScenario(TestSuite ts, String uri, Scenario s, List<Tag> featureTags, List<Tag> ruleTags,
            List<Step> background, StepLibrary library, TagExpression filter) {
        Set<String> base = new LinkedHashSet<>();
        base.addAll(names(featureTags));
        base.addAll(names(ruleTags));
        base.addAll(names(s.tags()));
        if (!s.isOutline()) {
            if (filter.matches(base)) {
                ts.testCases.add(testCase(uri, s, s.name(), s.line(), new ArrayList<>(base), background, s.steps(),
                        library));
            }
            return;
        }
        int n = 0;
        for (Examples ex : s.examples()) {
            Set<String> tags = new LinkedHashSet<>(base);
            tags.addAll(names(ex.tags()));
            for (int r = 0; r < ex.rows().size(); r++) {
                n++;
                if (!filter.matches(tags)) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < ex.header().size(); c++) {
                    row.put(ex.header().get(c), c < ex.rows().get(r).size() ? ex.rows().get(r).get(c) : "");
                }
                List<Step> steps = new ArrayList<>();
                for (Step st : s.steps()) {
                    steps.add(new Step(st.keyword(), substitute(st.text(), row), st.line(),
                            st.docString() == null ? null : substitute(st.docString(), row), substitute(st.table(), row)));
                }
                String name = substitute(s.name(), row) + " (example " + n + ")";
                ts.testCases.add(testCase(uri, s, name, ex.rowLines().get(r), new ArrayList<>(tags), background, steps,
                        library));
            }
        }
    }

    private static String substitute(String text, Map<String, String> row) {
        String out = text;
        for (Map.Entry<String, String> e : row.entrySet()) {
            out = out.replace("<" + e.getKey() + ">", e.getValue());
        }
        return out;
    }

    private static List<List<String>> substitute(List<List<String>> table, Map<String, String> row) {
        if (table == null) {
            return null;
        }
        List<List<String>> out = new ArrayList<>();
        for (List<String> r : table) {
            out.add(r.stream().map(c -> substitute(c, row)).toList());
        }
        return out;
    }

    private static TestCase testCase(String uri, Scenario s, String name, int line, List<String> tags,
            List<Step> background, List<Step> scenarioSteps, StepLibrary library) {
        TestCase tc = new TestCase();
        tc.name = name;
        tc.id = uri + ":" + line;
        List<Step> all = new ArrayList<>(background);
        all.addAll(scenarioSteps);

        List<Map<String, Object>> steps = new ArrayList<>();
        List<Integer> stepLines = new ArrayList<>();
        List<List<Integer>> assertionLines = new ArrayList<>();
        List<GherkinInfo.Step> gherkinSteps = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Step g : all) {
            String text = g.keyword().strip() + " " + g.text();
            List<Match> matches = library.find(g.text());
            if (matches.isEmpty()) {
                problems.add("undefined step: \"" + text + "\" (" + uri + ":" + g.line() + "), define it with:\n"
                        + StepLibrary.snippet(g.text()));
                gherkinSteps.add(new GherkinInfo.Step(g.keyword(), g.text(), g.line(), -1, false, List.of()));
                continue;
            }
            if (matches.size() > 1) {
                StringBuilder sb = new StringBuilder("ambiguous step: \"" + text + "\" (" + uri + ":" + g.line()
                        + ") matches");
                matches.forEach(m -> sb.append("\n  ").append(m.definition().pattern()).append(" (")
                        .append(m.definition().source()).append(')'));
                problems.add(sb.toString());
                gherkinSteps.add(new GherkinInfo.Step(g.keyword(), g.text(), g.line(), -1, false, List.of()));
                continue;
            }
            Match match = matches.getFirst();
            // captured values are variables of the step: ${arg1}, ${url}, ${docstring}, ${rows}...
            Map<String, Object> args = new LinkedHashMap<>();
            match.args().forEach((k, v) -> args.put(k, literal(v)));
            if (g.docString() != null) {
                args.put("docstring", literal(g.docString()));
            }
            if (g.table() != null) {
                args.put("table", literalTable(g.table()));
                args.put("rows", rows(literalTable(g.table())));
            }
            List<Object> assertions = new ArrayList<>();
            for (Object a : match.definition().assertions()) {
                assertions.add(withArgs(a, args));
            }
            Map<String, Object> step = null;
            if (match.definition().step() != null) {
                step = new LinkedHashMap<>(match.definition().step());
                Map<String, Object> with = new LinkedHashMap<>(args);
                if (step.get("with") instanceof Map<?, ?> own) {
                    own.forEach((k, v) -> with.put(String.valueOf(k), v));
                }
                if (!with.isEmpty()) {
                    step.put("with", with);
                }
            }
            if (step != null) {
                step.putIfAbsent("name", literal(text));
                List<Object> own = new ArrayList<>();
                if (step.get("assertions") instanceof List<?> l) {
                    own.addAll(l);
                }
                own.addAll(assertions);
                if (!own.isEmpty()) {
                    step.put("assertions", own);
                }
                steps.add(step);
                stepLines.add(g.line());
                List<Integer> lines = new ArrayList<>();
                own.forEach(_ -> lines.add(g.line()));
                assertionLines.add(lines);
                gherkinSteps.add(new GherkinInfo.Step(g.keyword(), g.text(), g.line(), steps.size() - 1, true, assertions));
            } else {
                if (steps.isEmpty()) {
                    problems.add("step \"" + text + "\" (" + uri + ":" + g.line()
                            + ") only adds assertions, but no step comes before it");
                    gherkinSteps.add(new GherkinInfo.Step(g.keyword(), g.text(), g.line(), -1, false, List.of()));
                    continue;
                }
                Map<String, Object> previous = steps.getLast();
                List<Object> merged = new ArrayList<>();
                if (previous.get("assertions") instanceof List<?> l) {
                    merged.addAll(l);
                }
                merged.addAll(assertions);
                previous.put("assertions", merged);
                assertions.forEach(_ -> assertionLines.getLast().add(g.line()));
                gherkinSteps.add(new GherkinInfo.Step(g.keyword(), g.text(), g.line(), steps.size() - 1, false, assertions));
            }
        }
        tc.steps.addAll(steps);
        tc.lines = new TestCaseLines(line, stepLines, assertionLines);
        tc.gherkin = new GherkinInfo.Scenario(s.keyword(), name, s.description(), line, tags, gherkinSteps, problems);
        return tc;
    }

    /** Table rows as objects keyed by the header row. */
    private static List<Map<String, String>> rows(List<List<String>> table) {
        List<Map<String, String>> out = new ArrayList<>();
        if (table.isEmpty()) {
            return out;
        }
        List<String> header = table.getFirst();
        for (List<String> r : table.subList(1, table.size())) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                m.put(header.get(c), c < r.size() ? r.get(c) : "");
            }
            out.add(m);
        }
        return out;
    }

    /** A captured text as a value: {@code ${...}} in it is not a template. */
    private static String literal(String text) {
        return text.replace("${", "$${");
    }

    private static List<List<String>> literalTable(List<List<String>> table) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> r : table) {
            out.add(r.stream().map(FeatureLoader::literal).toList());
        }
        return out;
    }

    /** An assertion of an assertions-only definition, with the values its Gherkin step captured. */
    private static Object withArgs(Object assertion, Map<String, Object> args) {
        if (args.isEmpty()) {
            return assertion;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (assertion instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        } else {
            out.put("that", assertion);
        }
        Map<String, Object> with = new LinkedHashMap<>(args);
        if (out.get("with") instanceof Map<?, ?> own) {
            own.forEach((k, v) -> with.put(String.valueOf(k), v));
        }
        out.put("with", with);
        return out;
    }

}
