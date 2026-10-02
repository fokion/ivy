package xyz.fokion.ivy.core.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import xyz.fokion.ivy.core.assertion.Assertions.AssertException;
import xyz.fokion.ivy.core.assertion.Assertions.AssertFunc;
import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.dump.Dump;
import xyz.fokion.ivy.core.engine.AssertionChecker.ParseException;
import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.core.model.AssertionsApplied;
import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestStepResult;
import xyz.fokion.ivy.core.template.Interpolator;
import xyz.fokion.ivy.core.template.Interpolator.InterpolationException;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.core.util.Slug;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.core.yaml.Yaml.YamlException;
import xyz.fokion.ivy.spi.util.GoFormat;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Runs the steps of a test case: port of venom's {@code runTestSteps}, {@code RunTestStep} and
 * {@code RunUserExecutor}.
 */
final class StepProcessor {

    private final Ivy ivy;

    StepProcessor(Ivy ivy) {
        this.ivy = ivy;
    }

    private IvyLog log() {
        return ivy.log;
    }

    private static Map<String, Object> clone(Map<String, Object> m) {
        return new LinkedHashMap<>(m);
    }

    private static void addAllWithPrefix(Map<String, Object> target, String prefix, Map<String, Object> source) {
        if (source != null) {
            source.forEach((k, v) -> target.put(prefix + "." + k, v));
        }
    }

    private record Range(boolean enabled, List<Map.Entry<String, Object>> items) {
    }

