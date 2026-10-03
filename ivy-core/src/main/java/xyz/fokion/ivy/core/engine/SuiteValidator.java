package xyz.fokion.ivy.core.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import xyz.fokion.ivy.core.connector.ConfigurationBinder;
import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.expr.ExprException;
import xyz.fokion.ivy.core.expr.Expression;
import xyz.fokion.ivy.core.expr.Template;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestSuite;

/**
 * Checks a suite before it runs: every template and expression compiles, every variable it
 * reads is defined somewhere, and syntax from older versions is reported with its new form.
 * Errors name the file and line; the first error of each test case is reported, so one run
 * shows the errors of all test cases.
 * <p>
 * Warnings (the run goes on): a step reading a {@code result} field that its connector does not
 * report, such as {@code result.statuscode} for an HTTP step.
 */
final class SuiteValidator {

    /** Variables a step may read without the suite defining them. */
    static final Set<String> PROVIDED = Set.of("result", "index", "key", "value", "ivy", "input", "env", "cases");

    private static final Pattern GO_TEMPLATE = Pattern.compile("\\{\\{-?\\s*\\.");
    private static final Pattern MATCHER_ASSERTION = Pattern.compile("^\\s*\\S+\\s+(Should|Must)[A-Z]\\w*.*");

    private SuiteValidator() {
    }

    /**
     * Validates the test cases of a suite.
     *
     * @param globals the names of the variables given on the command line
     */
    static void validate(TestSuite ts, Set<String> globals, Ivy ivy) throws IvyException {
        List<String> errors = new ArrayList<>();
        Set<String> known = new HashSet<>(PROVIDED);
        known.addAll(globals);
        known.addAll(ts.vars.keySet());
        for (TestCase tc : ts.testCases) {
            known.addAll(tc.vars.keySet());
            for (Map<String, Object> step : tc.steps) {
                if (step.get("set") instanceof Map<?, ?> set) {
                    set.keySet().forEach(k -> known.add(String.valueOf(k)));
                }
            }
        }
        Set<String> missing = new TreeSet<>();
        Set<String> suiteRoots = new HashSet<>();
        String where = ts.filepath + ":1";
        try {
            for (Object v : ts.vars.values()) {
                Template.collectRoots(v, suiteRoots);
            }
        } catch (ExprException e) {
            errors.add(where + ": " + e.getMessage());
        }
        suiteRoots.stream().filter(r -> !known.contains(r)).forEach(missing::add);
        for (TestCase tc : ts.testCases) {
            if (tc.hasSkipped()) {
                continue;
            }
            Set<String> roots = new HashSet<>();
            String at = ts.filepath + ":" + Math.max(tc.lines.testCaseLine(), 1);
            try {
                for (Object v : tc.vars.values()) {
                    Template.collectRoots(v, roots);
                }
                if (!tc.condition.isBlank()) {
                    roots.addAll(Expression.compile(tc.condition).roots());
                }
            } catch (ExprException e) {
                errors.add(at + ": test case \"" + tc.originalName + "\": " + e.getMessage());
                continue;
            }
            for (int i = 0; i < tc.steps.size(); i++) {
                String stepAt = ts.filepath + ":" + Math.max(tc.findSourceLine(i, -1), 1);
                String prefix = stepAt + ": test case \"" + tc.originalName + "\", step #" + (i + 1) + ": ";
                Set<String> local = new HashSet<>();
                try {
                    if (i == 0 && stepType(tc.steps.get(i)).isEmpty() && readsResult(tc.steps.get(i))) {
                        throw new IllegalArgumentException("a step without a type checks the result of the step "
                                + "before it, and this one is the first: give it a 'type', or a 'script' or 'command'");
                    }
                    validateStep(tc.steps.get(i), roots, local, ivy).forEach(w -> ivy.warn(prefix + w));
                } catch (ExprException | IllegalArgumentException | IvyException e) {
                    // the first error of a test case: the next ones often follow from it
                    errors.add(prefix + e.getMessage());
                    break;
                }
                roots.removeAll(local);
            }
            roots.stream().filter(r -> !known.contains(r)).forEach(missing::add);
        }
        if (!missing.isEmpty()) {
            errors.add("missing variables " + missing.stream().toList().toString().replace(",", "")
                    + " in " + ts.filepath + ": declare them in the suite's vars, or give them when it runs "
                    + "(--var, --var-from-file, or vars with ivy mcp)");
        }
        if (!errors.isEmpty()) {
            throw new IvyException(String.join("\n", errors));
        }
    }

