package xyz.fokion.ivy.core.engine;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import xyz.fokion.ivy.core.assertion.Assertions;
import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.connector.LocalConnectorInfoManager;
import xyz.fokion.ivy.core.dump.Dump;
import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestSuite;
import xyz.fokion.ivy.core.model.Tests;
import xyz.fokion.ivy.core.template.Interpolator;
import xyz.fokion.ivy.core.template.Interpolator.InterpolationException;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.util.Slug;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.core.yaml.Yaml.TestCaseLines;
import xyz.fokion.ivy.core.yaml.Yaml.YamlException;

/**
 * The test runner, a port of venom's {@code Venom} type: reads suites, runs them and keeps the
 * results in {@link #tests()}.
 */
public final class Ivy {

    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (var in = Ivy.class.getResourceAsStream("/xyz/fokion/ivy/core/version.properties")) {
            java.util.Properties p = new java.util.Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "snapshot");
        } catch (IOException e) {
            return "snapshot";
        }
    }

    /** Raised for errors that stop the whole run (unreadable suites, unknown executors...). */
    public static class IvyException extends Exception {
        public IvyException(String message) {
            super(message);
        }

        public IvyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final PrintStream out;
    private boolean colors = true;
    IvyLog log = IvyLog.discard();
    private Writer logWriter;

    final Map<String, Object> variables = new LinkedHashMap<>();
    final Map<String, Object> secrets = new LinkedHashMap<>();
    final Tests tests = new Tests();
    final Assertions assertions = new Assertions();
    final Map<String, UserExecutor> userExecutors = new LinkedHashMap<>();
    private final ConnectorInfoManager builtin;
    private final List<ConnectorInfoManager> plugins = new ArrayList<>();
    final AssertionChecker checker = new AssertionChecker(assertions);
    final StepProcessor steps = new StepProcessor(this);

    String libDir = "";
    String outputFormat = "xml";
    String outputDir = "";
    boolean stopOnFailure;
    boolean htmlReport;
    int verbose;

    public Ivy(PrintStream out) {
        this.out = out;
        this.builtin = LocalConnectorInfoManager.fromClassLoader("ivy", VERSION, Ivy.class.getClassLoader());
    }

    // ------------------------------------------------------------ configuration

    public Ivy libDir(String dir) {
        this.libDir = dir == null ? "" : dir;
        return this;
    }

    public Ivy outputFormat(String format) {
        this.outputFormat = format;
        return this;
    }

    public Ivy outputDir(String dir) {
        this.outputDir = dir == null ? "" : dir;
        return this;
    }

    public Ivy stopOnFailure(boolean b) {
        this.stopOnFailure = b;
        return this;
    }

    public Ivy htmlReport(boolean b) {
        this.htmlReport = b;
        return this;
    }

    public Ivy verbose(int level) {
        this.verbose = level;
        return this;
    }

    public Ivy colors(boolean b) {
        this.colors = b;
        return this;
    }

    public String outputFormat() {
        return outputFormat;
    }

    public String outputDir() {
        return outputDir;
    }

    public boolean htmlReport() {
        return htmlReport;
    }

    public int verbose() {
        return verbose;
    }

    public Tests tests() {
        return tests;
    }

    public void addVariables(Map<String, Object> vars) {
        variables.putAll(vars);
    }

    public void addSecrets(Map<String, Object> s) {
        secrets.putAll(s);
    }

    /** Adds a source of plugin connectors: a bundles directory or a connector server. */
    public void addConnectors(ConnectorInfoManager manager) {
        plugins.add(manager);
    }

    // ------------------------------------------------------------ console

    void print(String s) {
        out.print(s);
    }

    void println(String s) {
        out.println(s);
    }

    void printlnIndentedTrace(String s, String indent) {
        println("\t  " + indent + gray("[trac]") + " " + gray(s));
    }

    private String color(String code, Object s) {
        return colors ? "\u001b[" + code + "m" + s + "\u001b[0m" : String.valueOf(s);
    }

    String red(Object s) {
        return color("31", s);
    }

    String green(Object s) {
        return color("32", s);
    }

    String yellow(Object s) {
        return color("33", s);
    }

    String cyan(Object s) {
        return color("36", s);
    }

    String gray(Object s) {
        return color("90", s);
    }

    // ------------------------------------------------------------ logger

    /** Opens the log file in the output directory, as venom's {@code InitLogger}. */
    public void initLogger() throws IvyException {
        tests.testSuites = new ArrayList<>();
        IvyLog.Level level = switch (verbose) {
            case 1 -> IvyLog.Level.INFO;
            case 2, 3 -> IvyLog.Level.DEBUG;
            default -> IvyLog.Level.WARN;
        };
        try {
            if (!outputDir.isEmpty()) {
                Files.createDirectories(Path.of(outputDir));
            }
            Path logFile = Path.of(outputDir).resolve(Outputs.uniqueFilename(Path.of(outputDir), "ivy.log"));
            logWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
            log = new IvyLog(logWriter, level);
            printlnIndentedTrace("writing " + logFile, "");
        } catch (IOException e) {
            throw new IvyException("unable to write log file: " + e.getMessage(), e);
        }
    }

    /** Flushes the log and releases the connectors. */
    public void close() {
        try {
            if (logWriter != null) {
                logWriter.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do
        }
        for (ConnectorInfoManager m : plugins) {
            try {
                m.close();
            } catch (Exception ignored) {
                // closing is best effort
            }
        }
    }

    // ------------------------------------------------------------ parse

    /** Reads the suites and checks their steps can be resolved. */
    public void parse(List<String> paths) throws IvyException {
        RunContext ctx = RunContext.root();
        List<Path> files = SuiteFiles.find(paths);
        readFiles(ctx, files);
        registerUserExecutors(ctx);
        for (TestSuite ts : tests.testSuites) {
            ts.vars.put("venom.testsuite", ts.name);
            log.info(ctx.fields(), "Parsing testsuite " + ts.filepath);
            for (int i = 0; i < ts.testCases.size(); i++) {
                TestCase tc = ts.testCases.get(i);
                tc.originalName = tc.name;
                tc.number = i + 1;
                tc.name = Slug.make(tc.name);
                tc.vars = new LinkedHashMap<>(ts.vars);
                tc.vars.put("venom.testcase", tc.name);
                if (!tc.hasSkipped()) {
                    parseTestCase(tc);
                }
            }
        }
    }

    private void readFiles(RunContext ctx, List<Path> files) throws IvyException {
        for (Path file : files) {
            log.debug(ctx.fields(), "Reading " + file);
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException e) {
                throw new IvyException("unable to read file \"" + file + "\": " + e.getMessage(), e);
            }
            Map<String, Object> varCloned = new LinkedHashMap<>(variables);

            Map<String, Object> fromPartial;
            try {
                Object partial = Yaml.load(PartialYaml.read(content, "vars"));
                fromPartial = partial instanceof Map<?, ?> m && m.get("vars") instanceof Map<?, ?> v
                        ? Cast.toStringMap(v) : Map.of();
            } catch (YamlException e) {
                throw new IvyException("unable to get vars from file \"" + file + "\": error while unmarshal - see ivy.log", e);
            }
            if (!fromPartial.isEmpty()) {
                Map<String, String> varsFromPartial = Dump.dumpStringPreserveCase(fromPartial);
                for (Map.Entry<String, String> e : varsFromPartial.entrySet()) {
                    String k = e.getKey();
                    if (k.isEmpty()) {
                        continue;
                    }
                    if (!varCloned.containsKey(k)
                            || ("{}".equals(varCloned.get(k)) && "0".equals(varCloned.get("__Len__")))) {
                        try {
                            varCloned.put(k, Interpolator.interpolate(e.getValue(), varsFromPartial));
                        } catch (InterpolationException ex) {
                            throw new IvyException("unable to parse variable \"" + k + "\": " + ex.getMessage(), ex);
                        }
                    }
                }
            }
            Map<String, String> vars = varCloned.isEmpty() ? Map.of() : Dump.dumpStringPreserveCase(varCloned);
            String interpolated;
            try {
                interpolated = Interpolator.interpolate(content, vars);
            } catch (InterpolationException e) {
                throw new IvyException(e.getMessage(), e);
            }

            TestSuite ts = new TestSuite();
            try {
                Map<String, Object> input = Yaml.loadMap(interpolated);
                ts.name = Cast.toString(input.get("name"));
                ts.description = Cast.toString(input.get("description"));
                ts.secrets = stringList(input.get("secrets"));
                List<TestCaseLines> lines = Yaml.lineNumbers(content);
                if (input.get("testcases") instanceof List<?> tcs) {
                    for (int i = 0; i < tcs.size(); i++) {
                        TestCase tc = readTestCase(tcs.get(i));
                        if (i < lines.size()) {
                            tc.lines = lines.get(i);
                        }
                        ts.testCases.add(tc);
                    }
                }
            } catch (YamlException | RuntimeException e) {
                log.error(ctx.fields(), "file content: " + interpolated);
                throw new IvyException("error while unmarshal file \"" + file + "\": " + e.getMessage(), e);
            }
            log.info(ctx.fields(), "Has " + ts.secrets.size() + " Secrets");

            Path abs = file.toAbsolutePath().normalize();
            ts.workDir = abs.getParent().toString().replace('\\', '/');
            ts.filepath = file.toString();
            String filename = file.getFileName().toString();
            int dot = filename.lastIndexOf('.');
            ts.shortName = dot < 0 ? filename : filename.substring(0, dot);
            ts.filename = filename;
            ts.vars = varCloned;
            ts.vars.put("venom.testsuite.workdir", ts.workDir);
            ts.vars.put("venom.testsuite.name", ts.name);
            ts.vars.put("venom.testsuite.shortName", ts.shortName);
            ts.vars.put("venom.testsuite.filename", ts.filename);
            ts.vars.put("venom.testsuite.filepath", ts.filepath);
            OffsetDateTime now = OffsetDateTime.now();
            ts.vars.put("venom.datetime", now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            ts.vars.put("venom.timestamp", Long.toString(now.toEpochSecond()));
            tests.testSuites.add(ts);
        }
    }

    private static TestCase readTestCase(Object raw) {
        TestCase tc = new TestCase();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("a testcase must be a mapping, got " + Cast.toString(raw));
        }
        tc.name = Cast.toString(m.get("name"));
        tc.id = Cast.toString(m.get("id"));
        tc.vars = Cast.toStringMap(m.get("vars"));
        tc.skip = stringList(m.get("skip"));
        if (m.get("steps") instanceof List<?> steps) {
            for (Object step : steps) {
                tc.rawTestSteps.add(Yaml.toJson(step));
            }
        }
        return tc;
    }

    static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(e -> out.add(Cast.toString(e)));
        } else if (v != null) {
            out.add(Cast.toString(v));
        }
        return out;
    }

    /** Checks each step can be interpolated, parsed and resolved to an executor. */
    private void parseTestCase(TestCase tc) throws IvyException {
        Map<String, String> dvars = Dump.dumpStringPreserveCase(tc.vars);
        dvars.replaceAll((k, v) -> PartialYaml.escapeQuotes(v));
        for (String rawStep : tc.rawTestSteps) {
            Map<String, Object> step;
            try {
                step = Yaml.loadMap(Interpolator.interpolate(rawStep, dvars));
            } catch (InterpolationException e) {
                throw new IvyException(e.getMessage(), e);
            } catch (YamlException e) {
                throw new IvyException("unable to unmarshal teststep: " + e.getMessage(), e);
            }
            executorRunner(step);
        }
    }

    // ------------------------------------------------------------ user executors

    private void registerUserExecutors(RunContext ctx) throws IvyException {
        for (Path f : userExecutorFiles(ctx)) {
            log.debug(ctx.fields(), "Reading " + f);
            String content;
            try {
                content = Files.readString(f);
            } catch (IOException e) {
                throw new IvyException("unable to read file \"" + f + "\": " + e.getMessage(), e);
            }
            String ex = PartialYaml.read(content, "executor");
            if (ex.isEmpty()) {
                throw new IvyException("missing key 'executor' in \"" + f + "\"");
            }
            String name = ex.replaceFirst("executor:", "").strip();
            UserExecutor ux = new UserExecutor(name, PartialYaml.read(content, "input"), content, f.toString());
            UserExecutor existing = userExecutors.putIfAbsent(name, ux);
            if (existing != null) {
                throw new IvyException("unable to register user executor \"" + name + "\" from file \"" + f
                        + "\": executor \"" + name + "\" already exists (from file \"" + existing.filename() + "\")");
            }
            log.info(ctx.fields(), "User executor \"" + name + "\" registered");
            if (name.startsWith("Should")) {
                try {
                    assertions.registerUserAssertion(name, steps.userAssertion(ctx, name));
                } catch (Assertions.AssertException e) {
                    throw new IvyException("unable to register user executor \"" + name + "\" from file \"" + f
                            + "\" as user assertion: " + e.getMessage(), e);
                }
            }
        }
    }

    private List<Path> userExecutorFiles(RunContext ctx) {
        Set<Path> libPaths = new LinkedHashSet<>();
        if (!libDir.isEmpty()) {
            for (String lp : libDir.split(java.io.File.pathSeparator)) {
                if (!lp.isBlank()) {
                    libPaths.add(Path.of(lp.strip()).toAbsolutePath().normalize());
                }
            }
        }
        Map<String, String> vars = Dump.dumpStringPreserveCase(variables);
        String workdir = vars.getOrDefault("venom.testsuite.workdir", "");
        libPaths.add(Path.of(workdir).resolve("lib").toAbsolutePath().normalize());
        Set<Path> files = new TreeSet<>();
        for (Path p : libPaths) {
            if (!Files.isDirectory(p)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(p)) {
                walk.filter(f -> f.toString().endsWith(".yml") || f.toString().endsWith(".yaml")).forEach(files::add);
            } catch (IOException e) {
                log.warn(ctx.fields(), "Unable to list files in lib directory \"" + p + "\": " + e.getMessage());
            }
        }
        if (files.isEmpty()) {
            log.warn(ctx.fields(), "no user executor yml file selected");
        }
        return new ArrayList<>(files);
    }

    // ------------------------------------------------------------ executors

    /** venom's {@code GetExecutorRunner}: resolves the step type, exec by default for scripts. */
    ExecutorRunner executorRunner(Map<String, Object> step) throws IvyException {
        String name = stringValue(step, "type");
        String script = stringValue(step, "script");
        List<String> command = stringSliceValue(step, "command");
        if (name.isEmpty() && (!script.isEmpty() || !command.isEmpty())) {
            name = "exec";
        }
        int retry = intValue(step, "retry");
        List<String> retryIf = stringSliceValue(step, "retry_if");
        int delay = intValue(step, "delay");
        int timeout = intValue(step, "timeout");
        List<String> info = stringSliceValue(step, "info");

        if (name.isEmpty()) {
            return new ExecutorRunner(name, "builtin", retry, retryIf, delay, timeout, info, null, null);
        }
        Optional<ConnectorFacade> b = builtin.find(name);
        if (b.isPresent()) {
            return new ExecutorRunner(name, "builtin", retry, retryIf, delay, timeout, info, b.get(), null);
        }
        UserExecutor ux = userExecutors.get(name);
        if (ux != null) {
            return new ExecutorRunner(name, "user", retry, retryIf, delay, timeout, info, null, ux);
        }
        for (ConnectorInfoManager m : plugins) {
            Optional<ConnectorFacade> p = m.find(name);
            if (p.isPresent()) {
                return new ExecutorRunner(name, "plugin", retry, retryIf, delay, timeout, info, p.get(), null);
            }
        }
        throw new IvyException("user executor \"" + name + "\" not found - loaded executors are: "
                + userExecutors.keySet().stream().toList().toString().replace(",", ""));
    }

    static String stringValue(Map<String, Object> step, String name) throws IvyException {
        try {
            return Cast.toStringStrict(step.get(name));
        } catch (Cast.CastException e) {
            throw new IvyException("attribute \"" + name + "\" is not an string");
        }
    }

    static int intValue(Map<String, Object> step, String name) throws IvyException {
        try {
            return (int) Cast.toLongStrict(step.get(name));
        } catch (Cast.CastException e) {
            throw new IvyException("attribute \"" + name + "\" is not an integer");
        }
    }

    static List<String> stringSliceValue(Map<String, Object> step, String name) {
        Object v = step.get(name);
        if (v instanceof List<?>) {
            return Cast.toStringList(v);
        }
        String s = Cast.toString(v);
        return s.isEmpty() ? List.of() : List.of(s);
    }

    // ------------------------------------------------------------ process

    /** Runs all parsed suites. */
    public void process() throws IvyException {
        RunContext ctx = RunContext.root();
        tests.status = Status.RUN;
        tests.start = OffsetDateTime.now();
        log.debug(ctx.fields(), "nb testsuites: " + tests.testSuites.size());
        for (TestSuite ts : tests.testSuites) {
            ts.start = OffsetDateTime.now();
            runTestSuite(ctx, ts);
            ts.end = OffsetDateTime.now();
            ts.duration = seconds(ts.start, ts.end);
        }
        tests.end = OffsetDateTime.now();
        tests.duration = seconds(tests.start, tests.end);
        boolean failed = tests.testSuites.stream().anyMatch(ts -> ts.status == Status.FAIL);
        long skipped = tests.testSuites.stream().filter(ts -> ts.status == Status.SKIP).count();
        if (failed) {
            tests.status = Status.FAIL;
        } else if (skipped > 0 && skipped == tests.testSuites.size()) {
            tests.status = Status.SKIP;
        } else {
            tests.status = Status.PASS;
        }
        log.debug(ctx.fields(), "final status: " + tests.status);
    }

    static double seconds(OffsetDateTime start, OffsetDateTime end) {
        return ChronoUnit.NANOS.between(start, end) / 1e9;
    }

    private void runTestSuite(RunContext root, TestSuite ts) throws IvyException {
        ts.vars.putAll(variables);
        Map<String, String> vars = Dump.dumpStringPreserveCase(ts.vars);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            try {
                ts.vars.put(e.getKey(), Interpolator.interpolate(e.getValue(), vars));
            } catch (InterpolationException ex) {
                throw new IvyException("error while computing variable " + e.getKey() + "="
                        + xyz.fokion.ivy.spi.util.GoFormat.quote(e.getValue()) + ": " + ex.getMessage(), ex);
            }
        }
        ts.vars.put("venom.executable", ProcessHandle.current().info().command().orElse(""));
        ts.vars.put("venom.outputdir", outputDir);
        ts.vars.put("venom.libdir", libDir);
        ts.vars.put("venom.testsuite", ts.name);
        ts.computedVars = new LinkedHashMap<>();

        RunContext ctx = root.withTestsuite(ts.name);
        ctx = ctx.withSecrets(computeSecrets(ts, null));
        log.info(ctx.fields(), "Starting testsuite");
        int totalSteps = ts.testCases.stream().mapToInt(tc -> tc.rawTestSteps.size()).sum();
        ts.vars.put("venom.testsuite.totalSteps", (long) totalSteps);
        ts.status = Status.RUN;
        runTestCases(ctx, ts);

        boolean failed = false;
        int skipped = 0;
        for (TestCase tc : ts.testCases) {
            if (tc.status == Status.FAIL) {
                failed = true;
                ts.nbTestcasesFail++;
            } else if (tc.status == Status.SKIP) {
                skipped++;
                ts.nbTestcasesSkip++;
            } else if (tc.status == Status.PASS) {
                ts.nbTestcasesPass++;
            }
        }
        if (failed) {
            ts.status = Status.FAIL;
            tests.nbTestsuitesFail++;
        } else if (skipped > 0 && skipped == ts.testCases.size()) {
            ts.status = Status.SKIP;
            tests.nbTestsuitesSkip++;
        } else {
            ts.status = Status.PASS;
            tests.nbTestsuitesPass++;
        }
        log.info(ctx.fields(), "Ending testsuite");
    }

    private void runTestCases(RunContext ctx, TestSuite ts) {
        boolean verboseReport = verbose >= 1;
        println(" • " + ts.name + " (" + ts.filepath + ")");
        for (TestCase tc : ts.testCases) {
            tc.isEvaluated = true;
            print(" \t• " + tc.name);
            boolean hasFailure = false;
            if (!tc.hasSkipped()) {
                tc.start = OffsetDateTime.now();
                ts.status = Status.RUN;
                if (verboseReport) {
                    print("\n");
                }
                runTestCase(ctx, ts, tc);
                tc.end = OffsetDateTime.now();
                tc.duration = seconds(tc.start, tc.end);
            }
            int skippedSteps = 0;
            for (var r : tc.testStepResults) {
                if (r.status == Status.FAIL) {
                    hasFailure = true;
                }
                if (r.status == Status.SKIP) {
                    skippedSteps++;
                }
            }
            if (hasFailure) {
                tc.status = Status.FAIL;
            } else if (skippedSteps == tc.testStepResults.size()) {
                tc.status = Status.SKIP;
            } else if (tc.status != Status.SKIP) {
                tc.status = Status.PASS;
            }

            String indent = "";
            if (verboseReport) {
                indent = "\t  ";
                if (tc.testStepResults.isEmpty()) {
                    println("\t\t" + gray("• (all steps were skipped)"));
                    continue;
                }
            } else if (hasFailure) {
                println(" " + red(Status.FAIL));
            } else if (tc.status == Status.SKIP) {
                println(" " + gray(Status.SKIP));
                continue;
            } else {
                println(" " + green(Status.PASS));
            }
            for (String line : tc.computedVerbose) {
                printlnIndentedTrace(line, indent);
            }
            if (!verboseReport && hasFailure) {
                for (var r : tc.testStepResults) {
                    if ((r.computedInfo != null && !r.computedInfo.isEmpty()) || r.hasErrors()) {
                        println(" \t\t• " + r.name);
                        if (r.computedInfo != null) {
                            r.computedInfo.forEach(f -> println(" \t\t  " + cyan(f)));
                        }
                        r.errorList().forEach(f -> println(" \t\t  " + yellow(f.value)));
                    }
                }
            }
            if (stopOnFailure && tc.testStepResults.stream().anyMatch(r -> r.hasErrors())) {
                for (TestCase other : ts.testCases) {
                    if (other.status == null) {
                        other.status = Status.SKIP;
                        other.isEvaluated = true;
                        other.addSkipped("===== stop-on-failure: enabled =====");
                    }
                }
                return;
            }
            tc.computedVars.forEach((k, v) -> ts.computedVars.put(tc.name + "." + k, v));
        }
    }

    private void runTestCase(RunContext root, TestSuite ts, TestCase tc) {
        RunContext ctx = root.withTestcase(tc.name);
        tc.testSuiteVars = new LinkedHashMap<>(ts.vars);
        tc.vars = new LinkedHashMap<>(ts.vars);
        tc.vars.put("venom.testcase", tc.name);
        tc.vars.putAll(ts.computedVars);
        tc.vars.put("venom.testcase.totalSteps", (long) tc.rawTestSteps.size());
        tc.computedVars = new LinkedHashMap<>();
        ctx = ctx.withSecrets(computeSecrets(ts, tc));
        log.info(ctx.fields(), "Starting testcase");
        steps.runTestSteps(ctx, tc, null);
        log.info(ctx.fields(), "Ending testcase");
    }

    /** venom's {@code processSecrets}: secret values, plus their derived encodings. */
    List<String> computeSecrets(TestSuite ts, TestCase tc) {
        List<String> computed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectSecrets(ts.vars, ts.secrets, computed, seen);
        if (tc != null) {
            collectSecrets(tc.vars, ts.secrets, computed, seen);
        }
        Map<String, Object> derived = new LinkedHashMap<>(ts.vars);
        if (tc != null) {
            derived.putAll(tc.vars);
        }
        IvyLog.appendDerivedSecrets(computed, seen, derived, ts.secrets);
        return computed;
    }

    private static void collectSecrets(Map<String, Object> vars, List<String> secretKeys, List<String> computed,
            Set<String> seen) {
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            if (secretKeys.contains(e.getKey())) {
                String value = xyz.fokion.ivy.spi.util.GoFormat.sprint(e.getValue());
                if (seen.add(value)) {
                    computed.add(value);
                }
            }
        }
    }
}