    /** Runs the steps; {@code parent} is the step result of the calling step for a user executor. */
    void runTestSteps(RunContext ctx, TestCase tc, TestStepResult parent) {
        List<String> skipResults;
        try {
            skipResults = ivy.checker.testConditionalStatement(ctx, tc, tc.skip, tc.vars, "skipping testcase %s: %s");
        } catch (ParseException e) {
            log().error(ctx.fields(), "unable to evaluate \"skip\" assertions: " + e.getMessage());
            TestStepResult r = new TestStepResult();
            r.appendError(e.getMessage());
            tc.testStepResults.add(r);
            return;
        }
        if (!skipResults.isEmpty()) {
            tc.status = Status.SKIP;
            for (String s : skipResults) {
                tc.addSkipped(s);
                log().warn(ctx.fields(), s);
            }
            return;
        }

        Map<String, ConnectorFacade.Session> sessions = new LinkedHashMap<>();
        List<TestStepResult> sessionResults = new ArrayList<>();
        Map<String, Object> previousStepVars = new LinkedHashMap<>();
        boolean fromUserExecutor = parent != null;
        try {
            loopRawSteps:
            for (int stepIndex = 0; stepIndex < tc.rawTestSteps.size(); stepIndex++) {
                String rawStep = tc.rawTestSteps.get(stepIndex);
                Map<String, Object> stepVars = clone(tc.vars);
                stepVars.putAll(previousStepVars);
                addAllWithPrefix(stepVars, tc.name, tc.computedVars);
                int stepNumber = stepIndex + 1;
                stepVars.put("venom.teststep.number", (long) stepNumber);

                Range ranged;
                try {
                    ranged = parseRanged(ctx, rawStep, stepVars);
                } catch (IvyException e) {
                    log().error(ctx.fields(), "unable to parse \"range\" attribute: " + e.getMessage());
                    TestStepResult r = new TestStepResult();
                    r.appendError(e.getMessage());
                    tc.testStepResults.add(r);
                    return;
                }

                for (int rangedIndex = 0; rangedIndex < ranged.items().size(); rangedIndex++) {
                    Map.Entry<String, Object> rangedData = ranged.items().get(rangedIndex);
                    TestStepResult tsResult = new TestStepResult();
                    tc.testStepResults.add(tsResult);
                    if (ranged.enabled()) {
                        log().debug(ctx.fields(), "processing range index: " + rangedIndex);
                        stepVars.put("index", (long) rangedIndex);
                        stepVars.put("key", rangedData.getKey());
                        stepVars.put("value", rangedData.getValue());
                    }

                    Map<String, String> vars = Dump.dumpStringPreserveCase(stepVars);
                    for (Map.Entry<String, String> e : vars.entrySet()) {
                        try {
                            e.setValue(Interpolator.interpolate(e.getValue(), vars));
                        } catch (InterpolationException ex) {
                            tsResult.appendError(ex.getMessage());
                            log().error(ctx.fields(), "unable to interpolate variable \"" + e.getKey() + "\": " + ex.getMessage());
                            return;
                        }
                    }
                    // values may contain double quotes, escape them to keep the step JSON valid
                    vars.replaceAll((k, v) -> PartialYaml.escapeQuotes(v));

                    String content;
                    try {
                        content = Interpolator.interpolate(rawStep, vars);
                    } catch (InterpolationException ex) {
                        tsResult.appendError(ex.getMessage());
                        log().error(ctx.fields(), "unable to interpolate step: " + ex.getMessage());
                        return;
                    }
                    if (ranged.enabled()) {
                        log().info(ctx.fields(), "Step #" + stepNumber + "-" + rangedIndex + " content is: " + content);
                    } else {
                        log().info(ctx.fields(), "Step #" + stepNumber + " content is: " + content);
                    }
                    tsResult.raw = yamlBytes(rawStep);

                    Map<String, Object> step;
                    try {
                        step = Yaml.loadMap(content);
                    } catch (YamlException e) {
                        tsResult.appendError(e.getMessage());
                        log().error(ctx.fields(), "unable to parse step #" + stepNumber + ": " + e.getMessage());
                        log().error(ctx.fields(), content);
                        printTestStepResult(tc, tsResult, parent, stepNumber, false);
                        break loopRawSteps;
                    }
                    tsResult.interpolated = Yaml.dump(step).getBytes(StandardCharsets.UTF_8);
                    tsResult.number = stepNumber;
                    tsResult.rangedIndex = rangedIndex;
                    tsResult.rangedEnable = ranged.enabled();
                    tsResult.inputVars = vars;

                    ExecutorRunner e;
                    try {
                        e = ivy.executorRunner(step);
                    } catch (IvyException ex) {
                        tsResult.appendError(ex.getMessage());
                        log().error(ctx.fields(), "unable to get executor: " + ex.getMessage());
                        printTestStepResult(tc, tsResult, parent, stepNumber, false);
                        break loopRawSteps;
                    }
                    RunContext stepCtx = ctx.withVars(Dump.dumpStringPreserveCase(stepVars));

                    ConnectorFacade.Session session = null;
                    if (e.facade() != null) {
                        session = sessions.get(e.name());
                        if (session == null) {
                            try {
                                session = e.facade().openSession(new DefaultStepContext(stepCtx, step, log()));
                            } catch (Exception ex) {
                                tsResult.appendError(message(ex));
                                log().error(stepCtx.fields(), "unable to setup executor: " + message(ex));
                                break;
                            }
                            sessions.put(e.name(), session);
                            sessionResults.add(tsResult);
                        }
                    }
                    tsResult.name = stepName(e, step, ranged, rangedData);
                    if (ivy.verbose >= 1 && !fromUserExecutor) {
                        ivy.print(" \t\t• " + tsResult.name);
                    }

                    Map<String, Object> skipVars = clone(tc.vars);
                    addAllWithPrefix(skipVars, tc.name, previousStepVars);
                    try {
                        if (parseSkip(stepCtx, tc, tsResult, rawStep, stepNumber, skipVars)) {
                            tsResult.status = Status.SKIP;
                        } else {
                            tsResult.start = OffsetDateTime.now();
                            tsResult.status = Status.RUN;
                            runTestStep(stepCtx, e, session, step, tc, tsResult, stepNumber, rangedIndex);
                            tsResult.status = tsResult.hasErrors() || !tsResult.assertionsApplied.ok ? Status.FAIL : Status.PASS;
                            tsResult.end = OffsetDateTime.now();
                            tsResult.duration = Ivy.seconds(tsResult.start, tsResult.end);
                        }
                    } catch (ParseException ex) {
                        tsResult.appendError(ex.getMessage());
                        tsResult.status = Status.FAIL;
                    }

                    if (tsResult.status != Status.FAIL) {
                        log().info(ctx.fields(), "Step \"" + tsResult.name + "\" result is \"" + tsResult.status + "\"");
                    } else {
                        log().error(ctx.fields(), "Step \"" + tsResult.name + "\" result is \"" + tsResult.status + "\"");
                        log().error(ctx.fields(), "Errors: ");
                        boolean required = false;
                        for (Failure f : tsResult.errorList()) {
                            log().error(ctx.fields(), f.toString());
                            required = required || f.assertionRequired || ivy.stopOnFailure;
                        }
                        log().error(ctx.fields(), "teststep output vars are: " + GoFormat.sprint(tsResult.computedVars));
                        if (required) {
                            tsResult.appendFailure(Failures.newFailure(stepCtx, tc, stepNumber, rangedIndex, -1, "",
                                    "At least one required assertion failed, skipping remaining steps"));
                            printTestStepResult(tc, tsResult, parent, stepNumber, true);
                            return;
                        }
                        printTestStepResult(tc, tsResult, parent, stepNumber, false);
                        continue;
                    }

                    Map<String, Object> allVars = clone(tc.vars);
                    allVars.putAll(tsResult.computedVars);
                    Map<String, Object> assign;
                    try {
                        assign = processVariableAssignments(ctx, tc.name, allVars, rawStep);
                    } catch (IvyException ex) {
                        tsResult.appendError(ex.getMessage());
                        log().error(ctx.fields(), "unable to process variable assignments: " + ex.getMessage());
                        printTestStepResult(tc, tsResult, parent, stepNumber, false);
                        break loopRawSteps;
                    }
                    printTestStepResult(tc, tsResult, parent, stepNumber, false);
                    tc.computedVars.putAll(assign);
                    previousStepVars.putAll(assign);
                }
            }
        } finally {
            int i = 0;
            for (Map.Entry<String, ConnectorFacade.Session> s : sessions.entrySet()) {
                TestStepResult owner = sessionResults.get(i++);
                try {
                    s.getValue().close();
                } catch (Exception ex) {
                    owner.appendError(message(ex));
                    log().error(ctx.fields(), "unable to teardown executor: " + message(ex));
                }
            }
        }
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    private static byte[] yamlBytes(String json) {
        return Yaml.dump(Json.parse(json)).getBytes(StandardCharsets.UTF_8);
    }

    /** The step name: the executor name, or the {@code name} attribute; ranged steps add their key. */
    private static String stepName(ExecutorRunner e, Map<String, Object> step, Range ranged, Map.Entry<String, Object> data) {
        String name = e.name();
        if (step.get("name") instanceof String s) {
            name = s;
        }
        if (ranged.enabled()) {
            name = name + " (range=" + data.getKey() + ")";
        }
        return name;
    }

    private void printTestStepResult(TestCase tc, TestStepResult ts, TestStepResult parent, int stepNumber,
            boolean mustAssertionFailed) {
        if (parent != null || ivy.verbose < 1) {
            return;
        }
        if (ts.hasErrors()) {
            ivy.println(" " + ivy.red(Status.FAIL));
            if (ts.computedInfo != null) {
                ts.computedInfo.forEach(i -> ivy.println(" \t\t  " + ivy.cyan("[info]") + " " + ivy.cyan(i)));
            }
            ts.errorList().forEach(f -> ivy.println(" \t\t  " + ivy.yellow(f.value)));
            if (mustAssertionFailed) {
                int skipped = tc.rawTestSteps.size() - stepNumber;
                ivy.println(" \t\t  " + ivy.gray(skipped == 1 ? skipped + " other step was skipped"
                        : skipped + " other steps were skipped"));
            }
        } else if (ts.status == Status.SKIP) {
            ivy.println(" " + ivy.gray(Status.SKIP));
        } else {
            if (ts.retries == 0) {
                ivy.println(" " + ivy.green(Status.PASS));
            } else {
                ivy.println(" " + ivy.green(Status.PASS) + " (after " + ts.retries + " attempts)");
            }
            if (ts.computedInfo != null) {
                ts.computedInfo.forEach(i -> ivy.println(" \t\t  " + ivy.cyan("[info]") + " " + ivy.cyan(i)));
            }
        }
    }

    // ------------------------------------------------------------ skip, range, assignments

    private boolean parseSkip(RunContext ctx, TestCase tc, TestStepResult ts, String rawStep, int stepNumber,
            Map<String, Object> vars) throws ParseException {
        Object raw = Json.parse(rawStep);
        List<String> conditions = raw instanceof Map<?, ?> m ? Ivy.stringList(m.get("skip")) : List.of();
        if (conditions.isEmpty()) {
            return false;
        }
        List<String> results;
        try {
            results = ivy.checker.testConditionalStatement(ctx, tc, conditions, vars,
                    "skipping testcase %s step #" + stepNumber + ": %s");
        } catch (ParseException e) {
            log().error(ctx.fields(), "unable to evaluate \"skip\" assertions: " + e.getMessage());
            throw e;
        }
        for (String r : results) {
            ts.addSkipped(r);
            log().warn(ctx.fields(), r);
        }
        return !results.isEmpty();
    }

    /** venom's {@code parseRanged}: iterations over an array, a map or a number of items. */
    private Range parseRanged(RunContext ctx, String rawStep, Map<String, Object> stepVars) throws IvyException {
        Object step = Json.parse(rawStep);
        Object content = step instanceof Map<?, ?> m ? m.get("range") : null;
        if (content == null) {
            List<Map.Entry<String, Object>> single = new ArrayList<>();
            single.add(new java.util.AbstractMap.SimpleEntry<>("", null));
            return new Range(false, single);
        }
        if (content instanceof String raw) {
            log().debug(ctx.fields(), "attempting to parse range expression");
            if (raw.isEmpty()) {
                throw new IvyException("range expression has been specified without any data");
            }
            Object parsed = Json.tryParse(raw);
            if (parsed != null || "null".equals(raw.strip())) {
                content = parsed;
            } else {
                log().debug(ctx.fields(), "attempting to template range expression and parse it again");
                Map<String, String> vars = Dump.dumpStringPreserveCase(stepVars);
                vars.replaceAll((k, v) -> v.replace("\"", "\\\""));
                try {
                    String templated = Interpolator.interpolate(rawStep, vars);
                    Object again = Json.parse(templated);
                    content = again instanceof Map<?, ?> m ? m.get("range") : null;
                    if (content instanceof String s) {
                        Object p2 = Json.tryParse(s);
                        if (p2 == null) {
                            log().warn(ctx.fields(), "failed to parse range expression when parsing raw string into data");
                            throw new IvyException("unable to parse range expression: unable to transform string data into a supported range expression type");
                        }
                        content = p2;
                    }
                } catch (InterpolationException e) {
                    log().warn(ctx.fields(), "failed to parse range expression when templating variables: " + e.getMessage());
                } catch (Json.JsonException e) {
                    log().warn(ctx.fields(), "failed to parse range expression when parsing data into raw string: " + e.getMessage());
                }
            }
        }
        List<Map.Entry<String, Object>> items = new ArrayList<>();
        switch (content) {
            case List<?> l -> {
                log().debug(ctx.fields(), "\"range\" data is array-like");
                for (int i = 0; i < l.size(); i++) {
                    items.add(new java.util.AbstractMap.SimpleEntry<>(Integer.toString(i), l.get(i)));
                }
            }
            case Number n -> {
                log().debug(ctx.fields(), "\"range\" data is number-like");
                int upper = (int) n.doubleValue();
                for (int i = 0; i < upper; i++) {
                    items.add(new java.util.AbstractMap.SimpleEntry<>(Integer.toString(i), (long) i));
                }
            }
            case Map<?, ?> m -> {
                log().debug(ctx.fields(), "\"range\" data is map-like");
                m.forEach((k, v) -> items.add(new java.util.AbstractMap.SimpleEntry<>(String.valueOf(k), v)));
            }
            case null, default -> throw new IvyException("\"range\" was provided an unsupported type "
                    + goType(content));
        }
        return new Range(true, items);
    }

    private static String goType(Object v) {
        return switch (v) {
            case null -> "<nil>";
            case String s -> "string";
            case Boolean b -> "bool";
            default -> v.getClass().getSimpleName();
        };
    }

    /** venom's {@code processVariableAssignments}: the step {@code vars} section. */
    static Map<String, Object> processVariableAssignments(RunContext ctx, String tcName, Map<String, Object> tcVars,
            String rawStep) throws IvyException {
        Object step = Json.parse(rawStep);
        Map<String, Object> result = new LinkedHashMap<>();
        if (!(step instanceof Map<?, ?> m) || !(m.get("vars") instanceof Map<?, ?> assignments) || assignments.isEmpty()) {
            return result;
        }
        for (Map.Entry<?, ?> entry : assignments.entrySet()) {
            String varname = String.valueOf(entry.getKey());
            Map<String, Object> assignment = Cast.toStringMap(entry.getValue());
            String from = Cast.toString(assignment.get("from"));
            String regex = Cast.toString(assignment.get("regex"));
            Object defaultValue = assignment.get("default");
            Object value;
            if (tcVars.containsKey(from)) {
                value = tcVars.get(from);
            } else if (tcVars.containsKey(tcName + "." + from)) {
                value = tcVars.get(tcName + "." + from);
            } else {
                if (defaultValue == null) {
                    throw new IvyException(from + " reference not found");
                }
                value = defaultValue;
            }
            if (regex.isEmpty()) {
                result.put(varname, value);
                continue;
            }
            Pattern p;
            try {
                p = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new IvyException("error parsing regexp: " + e.getDescription() + ": `" + regex + "`");
            }
            if (!(value instanceof String s)) {
                result.put(varname, "");
                continue;
            }
            Matcher matcher = p.matcher(s);
            if (!matcher.find()) {
                result.put(varname, "");
                continue;
            }
            String last = matcher.groupCount() == 0 ? matcher.group() : matcher.group(matcher.groupCount());
            result.put(varname, last == null ? "" : last);
        }
        return result;
    }

    // ------------------------------------------------------------ one step

    /** venom's {@code RunTestStep}: runs with retries, then applies the assertions. */
    private void runTestStep(RunContext ctx, ExecutorRunner e, ConnectorFacade.Session session, Map<String, Object> step,
            TestCase tc, TestStepResult tsResult, int stepNumber, int rangedIndex) {
        ctx = ctx.withExecutor(e.name());
        AssertionsApplied assertRes = new AssertionsApplied();
        for (tsResult.retries = 0; tsResult.retries <= e.retry() && !assertRes.ok; tsResult.retries++) {
            if (tsResult.retries >= 1) {
                log().debug(ctx.fields(), "Sleep " + e.delay() + ", it's " + tsResult.retries + " attempt");
                sleep(e.delay());
            }
            Object result;
            try {
                result = runExecutor(ctx, e, session, step, tc, tsResult);
            } catch (Exception ex) {
                if (tsResult.retries == e.retry()) {
                    tsResult.appendFailure(Failures.newFailure(ctx, tc, stepNumber, rangedIndex, -1, "", message(ex)));
                }
                continue;
            }
            log().debug(ctx.fields(), "result of executor: " + GoFormat.sprint(result));
            Map<String, Object> mapResult = Dump.dump(result);
            Map<String, String> mapResultString = Dump.dumpString(result);

            if (ivy.verbose >= 2) {
                writeDumpFile(ctx, tc, step, result, stepNumber, rangedIndex);
            }
            for (String info : e.info()) {
                String text;
                try {
                    text = Interpolator.interpolate(info, mapResultString);
                } catch (InterpolationException ex) {
                    log().error(ctx.fields(), "unable to parse \"" + info + "\": " + ex.getMessage());
                    continue;
                }
                if (text.isEmpty()) {
                    continue;
                }
                int line = tc.findSourceLine(stepNumber - 1, -1);
                if (line > 0) {
                    text += " (" + ctx.var("venom.testsuite.filepath") + ":" + line + ")";
                }
                log().info(ctx.fields(), text);
                tsResult.addInfo(text);
            }

            if (result == null) {
                Map<String, Object> allVars = new LinkedHashMap<>(ctx.vars());
                log().debug(ctx.fields(), "empty testcase, applying assertions on variables: " + GoFormat.sprint(allVars));
                assertRes = ivy.checker.apply(ctx, allVars, tc, stepNumber, rangedIndex, step, null);
            } else {
                assertRes = ivy.checker.apply(ctx, result, tc, stepNumber, rangedIndex, step, e.defaultAssertions());
            }
            tsResult.assertionsApplied = assertRes;
            tsResult.computedVars.putAll(mapResult);
            if (assertRes.ok) {
                break;
            }
            List<String> failures;
            try {
                failures = ivy.checker.testConditionalStatement(ctx, tc, e.retryIf(), tsResult.computedVars, "");
            } catch (ParseException ex) {
                tsResult.appendError("Error while evaluating retry condition: " + ex.getMessage());
                break;
            }
            if (!failures.isEmpty()) {
                tsResult.appendFailure(Failures.newFailure(ctx, tc, stepNumber, rangedIndex, -1, "",
                        "retry conditions not fulfilled, skipping " + (e.retry() - tsResult.retries) + " remaining retries"));
                break;
            }
        }
        if (tsResult.retries > 1 && !assertRes.errors.isEmpty()) {
            tsResult.appendFailure(new Failure("It's a failure after " + tsResult.retries + " attempts"));
        }
        assertRes.errors.forEach(tsResult::appendFailure);
        tsResult.systemerr += assertRes.systemerr + "\n";
        tsResult.systemout += assertRes.systemout + "\n";
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeDumpFile(RunContext ctx, TestCase tc, Map<String, Object> step, Object result, int stepNumber,
            int rangedIndex) {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("variables", new LinkedHashMap<>(ctx.vars()));
        dump.put("step", step);
        dump.put("result", result);
        String output = Json.write(dump, Json.GO_INDENT.withIndent(" "));
        String filename = Slug.make(ctx.var("venom.testsuite.shortName")) + "." + Slug.make(tc.name) + ".testcase."
                + tc.number + ".step." + stepNumber + "." + rangedIndex + ".dump.json";
        Path path = Path.of(ivy.outputDir).resolve(filename);
        try {
            Files.writeString(path, IvyLog.hideSensitive(output, ctx.secrets()));
            tc.computedVerbose.add("writing " + path);
        } catch (java.io.IOException ex) {
            log().error(ctx.fields(), "Error while creating file " + path + ": " + ex.getMessage());
        }
    }

    private static final ExecutorService TIMEOUTS = Executors.newVirtualThreadPerTaskExecutor();

    /** Runs the executor, within the step timeout when there is one. */
    private Object runExecutor(RunContext ctx, ExecutorRunner e, ConnectorFacade.Session session, Map<String, Object> step,
            TestCase tc, TestStepResult ts) throws Exception {
        java.util.concurrent.Callable<Object> call = () -> {
            if (e.isUser()) {
                return runUserExecutor(ctx, e.user(), tc, ts, step);
            }
            if (session == null) {
                return null;
            }
            return session.run(new DefaultStepContext(ctx, step, log()));
        };
        if (e.timeout() == 0) {
            return call.call();
        }
        Future<Object> f = TIMEOUTS.submit(call);
        try {
            return f.get(e.timeout(), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            f.cancel(true);
            throw new IvyException("Timeout after " + e.timeout() + " second(s)");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw cause instanceof Exception c ? c : new IvyException(message(cause), cause);
        }
    }

    // ------------------------------------------------------------ user executors

    /** venom's {@code RunUserExecutor}: runs the steps of a YAML executor as a nested test case. */
    Object runUserExecutor(RunContext ctx, UserExecutor ux, TestCase tcIn, TestStepResult tsIn, Map<String, Object> step)
            throws IvyException {
        Map<String, Object> vrs = clone(tcIn.testSuiteVars);
        Map<String, String> tsVars = Map.of();
        if (!ux.rawInputs().isEmpty()) {
            tsVars = Dump.dumpString(vrs);
            Map<String, Object> inputs;
            try {
                String interpolatedInput = Interpolator.interpolate(ux.rawInputs(), tsVars);
                Map<String, Object> parsed = Yaml.loadMap(interpolatedInput);
                inputs = Cast.toStringMap(parsed.get("input"));
            } catch (InterpolationException e) {
                throw new IvyException("unable to interpolate executor inputs \"" + ux.executor() + "\": " + e.getMessage(), e);
            } catch (YamlException e) {
                throw new IvyException("unable to unmarshal inputs for executor \"" + ux.executor() + "\": " + e.getMessage(), e);
            }
            for (Map.Entry<String, Object> in : inputs.entrySet()) {
                String k = in.getKey();
                if (k.startsWith("input.")) {
                    // do not reinject input.vars from a parent user executor
                    continue;
                } else if (!k.startsWith("venom")) {
                    Object vl = step.get(k);
                    if (vl != null && !"".equals(vl)) {
                        vrs.put("input." + k, vl);
                    } else {
                        vrs.put("input." + k, in.getValue());
                    }
                } else {
                    vrs.put(k, in.getValue());
                }
            }
            tsVars = Dump.dumpString(vrs);
        }

        String sanitized;
        Map<String, Object> newUx;
        try {
            String interpolatedFull = Interpolator.interpolate(ux.raw(), tsVars);
            sanitized = PartialYaml.quoteTemplateExpressions(interpolatedFull);
            newUx = Yaml.loadMap(sanitized);
        } catch (InterpolationException e) {
            throw new IvyException("unable to interpolate executor \"" + ux.executor() + "\": " + e.getMessage(), e);
        } catch (YamlException e) {
            throw new IvyException("unable to unmarshal executor \"" + ux.executor() + "\": " + e.getMessage(), e);
        }
        Object output = newUx.get("output");

        TestCase tc = new TestCase();
        tc.name = ux.executor();
        tc.vars = vrs;
        if (newUx.get("steps") instanceof List<?> l) {
            for (Object s : l) {
                tc.rawTestSteps.add(Yaml.toJson(s));
            }
        }
        tc.number = tcIn.number;
        tc.testSuiteVars = tcIn.testSuiteVars;
        tc.isExecutor = true;
        tc.originalName = tc.name;
        tc.name = Slug.make(tc.name);
        tc.vars.put("venom.testcase", tc.name);
        tc.vars.put("venom.executor.filename", ux.filename());
        tc.vars.put("venom.executor.name", ux.executor());
        tc.computedVars = new LinkedHashMap<>();

        log().debug(ctx.fields(), "running user executor " + tc.name);
        log().debug(ctx.fields(), "with vars: " + GoFormat.sprint(vrs));
        runTestSteps(ctx, tc, tsIn);

        // inner results are merged into the calling step so that reports show them
        for (TestStepResult inner : tc.testStepResults) {
            inner.errorList().forEach(tsIn::appendFailure);
            if (inner.computedInfo != null) {
                inner.computedInfo.forEach(tsIn::addInfo);
            }
            if (!inner.systemout.isBlank()) {
                tsIn.systemout += inner.systemout;
            }
            if (!inner.systemerr.isBlank()) {
                tsIn.systemerr += inner.systemerr;
            }
        }

        Map<String, String> computedVars = Dump.dumpStringPreserveCase(tc.computedVars);
        computedVars.replaceAll((k, v) -> PartialYaml.escapeQuotes(v));
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("result", output);
        Object outputResult;
        try {
            String outputS = Interpolator.interpolate(Json.write(wrapper), computedVars);
            outputResult = Yaml.load(outputS);
        } catch (InterpolationException e) {
            throw new IvyException(e.getMessage(), e);
        } catch (YamlException e) {
            throw new IvyException("unable to unmarshal: " + e.getMessage(), e);
        }

        Map<String, Object> result = Dump.dump(outputResult);
        if (tsIn.hasErrors()) {
            log().error(ctx.fields(), "user executor \"" + ux.executor() + "\" failed - raw interpolated:\n" + sanitized + "\n");
            throw new UserExecutorFailure(outputResult, "executor \"" + ux.executor() + "\" failed");
        }
        // each string output that is JSON is also exposed parsed, as <key>json
        for (Map.Entry<String, Object> e : new ArrayList<>(result.entrySet())) {
            if (!(e.getValue() instanceof String z)) {
                continue;
            }
            Object outJson = Json.tryParse(z);
            if (outJson == null && !"null".equals(z.strip())) {
                continue;
            }
            String k = e.getKey();
            result.put(k + "json", outJson);
            if (outJson instanceof List<?>) {
                String prefix = k + "json";
                String[] split = prefix.split("\\.");
                prefix += "." + split[split.length - 1];
                for (Map.Entry<String, Object> d : Dump.dump(outJson).entrySet()) {
                    result.put(prefix + d.getKey(), d.getValue());
                }
            } else {
                result.putAll(Dump.dumpWithPrefix(outJson, k + "json"));
            }
        }
        return result;
    }

    /** A failed user executor; its output is still reported. */
    static final class UserExecutorFailure extends IvyException {
        final transient Object output;

        UserExecutorFailure(Object output, String message) {
            super(message);
            this.output = output;
        }
    }

    /** A user executor named {@code Should...} used as an assertion: {@code a} and {@code b} are its operands. */
    AssertFunc userAssertion(RunContext ctx, String name) {
        return (actual, expected) -> {
            TestCase tc = new TestCase();
            tc.name = name;
            tc.testSuiteVars = new LinkedHashMap<>();
            tc.testSuiteVars.put("a", actual);
            if (!expected.isEmpty()) {
                tc.testSuiteVars.put("b", expected.getFirst());
            }
            tc.testSuiteVars.put("argv", new ArrayList<>(expected));
            TestStepResult ts = new TestStepResult();
            UserExecutor ux = ivy.userExecutors.get(name);
            IvyException error = null;
            try {
                runUserExecutor(ctx, ux, tc, ts, Map.of());
            } catch (IvyException e) {
                error = e;
            }
            if (ts.hasErrors()) {
                // as venom: one empty line per failure, then the failures
                List<String> msg = new ArrayList<>();
                for (int i = 0; i < ts.errors.size(); i++) {
                    msg.add("");
                }
                for (Failure f : ts.errors) {
                    msg.add("  " + f.stepNumber + ": Sub-assertion " + GoFormat.quote(GoStrings.removeNotPrintable(f.assertion))
                            + " failed. " + GoStrings.removeNotPrintable(f.error));
                }
                throw new AssertException(String.join("\n", msg));
            }
            if (error != null) {
                throw new AssertException("user assertion failed during execution: " + error.getMessage());
            }
        };
    }
}
