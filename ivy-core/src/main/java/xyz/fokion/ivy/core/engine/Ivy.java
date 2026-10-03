package xyz.fokion.ivy.core.engine;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.connector.LocalConnectorInfoManager;
import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.core.log.Secrets;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestSuite;
import xyz.fokion.ivy.core.model.Tests;
import xyz.fokion.ivy.core.expr.ExprException;
import xyz.fokion.ivy.core.expr.Expression;
import xyz.fokion.ivy.core.expr.Scope;
import xyz.fokion.ivy.core.expr.Template;
import xyz.fokion.ivy.core.expr.Values;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.util.Slug;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.core.yaml.Yaml.TestCaseLines;
import xyz.fokion.ivy.core.yaml.Yaml.YamlException;

/**
 * The test runner: reads suites, runs them and keeps the
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

    /** Rose for errors that stop the whole run (unreadable suites, unknown executors...). */
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
    /** The secret values of the run, hidden in the log, the console and the reports. */
    final Secrets secrets = new Secrets();
    final Tests tests = new Tests();
    final Map<String, UserExecutor> userExecutors = new LinkedHashMap<>();
    private final ConnectorInfoManager builtin;
    private final List<ConnectorInfoManager> plugins = new ArrayList<>();
    final AssertionChecker checker = new AssertionChecker();
    final StepProcessor steps = new StepProcessor(this);

    String libDir = "";
    xyz.fokion.ivy.core.gherkin.TagExpression tagFilter = xyz.fokion.ivy.core.gherkin.TagExpression.ALL;
    String outputFormat = "xml";
    String outputDir = "";
    boolean stopOnFailure;
    boolean htmlReport;
    /** The number of suites run at the same time. */
    int parallel = 1;
    int verbose;
    /** Report strings longer than this are cut; 0 keeps them whole. */
    int reportMaxValue = 64 * 1024;

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

    /** Runs up to {@code n} suites at the same time; 1 runs them one after the other. */
    public Ivy parallel(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("parallel must be at least 1, got " + n);
        }
        this.parallel = n;
        return this;
    }

    public Ivy htmlReport(boolean b) {
        this.htmlReport = b;
        return this;
    }

    /** Strings longer than this are cut in reports; 0 keeps them whole. */
    public Ivy reportMaxValue(int max) {
        this.reportMaxValue = Math.max(0, max);
        return this;
    }

    public Ivy verbose(int level) {
        this.verbose = level;
        return this;
    }

    /** Runs only the scenarios whose tags match a Cucumber tag expression. */
    public Ivy tags(String expression) {
        this.tagFilter = xyz.fokion.ivy.core.gherkin.TagExpression.parse(expression);
        return this;
    }

    public Ivy colors(boolean b) {
        this.colors = b;
        return this;
    }

    public Tests tests() {
        return tests;
    }

    public void addVariables(Map<String, Object> vars) {
        variables.putAll(vars);
    }

    /** Adds variables whose values are secret ({@code --secret name=value}). */
    public void addSecrets(Map<String, Object> s) {
        variables.putAll(s);
        s.forEach((name, value) -> {
            secrets.addName(name);
            secrets.add(value);
        });
    }

    /** The secrets of the run. */
    public Secrets secrets() {
        return secrets;
    }

    /** Adds a source of plugin connectors: a bundles directory or a connector server. */
    public void addConnectors(ConnectorInfoManager manager) {
        plugins.add(manager);
    }

    /** The connectors of the run: built in, then those of each plugin source. */
    public List<xyz.fokion.ivy.core.connector.ConnectorInfo> connectorInfos() {
        List<xyz.fokion.ivy.core.connector.ConnectorInfo> infos = new ArrayList<>(builtin.connectorInfos());
        plugins.forEach(m -> infos.addAll(m.connectorInfos()));
        return infos;
    }

    /** The connector of a step type, if one handles it. */
    Optional<xyz.fokion.ivy.core.connector.ConnectorInfo> connectorInfo(String type) {
        return connectorInfos().stream().filter(i -> i.type().equals(type)).findFirst();
    }

    // ------------------------------------------------------------ warnings, progress, cancellation

    private final List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());

    /** Records a problem that does not stop the run; it is written to the log. */
    void warn(String warning) {
        warnings.add(warning);
        log.warn(IvyLog.Fields.EMPTY, warning);
    }

    /** The warnings of parse and run, such as an assertion reading an unknown result field. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Called when a test case ended, from the thread of its suite. */
    public interface TestCaseListener {
        void ended(TestSuite ts, TestCase tc);
    }

    private volatile TestCaseListener testCaseListener = (ts, tc) -> {
    };

    /** Calls {@code listener} each time a test case ends; it may be called from several threads. */
    public Ivy onTestCaseEnd(TestCaseListener listener) {
        this.testCaseListener = listener;
        return this;
    }

    private volatile boolean cancelled;
    /** The threads running suites, interrupted by {@link #cancel()}. */
    private final Set<Thread> suiteThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Stops the run: test cases not started yet are skipped, running steps are interrupted.
     * {@link #process()} returns once the suites that were running have ended.
     */
    public void cancel() {
        cancelled = true;
        suiteThreads.forEach(Thread::interrupt);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    // ------------------------------------------------------------ console

    void print(String s) {
        out.print(secrets.hide(s));
    }

    void println(String s) {
        out.println(secrets.hide(s));
    }

    void printlnIndentedTrace(String s, String indent) {
        println(indentedTrace(s, indent));
    }

    private String indentedTrace(String s, String indent) {
        return "\t  " + indent + gray("[trac]") + " " + gray(s);
    }

    /** The context of the run, before any suite: the run log and the console. */
    private RunContext rootContext() {
        return RunContext.root(log, Console.direct(out, secrets));
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

    /** Opens the log file in the output directory. */
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
            log = new IvyLog(logWriter, level, secrets);
            printlnIndentedTrace("writing " + logFile, "");
        } catch (IOException e) {
            throw new IvyException("unable to write log file: " + e.getMessage(), e);
        }
    }

    /** Flushes the log and releases the connectors. */
    public void close() {
        log.flush();
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
        RunContext ctx = rootContext();
        List<Path> files = SuiteFiles.find(paths);
        readFiles(ctx, files);
        registerUserExecutors(ctx);
        for (UserExecutor ux : userExecutors.values()) {
            SuiteValidator.validateExecutor(ux, this);
        }
        // every suite is checked, so that one run reports the errors of all of them
        List<String> errors = new ArrayList<>();
        for (TestSuite ts : tests.testSuites) {
            log.info(ctx.fields(), "Parsing testsuite " + ts.filepath);
            for (int i = 0; i < ts.testCases.size(); i++) {
                TestCase tc = ts.testCases.get(i);
                tc.originalName = tc.name;
                tc.number = i + 1;
                tc.name = Slug.make(tc.name);
            }
            try {
                SuiteValidator.validate(ts, variables.keySet(), this);
            } catch (IvyException e) {
                errors.add(e.getMessage());
            }
        }
        secrets.warnings().forEach(w -> log.warn(ctx.fields(), w));
        if (!errors.isEmpty()) {
            throw new IvyException(String.join("\n", errors));
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
            if (SuiteFiles.isFeature(file)) {
                tests.testSuites.add(finishSuite(readFeature(file, content), file));
                continue;
            }
            TestSuite ts = new TestSuite();
            try {
                Map<String, Object> input = Yaml.loadMap(content);
                ts.name = Cast.toString(input.get("name"));
                ts.description = Cast.toString(input.get("description"));
                ts.secrets = stringList(input.get("secrets"));
                secrets.addNames(ts.secrets);
                if (input.containsKey("parallel")) {
                    if (!(input.get("parallel") instanceof Boolean p)) {
                        throw new IllegalArgumentException("'parallel' must be true or false");
                    }
                    ts.parallel = p;
                }
                if (input.get("vars") instanceof Map<?, ?> vars) {
                    vars.forEach((k, v) -> ts.vars.put(String.valueOf(k), v));
                } else if (input.get("vars") != null) {
                    throw new IllegalArgumentException("'vars' must be a map");
                }
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
                String hint = String.valueOf(e.getMessage()).contains("could not determine a constructor for the tag !")
                        ? "\n(YAML reads a leading ! as a tag: quote the expression, \"!a\", or write isEmpty(a) or a == false)" : "";
                throw new IvyException("error while reading file \"" + file + "\": " + e.getMessage() + hint, e);
            }
            log.info(ctx.fields(), "Has " + ts.secrets.size() + " Secrets");
            tests.testSuites.add(finishSuite(ts, file));
        }
    }

    /** Sets the file attributes of a suite. */
    private static TestSuite finishSuite(TestSuite ts, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        ts.workDir = abs.getParent().toString().replace('\\', '/');
        ts.filepath = file.toString();
        String filename = file.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        ts.shortName = dot < 0 ? filename : filename.substring(0, dot);
        ts.filename = filename;
        return ts;
    }

    private final Map<Path, xyz.fokion.ivy.core.gherkin.StepLibrary> stepLibraries = new java.util.HashMap<>();

    /**
     * Reads a feature; its step definitions are the {@code *.steps.yml} files of the lib
     * directories, of {@code steps/} and {@code lib/} in the working directory and next to the
     * feature.
     */
    private TestSuite readFeature(Path file, String content) throws IvyException {
        Path featureDir = file.toAbsolutePath().normalize().getParent();
        xyz.fokion.ivy.core.gherkin.StepLibrary library = stepLibraries.get(featureDir);
        if (library == null) {
            List<Path> dirs = new ArrayList<>(libDirs());
            dirs.addAll(List.of(Path.of("steps"), Path.of("lib"), featureDir.resolve("steps"), featureDir.resolve("lib")));
            Set<Path> unique = new LinkedHashSet<>();
            dirs.forEach(d -> unique.add(d.toAbsolutePath().normalize()));
            try {
                library = xyz.fokion.ivy.core.gherkin.StepLibrary.load(new ArrayList<>(unique));
            } catch (IOException | YamlException | RuntimeException e) {
                throw new IvyException("unable to load step definitions: " + e.getMessage(), e);
            }
            stepLibraries.put(featureDir, library);
        }
        try {
            xyz.fokion.ivy.core.gherkin.Gherkin.Feature feature =
                    xyz.fokion.ivy.core.gherkin.GherkinParser.parse(file.toString(), content);
            return xyz.fokion.ivy.core.gherkin.FeatureLoader.load(file.toString(), feature, library, tagFilter);
        } catch (xyz.fokion.ivy.core.gherkin.GherkinParser.GherkinException e) {
            throw new IvyException(e.getMessage(), e);
        }
    }

    private List<Path> libDirs() {
        List<Path> dirs = new ArrayList<>();
        if (!libDir.isEmpty()) {
            for (String lp : libDir.split(java.io.File.pathSeparator)) {
                if (!lp.isBlank()) {
                    dirs.add(Path.of(lp.strip()));
                }
            }
        }
        return dirs;
    }

    private static TestCase readTestCase(Object raw) {
        TestCase tc = new TestCase();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("a testcase must be a mapping, got " + Cast.toString(raw));
        }
        tc.name = Cast.toString(m.get("name"));
        tc.id = Cast.toString(m.get("id"));
        if (m.containsKey("skip")) {
            throw new IllegalArgumentException("test case \"" + tc.name + "\": 'skip' is now 'if': an expression, "
                    + "the test case runs when it is true");
        }
        if (m.get("vars") instanceof Map<?, ?> vars) {
            vars.forEach((k, v) -> tc.vars.put(String.valueOf(k), v));
        }
        if (m.get("if") != null) {
            if (!(m.get("if") instanceof String condition)) {
                throw new IllegalArgumentException("test case \"" + tc.name + "\": 'if' must be an expression");
            }
            tc.condition = condition;
        }
        if (m.get("steps") instanceof List<?> steps) {
            for (Object step : steps) {
                if (!(step instanceof Map<?, ?> sm)) {
                    throw new IllegalArgumentException("test case \"" + tc.name + "\": a step must be a mapping, got "
                            + Cast.toString(step));
                }
                Map<String, Object> copy = new LinkedHashMap<>();
                sm.forEach((k, v) -> copy.put(String.valueOf(k), v));
                tc.steps.add(copy);
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

    // ------------------------------------------------------------ user executors

    private void registerUserExecutors(RunContext ctx) throws IvyException {
        for (Path f : userExecutorFiles(ctx)) {
            log.debug(ctx.fields(), "Reading " + f);
            Map<String, Object> content;
            try {
                content = Yaml.loadMap(Files.readString(f));
            } catch (IOException e) {
                throw new IvyException("unable to read file \"" + f + "\": " + e.getMessage(), e);
            } catch (YamlException e) {
                throw new IvyException("unable to read executor \"" + f + "\": " + e.getMessage(), e);
            }
            if (!(content.get("executor") instanceof String name) || name.isBlank()) {
                if (f.getFileName().toString().endsWith(".steps.yml") || f.getFileName().toString().endsWith(".steps.yaml")) {
                    continue;
                }
                throw new IvyException("missing key 'executor' in \"" + f + "\"");
            }
            Map<String, Object> input = new LinkedHashMap<>();
            if (content.get("input") instanceof Map<?, ?> in) {
                in.forEach((k, v) -> input.put(String.valueOf(k), v));
            }
            List<Map<String, Object>> steps = new ArrayList<>();
            if (content.get("steps") instanceof List<?> l) {
                for (Object s : l) {
                    if (!(s instanceof Map<?, ?> sm)) {
                        throw new IvyException("executor \"" + name + "\" in \"" + f + "\": a step must be a mapping");
                    }
                    Map<String, Object> copy = new LinkedHashMap<>();
                    sm.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    steps.add(copy);
                }
            }
            UserExecutor ux = new UserExecutor(name, input, steps, content.get("output"), f.toString());
            UserExecutor existing = userExecutors.putIfAbsent(name, ux);
            if (existing != null) {
                throw new IvyException("unable to register user executor \"" + name + "\" from file \"" + f
                        + "\": executor \"" + name + "\" already exists (from file \"" + existing.filename() + "\")");
            }
            log.info(ctx.fields(), "User executor \"" + name + "\" registered");
        }
    }

    private List<Path> userExecutorFiles(RunContext ctx) {
        Set<Path> libPaths = new LinkedHashSet<>();
        libDirs().forEach(d -> libPaths.add(d.toAbsolutePath().normalize()));
        libPaths.add(Path.of("lib").toAbsolutePath().normalize());
        for (TestSuite ts : tests.testSuites) {
            libPaths.add(Path.of(ts.workDir).resolve("lib").toAbsolutePath().normalize());
        }
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
            log.debug(ctx.fields(), "no user executor yml file selected");
        }
        return new ArrayList<>(files);
    }

    // ------------------------------------------------------------ executors

    /** Fails when no connector or user executor has this type. */
    void checkExecutor(String name) throws IvyException {
        if (builtin.find(name).isPresent() || userExecutors.containsKey(name)) {
            return;
        }
        for (ConnectorInfoManager m : plugins) {
            if (m.find(name).isPresent()) {
                return;
            }
        }
        throw new IvyException("unknown step type \"" + name + "\" - user executors are: "
                + userExecutors.keySet().stream().toList().toString().replace(",", ""));
    }

    /**
     * Resolves the step type, exec by default for scripts; {@code step} is the rendered step,
     * {@code raw} the step as written (for its expressions).
     */
    ExecutorRunner executorRunner(Map<String, Object> step, Map<String, Object> raw) throws IvyException {
        String name = stringValue(step, "type");
        String script = stringValue(step, "script");
        List<String> command = stringSliceValue(step, "command");
        if (name.isEmpty() && (!script.isEmpty() || !command.isEmpty())) {
            name = "exec";
        }
        int retry = intValue(step, "retry");
        String retryIf = raw.get("retryIf") instanceof String s ? s : "";
        Duration delay = secondsValue(step, "delay");
        Duration timeout = secondsValue(step, "timeout");
        List<String> info = stringSliceValue(raw, "info");

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
        throw new IvyException("unknown step type \"" + name + "\" - user executors are: "
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
        long n;
        try {
            n = Cast.toLongExact(step.get(name));
        } catch (Cast.CastException e) {
            throw new IvyException("attribute \"" + name + "\" is not a whole number: " + e.getMessage());
        }
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw new IvyException("attribute \"" + name + "\" must be 0 or more, got " + n);
        }
        return (int) n;
    }

    /** A duration in seconds, whole or not ({@code 0.5} is half a second); zero when absent. */
    static Duration secondsValue(Map<String, Object> step, String name) throws IvyException {
        double seconds;
        try {
            seconds = Cast.toDoubleStrict(step.get(name));
        } catch (Cast.CastException e) {
            throw new IvyException("attribute \"" + name + "\" is not a number of seconds: " + e.getMessage());
        }
        if (!(seconds >= 0) || Double.isInfinite(seconds)) {
            throw new IvyException("attribute \"" + name + "\" must be 0 or more seconds, got " + step.get(name));
        }
        return Duration.ofNanos(Math.round(seconds * 1e9));
    }

    /** Seconds as written in suites: {@code 1}, {@code 0.5}. */
    static String formatSeconds(Duration d) {
        return java.math.BigDecimal.valueOf(d.toNanos(), 9).stripTrailingZeros().toPlainString();
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

    /** Runs all parsed suites, up to {@link #parallel} at a time. */
    public void process() throws IvyException {
        RunContext ctx = rootContext();
        tests.status = Status.RUN;
        tests.start = OffsetDateTime.now();
        log.debug(ctx.fields(), "nb testsuites: " + tests.testSuites.size());
        // the lines of parse and of the run come first, each suite then writes its own
        log.flush();
        try {
            Scheduler.run(tests.testSuites, parallel, ts -> {
                ts.start = OffsetDateTime.now();
                runTestSuite(ctx, ts);
                ts.end = OffsetDateTime.now();
                ts.duration = seconds(ts.start, ts.end);
            });
        } finally {
            countTestSuites();
        }
        secrets.warnings().forEach(w -> log.warn(ctx.fields(), w));
        tests.end = OffsetDateTime.now();
        tests.duration = seconds(tests.start, tests.end);
        tests.status = Status.aggregate(tests.testSuites.stream().map(ts -> ts.status).toList(), Status.PASS);
        log.debug(ctx.fields(), "final status: " + tests.status);
    }

    /** The suite totals, counted once all suites ran. */
    private void countTestSuites() {
        tests.nbTestsuitesFail = 0;
        tests.nbTestsuitesPass = 0;
        tests.nbTestsuitesSkip = 0;
        for (TestSuite ts : tests.testSuites) {
            if (ts.status == Status.FAIL) {
                tests.nbTestsuitesFail++;
            } else if (ts.status == Status.SKIP) {
                tests.nbTestsuitesSkip++;
            } else if (ts.status == Status.PASS) {
                tests.nbTestsuitesPass++;
            }
        }
    }

    static double seconds(OffsetDateTime start, OffsetDateTime end) {
        return ChronoUnit.NANOS.between(start, end) / 1e9;
    }

    /** The environment variables, read once: {@code env.HOME}. */
    private static final Map<String, Object> ENV = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(System.getenv()));

    /** The scope of a suite being run, with the {@code ivy} built-ins of the suite. */
    private record SuiteRun(Scope scope, Map<String, Object> ivy, Map<String, Object> cases) {
    }

    private void runTestSuite(RunContext root, TestSuite ts) throws IvyException {
        int totalSteps = ts.testCases.stream().mapToInt(tc -> tc.steps.size()).sum();
        Map<String, Object> suite = new LinkedHashMap<>();
        suite.put("name", ts.name);
        suite.put("shortName", ts.shortName);
        suite.put("file", ts.filename);
        suite.put("path", ts.filepath);
        suite.put("workdir", ts.workDir);
        suite.put("totalSteps", (long) totalSteps);
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> ivyVars = new LinkedHashMap<>();
        ivyVars.put("suite", suite);
        ivyVars.put("datetime", now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        ivyVars.put("timestamp", now.toEpochSecond());
        ivyVars.put("executable", ProcessHandle.current().info().command().orElse(""));
        ivyVars.put("outputDir", outputDir);
        ivyVars.put("libDir", libDir);

        Map<String, Object> cases = new LinkedHashMap<>();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("env", ENV);
        base.put("cases", cases);
        base.put("ivy", ivyVars);

        // suite variables in order, each may use the previous ones; command line variables win
        Map<String, Object> suiteVars = new LinkedHashMap<>();
        Scope scope = Scope.of(base).child(suiteVars).child(variables);
        for (Map.Entry<String, Object> e : ts.vars.entrySet()) {
            if (variables.containsKey(e.getKey())) {
                continue;
            }
            try {
                suiteVars.put(e.getKey(), Template.interpolate(e.getValue(), scope));
            } catch (ExprException ex) {
                throw new IvyException("error while computing variable " + e.getKey() + " of " + ts.filepath + ": "
                        + ex.getMessage(), ex);
            }
        }
        ts.vars = new LinkedHashMap<>(suiteVars);
        ts.vars.putAll(variables);
        SuiteRun suiteRun = new SuiteRun(scope, ivyVars, cases);
        registerSecrets(scope);

        // suites running at the same time hold their lines until each test case ends
        Console console = parallel > 1 ? Console.held(out, secrets) : Console.direct(out, secrets);
        RunContext ctx = root.withTestsuite(ts.name, ts.filepath, log.buffer(), console);
        ctx.log().info(ctx.fields(), "Starting testsuite");
        ts.status = Status.RUN;
        suiteThreads.add(Thread.currentThread());
        try {
            runTestCases(ctx, ts, suiteRun);
        } finally {
            suiteThreads.remove(Thread.currentThread());
            if (cancelled) {
                // the interrupt of cancel() was for the steps, not for whoever runs the suites
                Thread.interrupted();
            }
            ctx.log().info(ctx.fields(), "Ending testsuite");
            ctx.log().flush();
            console.flush();
        }

        for (TestCase tc : ts.testCases) {
            if (tc.status == Status.FAIL) {
                ts.nbTestcasesFail++;
            } else if (tc.status == Status.SKIP) {
                ts.nbTestcasesSkip++;
            } else if (tc.status == Status.PASS) {
                ts.nbTestcasesPass++;
            }
        }
        ts.status = Status.aggregate(ts.testCases.stream().map(tc -> tc.status).toList(), Status.PASS);
    }

    /**
     * Runs the test cases of a suite one after the other. Each test case is written to the log and
     * the console when it ends; with suites in parallel, its console lines start with the suite.
     */
    private void runTestCases(RunContext ctx, TestSuite ts, SuiteRun suite) {
        Console console = ctx.console();
        boolean verboseReport = verbose >= 1;
        String label = parallel > 1 ? "[" + ts.shortName + "] " : "";
        console.println(" • " + ts.name + " (" + ts.filepath + ")");
        console.flush();
        for (TestCase tc : ts.testCases) {
            try {
                tc.isEvaluated = true;
                if (cancelled && !tc.hasSkipped()) {
                    tc.addSkipped("===== cancelled =====");
                }
                console.print(" \t• " + label + tc.name);
                if (!tc.hasSkipped()) {
                    tc.start = OffsetDateTime.now();
                    ts.status = Status.RUN;
                    if (verboseReport) {
                        console.print("\n");
                    }
                    runTestCase(ctx, tc, suite);
                    tc.end = OffsetDateTime.now();
                    tc.duration = seconds(tc.start, tc.end);
                }
                Status steps = Status.aggregate(tc.testStepResults.stream().map(r -> r.status).toList(), Status.SKIP);
                // a test case skipped by its condition stays skipped
                tc.status = steps == Status.PASS && tc.status == Status.SKIP ? Status.SKIP : steps;
                boolean hasFailure = tc.status == Status.FAIL;

                String indent = "";
                if (verboseReport) {
                    indent = "\t  ";
                    if (tc.testStepResults.isEmpty()) {
                        console.println("\t\t" + gray("• (all steps were skipped)"));
                        continue;
                    }
                } else if (hasFailure) {
                    console.println(" " + red(Status.FAIL));
                } else if (tc.status == Status.SKIP) {
                    console.println(" " + gray(Status.SKIP));
                    continue;
                } else {
                    console.println(" " + green(Status.PASS));
                }
                for (String line : tc.computedVerbose) {
                    console.println(indentedTrace(line, indent));
                }
                if (!verboseReport && hasFailure) {
                    for (var r : tc.testStepResults) {
                        if ((r.computedInfo != null && !r.computedInfo.isEmpty()) || r.hasErrors()) {
                            console.println(" \t\t• " + r.name);
                            if (r.computedInfo != null) {
                                r.computedInfo.forEach(f -> console.println(" \t\t  " + cyan(f)));
                            }
                            r.errorList().forEach(f -> console.println(" \t\t  " + yellow(f.value.replace("\n", "\n \t\t  "))));
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
            } finally {
                ctx.log().flush();
                console.flush();
                testCaseListener.ended(ts, tc);
            }
        }
    }

    private void runTestCase(RunContext root, TestCase tc, SuiteRun suite) {
        RunContext ctx = root.withTestcase(tc.name);
        // later test cases read the values this one sets as cases.<name>.<value>
        suite.cases().put(tc.name, tc.computedVars);
        suite.cases().putIfAbsent(tc.originalName, tc.computedVars);

        Map<String, Object> caseVars = new LinkedHashMap<>();
        caseVars.put("name", tc.originalName);
        caseVars.put("tags", tc.gherkin == null ? List.of() : tc.gherkin.tags());
        caseVars.put("totalSteps", (long) tc.steps.size());
        Map<String, Object> ivyCase = new LinkedHashMap<>(suite.ivy());
        ivyCase.put("case", caseVars);
        Scope scope = suite.scope().with("ivy", ivyCase);

        Map<String, Object> declared = tc.vars;
        tc.vars = new LinkedHashMap<>();
        Scope varScope = scope.child(tc.vars);
        try {
            for (Map.Entry<String, Object> e : declared.entrySet()) {
                tc.vars.put(e.getKey(), Template.interpolate(e.getValue(), varScope));
            }
        } catch (ExprException ex) {
            failTestCase(ctx, tc, "error while computing the variables: " + ex.getMessage());
            return;
        }
        Scope tcScope = varScope.child(tc.computedVars);
        registerSecrets(varScope);
        ctx.log().info(ctx.fields(), "Starting testcase");
        if (tc.gherkin != null && !tc.gherkin.problems().isEmpty()) {
            // a scenario with undefined steps does not run
            var r = new xyz.fokion.ivy.core.model.TestStepResult();
            r.name = tc.originalName;
            tc.gherkin.problems().forEach(r::appendError);
            tc.testStepResults.add(r);
            ctx.log().error(ctx.fields(), String.join("\n", tc.gherkin.problems()));
            return;
        }
        if (!tc.condition.isBlank()) {
            boolean run;
            try {
                run = Values.truthy(Expression.compile(tc.condition).evaluate(tcScope));
            } catch (ExprException ex) {
                failTestCase(ctx, tc, "cannot evaluate if: " + ex.getMessage());
                return;
            }
            if (!run) {
                tc.status = Status.SKIP;
                tc.addSkipped("test case \"" + tc.originalName + "\" skipped: if " + tc.condition + " is false");
                ctx.log().info(ctx.fields(), "skipped: if " + tc.condition + " is false");
                return;
            }
        }
        steps.runTestSteps(new StepProcessor.CaseRun(ctx, tc, tcScope, suite.scope(), ivyCase, null));
        ctx.log().info(ctx.fields(), "Ending testcase");
    }

    private void failTestCase(RunContext ctx, TestCase tc, String message) {
        var r = new xyz.fokion.ivy.core.model.TestStepResult();
        r.name = tc.originalName;
        r.appendFailure(Failures.newFailure(ctx, tc, 0, -1, -1, "", message));
        r.status = Status.FAIL;
        tc.testStepResults.add(r);
        ctx.log().error(ctx.fields(), message);
    }

    /**
     * Adds the values of the secret variables a scope defines; a secret name may be a path, such
     * as {@code db.password}.
     */
    void registerSecrets(Scope scope) {
        for (String name : secrets.names()) {
            try {
                Expression e = Expression.compile(name);
                if (e.isPath() && scope.has(e.roots().iterator().next())) {
                    secrets.add(e.evaluate(scope));
                }
            } catch (ExprException ignored) {
                // a name that is not a path holds no value here
            }
        }
        if (scope.lookup("basic_auth_password") instanceof Object password && secrets.isSecret(password)) {
            Object user = scope.lookup("basic_auth_user");
            secrets.addBasicAuth(user == Scope.MISSING ? "" : Values.display(user), Values.display(password));
        }
    }
}
