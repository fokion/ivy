package xyz.fokion.ivy.core.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

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
                    + " in " + ts.filepath);
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
        for (String key : List.of("if", "retryIf")) {
            Object v = step.get(key);
            if (v == null) {
                continue;
            }
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException("'" + key + "' must be an expression, got " + v);
            }
            stepRoots.addAll(Expression.compile(s).roots());
            if (key.equals("retryIf")) {
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
        return unknownResultFields(step, resultFields, ivy);
    }

    /** Warns about result fields the connector of the step does not report. */
    private static List<String> unknownResultFields(Map<String, Object> step, Set<String> read, Ivy ivy) {
        String type = step.get("type") instanceof String t ? t : "";
        if (type.isEmpty() && (step.containsKey("script") || step.containsKey("command"))) {
            type = "exec";
        }
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
            } catch (ExprException | IllegalArgumentException e) {
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
