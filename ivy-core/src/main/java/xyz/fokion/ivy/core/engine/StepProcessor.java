package xyz.fokion.ivy.core.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.expr.ExprException;
import xyz.fokion.ivy.core.expr.Expression;
import xyz.fokion.ivy.core.expr.Scope;
import xyz.fokion.ivy.core.expr.Template;
import xyz.fokion.ivy.core.expr.Values;
import xyz.fokion.ivy.core.model.AssertionsApplied;
import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestStepResult;
import xyz.fokion.ivy.core.util.Slug;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Runs the steps of a test case, including the steps of user executors.
 * <p>
 * Each step sees a {@link Scope}: the suite and test case variables, the values set by the
 * previous steps, {@code ivy}, and for a ranged step {@code index}, {@code key} and
 * {@code value}. Once the step ran, {@code result} is added for its assertions,
 * {@code retryIf}, {@code info} and {@code set}. Nothing is copied: scopes are layers over the
 * maps that hold the values.
 */
final class StepProcessor {

    /** Step keys that are expressions or apply after the step: not rendered before it runs. */
    static final Set<String> CONTROL_KEYS = Set.of("assertions", "if", "retryIf", "set", "info", "range", "with");

    private final Ivy ivy;

    StepProcessor(Ivy ivy) {
        this.ivy = ivy;
    }

    /**
     * A test case being run.
     *
     * @param scope the test case scope, including the values set by its steps
     * @param suiteScope the suite scope, for the user executors the steps call
     * @param ivyCase the {@code ivy} built-ins of the test case
     * @param parent the step calling a user executor, or {@code null}
     */
    record CaseRun(RunContext ctx, TestCase tc, Scope scope, Scope suiteScope, Map<String, Object> ivyCase,
            TestStepResult parent) {
    }

    private record Range(boolean enabled, List<Map.Entry<String, Object>> items) {
    }

    void runTestSteps(CaseRun run) {
        TestCase tc = run.tc();
        Sessions sessions = new Sessions();
        Map<String, Object> inputVars = inputVars(run);
        try {
            loopSteps:
            for (int stepIndex = 0; stepIndex < tc.steps.size(); stepIndex++) {
                Map<String, Object> step = tc.steps.get(stepIndex);
                int stepNumber = stepIndex + 1;
                Map<String, Object> ivyStep = new LinkedHashMap<>(run.ivyCase());
                ivyStep.put("step", Map.of("number", (long) stepNumber));
                Scope scope = run.scope().with("ivy", ivyStep);

                Range ranged;
                try {
                    if (step.get("with") instanceof Map<?, ?> with) {
                        Map<String, Object> values = stringMap(Template.interpolate(with, scope));
                        values.forEach((k, v) -> {
                            if (ivy.secrets.isName(k)) {
                                ivy.secrets.add(v);
                            }
                        });
                        scope = scope.child(values);
                    }
                    ranged = range(run.ctx(), step.get("range"), scope);
                } catch (IvyException | ExprException e) {
                    TestStepResult r = new TestStepResult();
                    r.number = stepNumber;
                    r.appendFailure(Failures.newFailure(run.ctx(), tc, stepNumber, -1, -1, "", message(e)));
                    r.status = Status.FAIL;
                    tc.testStepResults.add(r);
                    run.ctx().log().error(run.ctx().fields(), "unable to prepare step #" + stepNumber + ": " + message(e));
                    return;
                }

                for (int rangedIndex = 0; rangedIndex < ranged.items().size(); rangedIndex++) {
                    TestStepResult tsResult = new TestStepResult();
                    tsResult.inputVars = inputVars;
                    tc.testStepResults.add(tsResult);
                    Scope iterationScope = scope;
                    Map.Entry<String, Object> item = ranged.items().get(rangedIndex);
                    if (ranged.enabled()) {
                        Map<String, Object> rangeVars = new LinkedHashMap<>();
                        rangeVars.put("index", (long) rangedIndex);
                        rangeVars.put("key", item.getKey());
                        rangeVars.put("value", item.getValue());
                        iterationScope = scope.child(rangeVars);
                    }
                    Iteration it = new Iteration(run, step, iterationScope, stepNumber, ranged.enabled() ? rangedIndex : -1,
                            item.getKey(), tsResult, sessions);
                    Flow flow;
                    try {
                        flow = runIteration(it);
                    } catch (RuntimeException ex) {
                        // a bug or an unexpected value must fail the step, not the whole run
                        tsResult.appendFailure(Failures.newFailure(run.ctx(), tc, stepNumber, it.rangedIndex(), -1, "",
                                message(ex)));
                        tsResult.status = Status.FAIL;
                        run.ctx().log().error(run.ctx().fields(), "step #" + stepNumber + " failed: " + ex);
                        printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
                        flow = Flow.NEXT;
                    }
                    switch (flow) {
                        case NEXT -> {
                        }
                        case NEXT_STEP -> {
                            continue loopSteps;
                        }
                        case STOP -> {
                            break loopSteps;
                        }
                        case RETURN -> {
                            return;
                        }
                    }
                }
            }
        } finally {
            sessions.closeAll(run.ctx());
        }
    }