    /**
     * Validates one step; adds the variables it reads to {@code roots} and the variables only it
     * defines ({@code with}) to {@code local}. Returns its warnings.
     */
    static List<String> validateStep(Map<String, Object> step, Set<String> roots, Set<String> local, Ivy ivy)
            throws IvyException {
        legacy(step);
        waiting(step);
        // written values are checked now; templates once they are rendered
        for (String key : List.of("retry", "delay", "timeout", "within", "every")) {
            if (step.get(key) == null || step.get(key) instanceof String s && Template.has(s)) {
                continue;
            }
            if (key.equals("retry")) {
                Ivy.intValue(step, key);
            } else {
                Ivy.secondsValue(step, key);
            }
        }
        Set<String> stepRoots = new HashSet<>();
        // the result fields read after the step: assertions, retryIf, set, info
        Set<String> resultFields = new LinkedHashSet<>();
        if (step.get("with") instanceof Map<?, ?> with) {
            Template.collectRoots(with, stepRoots);
            with.keySet().forEach(k -> local.add(String.valueOf(k)));
        } else if (step.containsKey("with")) {
            throw new IllegalArgumentException("'with' must be a map of variables");
        }
        Object range = step.get("range");
        if (range instanceof String s) {
            stepRoots.addAll(expressionOrTemplateRoots(s));
        } else if (range != null) {
            Template.collectRoots(range, stepRoots);
        }
        for (String key : List.of("if", "retryIf", "until")) {
            Object v = step.get(key);
            if (v == null) {
                continue;
            }
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException("'" + key + "' must be an expression, got " + v);
            }
            stepRoots.addAll(Expression.compile(s).roots());
            if (!key.equals("if")) {
                resultFields.addAll(Expression.compile(s).members("result"));
            }
        }
        if (step.get("assertions") instanceof List<?> assertions) {
            for (Object a : assertions) {
                AssertionChecker.Assertion assertion = AssertionChecker.Assertion.of(a);
                if (MATCHER_ASSERTION.matcher(assertion.expression()).matches()) {
                    throw new IllegalArgumentException("assertion \"" + assertion.expression()
                            + "\": assertions are expressions now, for instance 'result.exitCode == 0', "
                            + "'result.stdout contains \"ok\"' or 'result.body.items.length > 2'");
                }
                Set<String> r = expressionOrTemplateRoots(assertion.expression());
                resultFields.addAll(expressionOrTemplateMembers(assertion.expression()));
                if (assertion.with() != null) {
                    Template.collectRoots(assertion.with(), stepRoots);
                    r.removeAll(assertion.with().keySet());
                }
                stepRoots.addAll(r);
            }
        } else if (step.containsKey("assertions")) {
            throw new IllegalArgumentException("'assertions' must be a list");
        }
        if (step.get("set") instanceof Map<?, ?> set) {
            for (Object v : set.values()) {
                if (v instanceof String s) {
                    stepRoots.addAll(expressionOrTemplateRoots(s));
                    resultFields.addAll(expressionOrTemplateMembers(s));
                } else {
                    Template.collectRoots(v, stepRoots);
                }
            }
        } else if (step.containsKey("set")) {
            throw new IllegalArgumentException("'set' must be a map of names to expressions");
        }
        Object info = step.get("info");
        if (info != null) {
            for (String s : Ivy.stringSliceValue(step, "info")) {
                stepRoots.addAll(Template.compile(s).roots());
                resultFields.addAll(Template.compile(s).members("result"));
            }
        }
        for (Map.Entry<String, Object> e : step.entrySet()) {
            if (!StepProcessor.CONTROL_KEYS.contains(e.getKey())) {
                Template.collectRoots(e.getValue(), stepRoots);
            }
        }
        if (step.get("type") instanceof String type && !Template.has(type) && !type.isEmpty()) {
            ivy.checkExecutor(type);
        }
        stepRoots.removeAll(local);
        roots.addAll(stepRoots);
        List<String> warnings = new ArrayList<>(unknownProperties(step, ivy));
        warnings.addAll(unknownResultFields(step, resultFields, ivy));
        return warnings;
    }

    /** Checks that a step waits ({@code until}) or retries ({@code retry}), not both, and waits for something. */
    private static void waiting(Map<String, Object> step) {
        boolean until = step.containsKey("until");
        if (!until && (step.containsKey("within") || step.containsKey("every"))) {
            throw new IllegalArgumentException("'within' and 'every' go with 'until', the condition the step waits for");
        }
        if (!until) {
            return;
        }
        if (step.containsKey("retry") || step.containsKey("retryIf") || step.containsKey("delay")) {
            throw new IllegalArgumentException("a step waits with 'until' ('within', 'every') or retries with 'retry' "
                    + "('retryIf', 'delay'), not both");
        }
        if (stepType(step).isEmpty()) {
            throw new IllegalArgumentException("a step without a type runs nothing, so its result never changes: "
                    + "put 'until' on the step that gets the result");
        }
        if (step.get("every") != null && !(step.get("every") instanceof String e && Template.has(e))) {
            try {
                if (Ivy.secondsValue(step, "every").isZero()) {
                    throw new IllegalArgumentException("'every' must be more than 0 seconds");
                }
            } catch (IvyException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
    }

    /** Whether a step reads {@code result}, in its assertions, info, set, retryIf or until. */
    private static boolean readsResult(Map<String, Object> step) {
        List<String> read = new ArrayList<>();
        if (step.get("assertions") instanceof List<?> assertions) {
            assertions.forEach(a -> read.add(AssertionChecker.Assertion.of(a).expression()));
        }
        if (step.get("set") instanceof Map<?, ?> set) {
            set.values().forEach(v -> read.add(String.valueOf(v)));
        }
        read.addAll(Ivy.stringSliceValue(step, "info"));
        for (String key : List.of("retryIf", "until")) {
            if (step.get(key) instanceof String r) {
                read.add(r);
            }
        }
        return read.stream().anyMatch(s -> expressionOrTemplateRoots(s).contains("result"));
    }

    /** The keys of a step that are not properties of a connector. */
    static final Set<String> STEP_KEYS = Set.of("type", "name", "retry", "retryIf", "delay", "timeout", "until", "within",
            "every", "assertions", "if", "set", "info", "range", "with");

    /** The type of a step: {@code exec} for a script or command without one; empty when it has none. */
    private static String stepType(Map<String, Object> step) {
        String type = step.get("type") instanceof String t ? t : "";
        if (type.isEmpty() && (step.containsKey("script") || step.containsKey("command"))) {
            type = "exec";
        }
        return type;
    }

    /**
     * Warns about keys that are neither step settings nor properties of the step's connector, such as {@code methd}
     * for {@code method}: the connector would ignore them.
     */
    private static List<String> unknownProperties(Map<String, Object> step, Ivy ivy) {
        String type = stepType(step);
        if (type.isEmpty()) {
            return step.keySet().stream().filter(k -> !STEP_KEYS.contains(k))
                    .map(k -> "\"" + k + "\" is ignored: the step has no type, so it runs nothing and only checks "
                            + "the variables and the result of the step before it")
                    .toList();
        }
        if (Template.has(type)) {
            return List.of();
        }
        List<String> properties = ivy.connectorInfo(type)
                .map(i -> i.properties().stream().map(p -> p.name()).toList())
                .orElse(List.of());
        if (properties.isEmpty()) {
            // a user executor: its keys are its inputs
            return List.of();
        }
        // connectors match their properties ignoring case and underscores; the step settings are read as written
        Set<String> known = new HashSet<>();
        properties.forEach(p -> known.add(ConfigurationBinder.normalize(p)));
        List<String> warnings = new ArrayList<>();
        for (String key : step.keySet()) {
            if (STEP_KEYS.contains(key) || known.contains(ConfigurationBinder.normalize(key))) {
                continue;
            }
            List<String> candidates = new ArrayList<>(STEP_KEYS);
            candidates.addAll(properties);
            String hint = closest(key, candidates);
            warnings.add("\"" + key + "\" is not a property of " + type + " steps and is ignored; "
                    + (hint != null ? "did you mean \"" + hint + "\"?" : type + " steps take: " + String.join(", ", properties)));
        }
        return warnings;
    }

    /** The candidate closest to a mistyped key, within two edits; {@code null} when none is that close. */
    private static String closest(String key, List<String> candidates) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        String best = null;
        int bestDistance = 3;
        for (String c : candidates) {
            int d = distance(k, c.toLowerCase(java.util.Locale.ROOT));
            if (d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        return best;
    }

    /** Levenshtein distance. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] t = previous;
            previous = current;
            current = t;
        }
        return previous[b.length()];
    }

    /** Warns about result fields the connector of the step does not report. */
    private static List<String> unknownResultFields(Map<String, Object> step, Set<String> read, Ivy ivy) {
        String type = stepType(step);
        if (read.isEmpty() || type.isEmpty() || Template.has(type)) {
            return List.of();
        }
        Map<String, String> known = ivy.connectorInfo(type).map(i -> i.resultFields()).orElse(Map.of());
        if (known.isEmpty()) {
            // a user executor, or a connector that does not describe its result
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (String field : read) {
            if (!known.containsKey(field)) {
                warnings.add("result." + field + " is not a field of " + type + " results, which are: "
                        + String.join(", ", known.keySet()));
            }
        }
        return warnings;
    }

    /** The {@code result} fields a string reads, as a template or an expression. */
    private static Set<String> expressionOrTemplateMembers(String s) {
        return Template.has(s) ? Template.compile(s).members("result") : Expression.compile(s).members("result");
    }

    /** A string that is a template when it has {@code ${...}}, an expression otherwise. */
    private static Set<String> expressionOrTemplateRoots(String s) {
        if (Template.has(s)) {
            Template t = Template.compile(s);
            return new HashSet<>(t.roots());
        }
        return new HashSet<>(Expression.compile(s).roots());
    }

    /** Reports the syntax of older versions with its replacement. */
    private static void legacy(Map<String, Object> step) {
        if (step.containsKey("skip")) {
            throw new IllegalArgumentException("'skip' is now 'if': an expression, the step runs when it is true "
                    + "(for instance 'if: env.CI == \"true\"')");
        }
        if (step.containsKey("retry_if")) {
            throw new IllegalArgumentException("'retry_if' is now 'retryIf': an expression, retries go on while it is true");
        }
        if (step.containsKey("vars")) {
            throw new IllegalArgumentException("step 'vars' is now 'set': names and expressions evaluated after the step, "
                    + "for instance 'set: {id: result.body.id}'");
        }
        checkTemplates(step);
    }

    private static void checkTemplates(Object v) {
        switch (v) {
            case String s when GO_TEMPLATE.matcher(s).find() -> throw new IllegalArgumentException("\"" + s
                    + "\": templates are written ${...} now, {{.name}} becomes ${name}");
            case Map<?, ?> m -> m.values().forEach(SuiteValidator::checkTemplates);
            case List<?> l -> l.forEach(SuiteValidator::checkTemplates);
            case null, default -> {
            }
        }
    }

    /** Checks the steps of a user executor: syntax only, its variables come from the caller. */
    static void validateExecutor(UserExecutor ux, Ivy ivy) throws IvyException {
        for (int i = 0; i < ux.steps().size(); i++) {
            try {
                String prefix = ux.filename() + ": executor \"" + ux.executor() + "\", step #" + (i + 1) + ": ";
                validateStep(ux.steps().get(i), new HashSet<>(), new HashSet<>(), ivy).forEach(w -> ivy.warn(prefix + w));
            } catch (ExprException | IllegalArgumentException | IvyException e) {
                throw new IvyException(ux.filename() + ": executor \"" + ux.executor() + "\", step #" + (i + 1) + ": "
                        + e.getMessage(), e);
            }
        }
        try {
            Template.collectRoots(ux.output(), new HashSet<>());
            Template.collectRoots(ux.input(), new HashSet<>());
            checkTemplates(ux.output());
        } catch (ExprException | IllegalArgumentException e) {
            throw new IvyException(ux.filename() + ": executor \"" + ux.executor() + "\": " + e.getMessage(), e);
        }
    }
}