    /**
     * The variables shown with the steps in reports: those of the suite and the test case when it
     * starts (the map holds references to the values, not copies).
     */
    private static Map<String, Object> inputVars(CaseRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        run.scope().asMap().forEach((k, v) -> {
            if (!k.equals("env") && !k.equals("cases") && !k.equals("ivy")) {
                view.put(k, v);
            }
        });
        return view;
    }

    /** What the step loop does after one iteration. */
    private enum Flow {
        /** next iteration or step */
        NEXT,
        /** skip the remaining iterations of this step */
        NEXT_STEP,
        /** skip the remaining steps */
        STOP,
        /** end the test case now */
        RETURN
    }

    /**
     * One iteration of a step.
     *
     * @param rangedIndex the iteration of a ranged step, or -1
     */
    private record Iteration(CaseRun run, Map<String, Object> step, Scope scope, int stepNumber, int rangedIndex,
            String rangeKey, TestStepResult tsResult, Sessions sessions) {
    }

    /** The connector sessions of one test case, opened on first use and closed at its end. */
    private final class Sessions {
        private final Map<String, ConnectorFacade.Session> open = new LinkedHashMap<>();
        private final Map<String, TestStepResult> owners = new LinkedHashMap<>();

        ConnectorFacade.Session get(ExecutorRunner e, RunContext ctx, Scope scope, Map<String, Object> step,
                TestStepResult owner) throws Exception {
            ConnectorFacade.Session s = open.get(e.name());
            if (s == null) {
                s = e.facade().openSession(new DefaultStepContext(ctx, scope, step, ctx.log()));
                open.put(e.name(), s);
                owners.put(e.name(), owner);
            }
            return s;
        }

        /**
         * Drops a session whose call timed out: it may still be running, so it is closed in the
         * background and the next step opens a new one.
         */
        void discard(String name, RunContext ctx) {
            ConnectorFacade.Session s = open.remove(name);
            owners.remove(name);
            if (s != null) {
                Thread.startVirtualThread(() -> {
                    try {
                        s.close();
                    } catch (Exception ex) {
                        ctx.log().warn(ctx.fields(), "unable to close timed out session: " + message(ex));
                    }
                });
            }
        }

        void closeAll(RunContext ctx) {
            for (Map.Entry<String, ConnectorFacade.Session> s : open.entrySet()) {
                try {
                    s.getValue().close();
                } catch (Exception ex) {
                    owners.get(s.getKey()).appendError(message(ex));
                    ctx.log().error(ctx.fields(), "unable to teardown executor: " + message(ex));
                }
            }
            open.clear();
        }
    }

    /** One iteration of a step: condition, templates, executor, run, assertions and {@code set}. */
    private Flow runIteration(Iteration it) {
        CaseRun run = it.run();
        RunContext ctx = run.ctx();
        TestCase tc = run.tc();
        TestStepResult tsResult = it.tsResult();
        Map<String, Object> step = it.step();
        int stepNumber = it.stepNumber();
        tsResult.number = stepNumber;
        tsResult.rangedIndex = Math.max(it.rangedIndex(), 0);
        tsResult.rangedEnable = it.rangedIndex() >= 0;
        tsResult.raw = Yaml.dump(step);

        if (step.get("if") instanceof String condition && !condition.isBlank()) {
            boolean go;
            try {
                go = Values.truthy(Expression.compile(condition).evaluate(it.scope()));
            } catch (ExprException ex) {
                tsResult.appendFailure(Failures.newFailure(ctx, tc, stepNumber, it.rangedIndex(), -1, "",
                        "cannot evaluate if: " + ex.getMessage()));
                tsResult.status = Status.FAIL;
                printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
                return Flow.NEXT;
            }
            if (!go) {
                tsResult.name = step.get("name") instanceof String n ? n
                        : step.get("type") instanceof String t ? t : "step #" + stepNumber;
                tsResult.status = Status.SKIP;
                tsResult.addSkipped("step #" + stepNumber + " skipped: if " + condition + " is false");
                ctx.log().info(ctx.fields(), "step #" + stepNumber + " skipped: if " + condition + " is false");
                printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
                return Flow.NEXT;
            }
        }

        Map<String, Object> runnable = new LinkedHashMap<>(step);
        runnable.keySet().removeAll(CONTROL_KEYS);
        Map<String, Object> interpolated;
        try {
            interpolated = stringMap(Template.interpolate(runnable, it.scope()));
        } catch (ExprException ex) {
            tsResult.appendFailure(Failures.newFailure(ctx, tc, stepNumber, it.rangedIndex(), -1, "", ex.getMessage()));
            tsResult.status = Status.FAIL;
            ctx.log().error(ctx.fields(), "unable to render step #" + stepNumber + ": " + ex.getMessage());
            printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
            return Flow.NEXT;
        }
        if (ivy.secrets.isSecret(interpolated.get("basic_auth_password"))) {
            ivy.secrets.addBasicAuth(Values.display(interpolated.get("basic_auth_user")),
                    Values.display(interpolated.get("basic_auth_password")));
        }
        tsResult.interpolated = Yaml.dump(interpolated);
        ctx.log().info(ctx.fields(), "Step #" + stepNumber + (it.rangedIndex() >= 0 ? "-" + it.rangedIndex() : "")
                + " content is: " + Json.write(interpolated, Json.COMPACT));

        ExecutorRunner e;
        try {
            e = ivy.executorRunner(interpolated, step);
        } catch (IvyException ex) {
            tsResult.appendFailure(Failures.newFailure(ctx, tc, stepNumber, it.rangedIndex(), -1, "", ex.getMessage()));
            tsResult.status = Status.FAIL;
            ctx.log().error(ctx.fields(), "unable to get executor: " + ex.getMessage());
            printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
            return Flow.STOP;
        }
        tsResult.name = stepName(e, interpolated, it);
        if (ivy.verbose >= 1 && run.parent() == null) {
            ctx.console().print(" \t\t• " + tsResult.name);
        }

        if (e.facade() != null) {
            try {
                it.sessions().get(e, ctx, it.scope(), interpolated, tsResult);
            } catch (Exception ex) {
                tsResult.appendFailure(Failures.newFailure(ctx, tc, stepNumber, it.rangedIndex(), -1, "",
                        "unable to set up " + e.name() + ": " + message(ex)));
                tsResult.status = Status.FAIL;
                ctx.log().error(ctx.fields(), "unable to setup executor: " + message(ex));
                printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
                return Flow.NEXT_STEP;
            }
        }

        tsResult.start = OffsetDateTime.now();
        tsResult.status = Status.RUN;
        Scope resultScope = runTestStep(it, e, interpolated);
        tsResult.end = OffsetDateTime.now();
        tsResult.duration = Ivy.seconds(tsResult.start, tsResult.end);
        tsResult.status = tsResult.hasErrors() || !tsResult.assertionsApplied.ok ? Status.FAIL : Status.PASS;

        if (tsResult.status == Status.FAIL) {
            // values a failed step would have captured as secrets are still hidden
            hideSecretCaptures(step, resultScope);
            ctx.log().error(ctx.fields(), "Step \"" + tsResult.name + "\" result is \"" + tsResult.status + "\"");
            boolean required = ivy.stopOnFailure;
            for (Failure f : tsResult.errorList()) {
                ctx.log().error(ctx.fields(), f.toString());
                required = required || f.assertionRequired;
            }
            if (required) {
                tsResult.appendFailure(Failures.newFailure(ctx, tc, stepNumber, it.rangedIndex(), -1, "",
                        "a required assertion failed, the remaining steps are skipped"));
                printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, true);
                return Flow.RETURN;
            }
            printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
            return Flow.NEXT;
        }
        ctx.log().info(ctx.fields(), "Step \"" + tsResult.name + "\" result is \"" + tsResult.status + "\"");

        if (step.get("set") instanceof Map<?, ?> set) {
            for (Map.Entry<?, ?> s : set.entrySet()) {
                String name = String.valueOf(s.getKey());
                Object value;
                try {
                    value = evaluateValue(s.getValue(), resultScope);
                } catch (ExprException ex) {
                    tsResult.appendFailure(Failures.newFailure(ctx, tc, stepNumber, it.rangedIndex(), -1, "",
                            "cannot set " + name + ": " + ex.getMessage()));
                    tsResult.status = Status.FAIL;
                    printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
                    return Flow.STOP;
                }
                if (ivy.secrets.isName(name)) {
                    ivy.secrets.add(value);
                }
                tc.computedVars.put(name, value);
                tsResult.computedVars.put(name, value);
            }
        }
        // written once the values the step set are known secrets
        if (ivy.verbose >= 2) {
            writeDumpFile(ctx, tc, interpolated, tsResult.computedVars.get("result"), it);
        }
        printTestStepResult(run.ctx(), tc, tsResult, run.parent(), stepNumber, false);
        return Flow.NEXT;
    }

    /** Registers the values that {@code set} captures under secret names, without assigning them. */
    private void hideSecretCaptures(Map<String, Object> step, Scope resultScope) {
        if (!(step.get("set") instanceof Map<?, ?> set)) {
            return;
        }
        for (Map.Entry<?, ?> s : set.entrySet()) {
            if (ivy.secrets.isName(String.valueOf(s.getKey()))) {
                try {
                    ivy.secrets.add(evaluateValue(s.getValue(), resultScope));
                } catch (ExprException ignored) {
                    // nothing was captured
                }
            }
        }
    }

    /**
     * A {@code set} value: a string is an expression ({@code result.body.id}) or a template
     * ({@code "${a}-${b}"}); other values are taken as they are.
     */
    static Object evaluateValue(Object declared, Scope scope) {
        if (declared instanceof String s) {
            return Template.has(s) ? Template.compile(s).render(scope) : Expression.compile(s).evaluate(scope);
        }
        return Template.interpolate(declared, scope);
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /** The step name: the {@code name} attribute or the executor name; ranged steps add their key. */
    private static String stepName(ExecutorRunner e, Map<String, Object> step, Iteration it) {
        String name = step.get("name") instanceof String s && !s.isEmpty() ? s : e.name();
        if (it.rangedIndex() >= 0) {
            name = name + " (range=" + it.rangeKey() + ")";
        }
        return name;
    }

    private void printTestStepResult(RunContext ctx, TestCase tc, TestStepResult ts, TestStepResult parent,
            int stepNumber, boolean mustAssertionFailed) {
        if (parent != null || ivy.verbose < 1) {
            return;
        }
        Console console = ctx.console();
        if (ts.hasErrors()) {
            console.println(" " + ivy.red(Status.FAIL));
            if (ts.computedInfo != null) {
                ts.computedInfo.forEach(i -> console.println(" \t\t  " + ivy.cyan("[info]") + " " + ivy.cyan(i)));
            }
            ts.errorList().forEach(f -> console.println(" \t\t  " + ivy.yellow(f.value.replace("\n", "\n \t\t  "))));
            if (mustAssertionFailed) {
                int skipped = tc.steps.size() - stepNumber;
                console.println(" \t\t  " + ivy.gray(skipped == 1 ? skipped + " other step was skipped"
                        : skipped + " other steps were skipped"));
            }
        } else if (ts.status == Status.SKIP) {
            console.println(" " + ivy.gray(Status.SKIP));
        } else {
            if (ts.retries == 0) {
                console.println(" " + ivy.green(Status.PASS));
            } else {
                console.println(" " + ivy.green(Status.PASS) + " (after " + ts.retries + " attempts)");
            }
            if (ts.computedInfo != null) {
                ts.computedInfo.forEach(i -> console.println(" \t\t  " + ivy.cyan("[info]") + " " + ivy.cyan(i)));
            }
        }
    }

    // ------------------------------------------------------------ range

    /** {@code range}: iterations over a list, a map or a number of items. */
    private Range range(RunContext ctx, Object declared, Scope scope) throws IvyException {
        if (declared == null) {
            List<Map.Entry<String, Object>> single = new ArrayList<>();
            single.add(new java.util.AbstractMap.SimpleEntry<>("", null));
            return new Range(false, single);
        }
        Object content;
        if (declared instanceof String s) {
            if (s.isBlank()) {
                throw new IvyException("range is empty");
            }
            content = Values.unwrap(Template.has(s) ? Template.compile(s).render(scope)
                    : Expression.compile(s).evaluate(scope));
            if (content instanceof String text && Json.tryParse(text) instanceof Object parsed) {
                content = parsed;
            }
        } else {
            content = Values.unwrap(Template.interpolate(declared, scope));
        }
        List<Map.Entry<String, Object>> items = new ArrayList<>();
        switch (content) {
            case List<?> l -> {
                for (int i = 0; i < l.size(); i++) {
                    items.add(new java.util.AbstractMap.SimpleEntry<>(Integer.toString(i), l.get(i)));
                }
            }
            case Number n -> {
                long upper = n.longValue();
                for (long i = 0; i < upper; i++) {
                    items.add(new java.util.AbstractMap.SimpleEntry<>(Long.toString(i), i));
                }
            }
            case Map<?, ?> m -> m.forEach((k, v) -> items.add(new java.util.AbstractMap.SimpleEntry<>(String.valueOf(k), v)));
            case null, default -> throw new IvyException("range must be a list, a map or a number, got "
                    + Values.typeOf(content) + " " + Values.describe(content, 50));
        }
        ctx.log().debug(ctx.fields(), "range of " + items.size() + " items");
        return new Range(true, items);
    }

    // ------------------------------------------------------------ one step

    /** Runs a step with retries and applies the assertions; returns the scope with {@code result}. */
    private Scope runTestStep(Iteration it, ExecutorRunner e, Map<String, Object> step) {
        CaseRun run = it.run();
        RunContext ctx = run.ctx().withExecutor(e.name());
        TestCase tc = run.tc();
        TestStepResult tsResult = it.tsResult();
        AssertionsApplied assertRes = new AssertionsApplied();
        Scope resultScope = it.scope().with("result", null);
        List<?> declared = it.step().get("assertions") instanceof List<?> l ? l : null;
        int attempt = 0;
        // a user executor reports into a result of its own per attempt; the last one is kept
        TestStepResult userAttempt = null;
        try {
            for (; attempt <= e.retry(); attempt++) {
                if (attempt >= 1) {
                    ctx.log().debug(ctx.fields(), "Sleep " + e.delay() + ", it's " + attempt + " attempt");
                    sleep(e.delay());
                }
                userAttempt = e.isUser() ? new TestStepResult() : null;
                Object result;
                try {
                    result = runExecutor(ctx, e, it, step, userAttempt);
                } catch (Exception ex) {
                    if (attempt == e.retry()) {
                        tsResult.appendFailure(Failures.newFailure(ctx, tc, it.stepNumber(), it.rangedIndex(), -1, "",
                                message(ex)));
                    }
                    continue;
                }
                resultScope = it.scope().with("result", result);
                tsResult.computedVars = new LinkedHashMap<>();
                tsResult.computedVars.put("result", result);
                for (String info : e.info()) {
                    try {
                        String text = Template.compile(info).renderString(resultScope);
                        if (!text.isEmpty()) {
                            int line = tc.findSourceLine(it.stepNumber() - 1, -1);
                            if (line > 0) {
                                text += " (" + ctx.filepath() + ":" + line + ")";
                            }
                            ctx.log().info(ctx.fields(), text);
                            tsResult.addInfo(text);
                        }
                    } catch (ExprException ex) {
                        ctx.log().error(ctx.fields(), "unable to render info \"" + info + "\": " + ex.getMessage());
                    }
                }
                assertRes = ivy.checker.apply(ctx, resultScope, result, tc, it.stepNumber(), it.rangedIndex(), declared,
                        e.defaultAssertions());
                tsResult.assertionsApplied = assertRes;
                if (assertRes.ok) {
                    break;
                }
                if (!e.retryIf().isEmpty() && attempt < e.retry()) {
                    boolean again;
                    try {
                        again = Values.truthy(Expression.compile(e.retryIf()).evaluate(resultScope));
                    } catch (ExprException ex) {
                        tsResult.appendError("cannot evaluate retryIf: " + ex.getMessage());
                        break;
                    }
                    if (!again) {
                        tsResult.appendFailure(Failures.newFailure(ctx, tc, it.stepNumber(), it.rangedIndex(), -1, "",
                                "retryIf " + e.retryIf() + " is false, " + (e.retry() - attempt)
                                        + " remaining retries skipped"));
                        break;
                    }
                }
            }
        } finally {
            tsResult.retries = Math.min(attempt, e.retry());
            if (userAttempt != null) {
                userAttempt.errorList().forEach(tsResult::appendFailure);
                if (userAttempt.computedInfo != null) {
                    userAttempt.computedInfo.forEach(tsResult::addInfo);
                }
                tsResult.stdout += userAttempt.stdout;
                tsResult.stderr += userAttempt.stderr;
            }
        }
        if (tsResult.retries > 0 && !assertRes.errors.isEmpty()) {
            tsResult.appendFailure(new Failure("It's a failure after " + (tsResult.retries + 1) + " attempts"));
        }
        assertRes.errors.forEach(tsResult::appendFailure);
        tsResult.stderr += assertRes.stderr;
        tsResult.stdout += assertRes.stdout;
        return resultScope;
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(Math.max(0, seconds) * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeDumpFile(RunContext ctx, TestCase tc, Map<String, Object> step, Object result, Iteration it) {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("variables", it.tsResult().inputVars);
        dump.put("step", step);
        dump.put("result", result);
        String filename = Slug.make(Path.of(ctx.filepath()).getFileName() == null ? "suite"
                : Path.of(ctx.filepath()).getFileName().toString()) + "." + Slug.make(tc.name) + ".testcase."
                + tc.number + ".step." + it.stepNumber() + "." + Math.max(it.rangedIndex(), 0) + ".dump.json";
        Path path = Path.of(ivy.outputDir).resolve(filename);
        try (var out = Files.newBufferedWriter(path)) {
            Json.write(dump, Json.GO_INDENT.withIndent(" "), out, ivy.secrets.filter());
            tc.computedVerbose.add("writing " + path);
        } catch (java.io.IOException ex) {
            ctx.log().error(ctx.fields(), "Error while creating file " + path + ": " + ex.getMessage());
        }
    }

    private static final ExecutorService TIMEOUTS = Executors.newVirtualThreadPerTaskExecutor();

    /** Runs the executor, within the step timeout when there is one. */
    private Object runExecutor(RunContext ctx, ExecutorRunner e, Iteration it, Map<String, Object> step,
            TestStepResult userAttempt) throws Exception {
        if (e.isUser()) {
            return withTimeout(e, () -> runUserExecutor(ctx, e.user(), it.run(), userAttempt, step), () -> {
            });
        }
        if (e.facade() == null) {
            return null;
        }
        ConnectorFacade.Session session = it.sessions().get(e, ctx, it.scope(), step, it.tsResult());
        return withTimeout(e, () -> session.run(new DefaultStepContext(ctx, it.scope(), step, ctx.log())),
                () -> it.sessions().discard(e.name(), ctx));
    }

    /** Runs the call within the step timeout, if any; {@code onTimeout} runs when it expires. */
    private static Object withTimeout(ExecutorRunner e, java.util.concurrent.Callable<Object> call, Runnable onTimeout)
            throws Exception {
        if (e.timeout() == 0) {
            return call.call();
        }
        Future<Object> f = TIMEOUTS.submit(call);
        try {
            return f.get(e.timeout(), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            f.cancel(true);
            onTimeout.run();
            throw new IvyException("Timeout after " + e.timeout() + " second(s)");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw cause instanceof Exception c ? c : new IvyException(message(cause), cause);
        }
    }

    // ------------------------------------------------------------ user executors

    /**
     * Runs the steps of a YAML executor as a nested test case. Its steps see the suite variables
     * and {@code input}; its {@code output} is rendered with the values its steps set.
     */
    Object runUserExecutor(RunContext ctx, UserExecutor ux, CaseRun caller, TestStepResult tsIn,
            Map<String, Object> step) throws IvyException {
        Map<String, Object> inputs = new LinkedHashMap<>();
        Scope inputScope = caller.suiteScope().with("input", inputs);
        try {
            for (Map.Entry<String, Object> in : ux.input().entrySet()) {
                Object given = step.get(in.getKey());
                Object value = given != null && !"".equals(given) ? given : Template.interpolate(in.getValue(), inputScope);
                if (ivy.secrets.isName(in.getKey())) {
                    ivy.secrets.add(value);
                }
                inputs.put(in.getKey(), value);
            }
        } catch (ExprException e) {
            throw new IvyException("unable to compute the inputs of executor \"" + ux.executor() + "\": " + e.getMessage(), e);
        }

        TestCase tc = new TestCase();
        tc.name = Slug.make(ux.executor());
        tc.originalName = ux.executor();
        tc.steps = ux.steps();
        tc.number = caller.tc().number;
        tc.isExecutor = true;
        Map<String, Object> ivyExecutor = new LinkedHashMap<>(caller.ivyCase());
        ivyExecutor.put("executor", Map.of("name", ux.executor(), "file", ux.filename()));
        Scope scope = inputScope.child(tc.computedVars);

        ctx.log().debug(ctx.fields(), "running user executor " + tc.name);
        RunContext inner = ctx.withFile(ux.filename());
        runTestSteps(new CaseRun(inner, tc, scope, caller.suiteScope(), ivyExecutor, tsIn));

        // inner results are merged into the calling step so that reports show them
        for (TestStepResult r : tc.testStepResults) {
            r.errorList().forEach(tsIn::appendFailure);
            if (r.computedInfo != null) {
                r.computedInfo.forEach(tsIn::addInfo);
            }
            if (!r.stdout.isBlank()) {
                tsIn.stdout += r.stdout;
            }
            if (!r.stderr.isBlank()) {
                tsIn.stderr += r.stderr;
            }
        }
        if (tsIn.hasErrors()) {
            throw new IvyException("executor \"" + ux.executor() + "\" failed");
        }
        try {
            return Template.interpolate(ux.output(), scope);
        } catch (ExprException e) {
            throw new IvyException("unable to render the output of executor \"" + ux.executor() + "\": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> stringMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }
}
