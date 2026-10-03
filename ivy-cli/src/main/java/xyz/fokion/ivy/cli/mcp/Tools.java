package xyz.fokion.ivy.cli.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.connector.ConnectorInfo;
import xyz.fokion.ivy.core.connector.ConnectorInfo.PropertyInfo;
import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.engine.Ivy;
import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.expr.ExprException;
import xyz.fokion.ivy.core.expr.Expression;
import xyz.fokion.ivy.core.expr.Scope;
import xyz.fokion.ivy.core.expr.Template;
import xyz.fokion.ivy.core.expr.Values;
import xyz.fokion.ivy.core.gherkin.StepLibrary;
import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestStepResult;
import xyz.fokion.ivy.core.model.TestSuite;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.spi.util.Json;

/**
 * The tools of the MCP server. The client model does the writing: it learns the step types and
 * the syntax, writes a suite with {@code write_suite} (which keeps only valid suites), runs it
 * and fixes it from the reported values.
 * <p>
 * Every path is inside the workspace: checked after symbolic links are resolved.
 */
final class Tools {

    static final String INSTRUCTIONS = """
            ivy runs integration test suites written in YAML (or Gherkin .feature files with *.steps.yml).
            To write a test from a description or a Gherkin file:
            1. call list_step_types and describe_syntax to learn the step types, their result fields and the syntax;
            2. write the suite with write_suite: it is saved only when it is valid, otherwise the errors are returned;
            3. run it with run_suite, and fix the assertions from the reported values until it passes.
            For a .feature file, gherkin_steps lists the steps without a definition and proposes one.""";

    /** The longest string kept in the values a run reports. */
    static final int MAX_VALUE = 2000;

    /** A running tool call: its run, so that it can be cancelled, and where to report progress. */
    static final class Call {
        interface Progress {
            void report(long done, long total, String message);
        }

        private final Progress progress;
        private volatile boolean cancelled;
        private volatile Ivy ivy;

        Call(Progress progress) {
            this.progress = progress;
        }

        void attach(Ivy run) {
            ivy = run;
            if (cancelled) {
                run.cancel();
            }
        }

        void cancel() {
            cancelled = true;
            Ivy run = ivy;
            if (run != null) {
                run.cancel();
            }
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    /** A problem with the arguments or the files of a call: reported to the model, not a protocol error. */
    private static final class ToolException extends Exception {
        ToolException(String message) {
            super(message);
        }
    }

    private interface Handler {
        Map<String, Object> handle(Map<String, Object> args, Call call) throws ToolException, IOException;
    }

    private record Tool(String name, String title, String description, Map<String, Object> inputSchema,
            Map<String, Object> annotations, Handler handler) {
    }

    private final McpServer.RunFactory runs;
    private final Path workspace;
    private final List<ConnectorInfoManager> connectors;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    Tools(McpServer.RunFactory runs, Path workspace, boolean noRun, List<ConnectorInfoManager> connectors) {
        this.runs = runs;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.connectors = connectors.stream().<ConnectorInfoManager>map(Shared::new).toList();
        add("list_step_types", "Step types",
                "Lists the step types (connectors): their properties, the fields of their result and an example step.",
                schema(Map.of(), List.of()), readOnly(), (a, c) -> listStepTypes());
        add("describe_syntax", "Suite syntax",
                "Describes the suite syntax: ${...} templates, expressions used by assertions, built-in variables and secrets.",
                schema(Map.of(), List.of()), readOnly(), (a, c) -> describeSyntax());
        add("validate_suite", "Validate a suite",
                "Checks a suite (.yml, .yaml or .feature) in the workspace without running it. Returns the errors, "
                        + "one per test case with file:line, and warnings such as an assertion reading an unknown result field.",
                schema(Map.of("path", string("the suite, relative to the workspace")), List.of("path")), readOnly(),
                (a, c) -> validateSuite(a));
        add("write_suite", "Write a suite",
                "Writes a suite (.yml, .yaml, .feature or .steps.yml) in the workspace, only if it is valid: it is checked in "
                        + "its folder first, so relative fixture and script paths resolve. When invalid, nothing is written "
                        + "and the errors are returned. Replaces an existing file.",
                schema(Map.of("path", string("the file to write, relative to the workspace"),
                        "content", string("the YAML or Gherkin content")), List.of("path", "content")),
                annotations(false, true, true), (a, c) -> writeSuite(a));
        if (!noRun) {
            add("run_suite", "Run a suite",
                    "Runs a suite of the workspace and returns the status of each test case, with the errors, values and "
                            + "results of failed steps. Steps run for real: exec steps run commands, http steps send requests.",
                    schema(Map.of("path", string("the suite, relative to the workspace"),
                            "vars", Map.of("type", "object", "description", "variables, as on the command line"),
                            "tags", string("a tag expression for Gherkin scenarios, e.g. \"@smoke and not @slow\""),
                            "testcases", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "run only these test cases, by name")),
                            List.of("path")),
                    annotations(false, true, false), this::runSuite);
        }
        add("evaluate_expression", "Evaluate an expression",
                "Evaluates an expression (as in assertions) or a ${...} template against the given variables, "
                        + "for instance to check an assertion on a value a run reported.",
                schema(Map.of("expression", string("e.g. result.body.items.length > 0"),
                        "scope", Map.of("type", "object", "description", "the variables, e.g. {\"result\": {...}}")),
                        List.of("expression")),
                readOnly(), (a, c) -> evaluate(a));
        add("list_step_definitions", "Gherkin step definitions",
                "Lists the Gherkin step definitions (*.steps.yml) of a folder and of its steps/ and lib/ folders.",
                schema(Map.of("dir", string("the folder, relative to the workspace (default: the workspace)")), List.of()),
                readOnly(), (a, c) -> listStepDefinitions(a));
        add("gherkin_steps", "Gherkin steps of a feature",
                "Lists the scenarios and steps of a .feature file, and for each step without a definition, a definition "
                        + "to start from.",
                schema(Map.of("path", string("the .feature file, relative to the workspace")), List.of("path")), readOnly(),
                (a, c) -> gherkinSteps(a));
    }

    private void add(String name, String title, String description, Map<String, Object> schema,
            Map<String, Object> annotations, Handler handler) {
        tools.put(name, new Tool(name, title, description, schema, annotations, handler));
    }

    boolean has(String name) {
        return tools.containsKey(name);
    }

    /** The tools, as {@code tools/list} describes them. */
    List<Object> list() {
        List<Object> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("title", t.title());
            m.put("description", t.description());
            m.put("inputSchema", t.inputSchema());
            m.put("annotations", t.annotations());
            out.add(m);
        }
        return out;
    }

    /** Runs a tool; problems with its arguments or files are results with {@code isError}. */
    Map<String, Object> call(String name, Map<String, Object> args, Call call) {
        try {
            return tools.get(name).handler().handle(args, call);
        } catch (ToolException e) {
            return failure(e.getMessage());
        } catch (IOException e) {
            return failure("I/O error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ tools

    private Map<String, Object> listStepTypes() throws ToolException {
        Ivy ivy = newRun(discard());
        List<Object> types = new ArrayList<>();
        for (ConnectorInfo info : ivy.connectorInfos()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", info.type());
            List<Object> properties = new ArrayList<>();
            for (PropertyInfo p : info.properties()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.name());
                pm.put("type", simpleType(p.type()));
                pm.put("required", p.required());
                if (p.secret()) {
                    pm.put("secret", true);
                }
                if (!p.help().isEmpty()) {
                    pm.put("help", p.help());
                }
                properties.add(pm);
            }
            t.put("properties", properties);
            t.put("resultFields", info.resultFields());
            if (info.defaultAssertions() != null) {
                t.put("defaultAssertions", info.defaultAssertions());
            }
            t.put("example", example(info));
            types.add(t);
        }
        return success(Map.of("stepTypes", types,
                "note", "a step with 'script' or 'command' and no type is an exec step"));
    }

    private static Map<String, Object> describeSyntax() throws ToolException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("suite", SUITE_SYNTAX);
        m.put("builtins", BUILTINS);
        m.put("expressions", doc("expressions.md"));
        m.put("secrets", doc("secrets.md"));
        return success(m);
    }

    private Map<String, Object> validateSuite(Map<String, Object> args) throws ToolException {
        Path path = existingFile(Cast.toString(args.get("path")));
        return validation(path, path);
    }

    /** Parses a suite; {@code shown} is the path errors name, when {@code file} is a temporary copy. */
    private Map<String, Object> validation(Path file, Path shown) throws ToolException {
        Ivy ivy = newRun(discard());
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            ivy.parse(List.of(file.toString()));
        } catch (IvyException | RuntimeException e) {
            m.put("valid", false);
            m.put("errors", e.getMessage() == null ? e.toString()
                    : e.getMessage().replace(file.toString(), relative(shown)));
            m.put("warnings", ivy.warnings().stream().map(w -> w.replace(file.toString(), relative(shown))).toList());
            return failure(m);
        }
        m.put("valid", true);
        m.put("path", relative(shown));
        m.put("testcases", ivy.tests().testSuites.stream().flatMap(ts -> ts.testCases.stream())
                .map(tc -> tc.originalName).toList());
        m.put("warnings", ivy.warnings().stream().map(w -> w.replace(file.toString(), relative(shown))).toList());
        return success(m);
    }

    private Map<String, Object> writeSuite(Map<String, Object> args) throws ToolException, IOException {
        Path target = inWorkspace(Cast.toString(args.get("path")));
        String name = target.getFileName().toString();
        if (!(name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".feature"))) {
            throw new ToolException("a suite is a .yml, .yaml or .feature file, not \"" + name + "\"");
        }
        if (Files.isDirectory(target)) {
            throw new ToolException("\"" + relative(target) + "\" is a directory");
        }
        Object content = args.get("content");
        if (!(content instanceof String text)) {
            throw new ToolException("content is required");
        }
        Files.createDirectories(target.getParent());
        // checked next to its final place, so that relative paths resolve, under a name nothing else reads
        Path temp = target.resolveSibling(".ivy-" + UUID.randomUUID() + "-" + name);
        Files.writeString(temp, text);
        try {
            if (!name.endsWith(".steps.yml") && !name.endsWith(".steps.yaml")) {
                Map<String, Object> checked = validation(temp, target);
                if (Boolean.TRUE.equals(checked.get("isError"))) {
                    return checked;
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return success(Map.of("written", relative(target)));
    }

    private Map<String, Object> runSuite(Map<String, Object> args, Call call) throws ToolException, IOException {
        Path path = existingFile(Cast.toString(args.get("path")));
        Path out = Files.createTempDirectory("ivy-mcp-run-");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        Ivy ivy = newRun(new PrintStream(console, true, StandardCharsets.UTF_8));
        ivy.colors(false).outputDir(out.toString());
        call.attach(ivy);
        try {
            if (args.get("vars") instanceof Map<?, ?> vars) {
                ivy.addVariables(Cast.toStringMap(vars));
            }
            if (args.get("tags") instanceof String tags && !tags.isBlank()) {
                ivy.tags(tags);
            }
            ivy.initLogger();
            ivy.parse(List.of(path.toString()));
            if (args.get("testcases") instanceof List<?> names && !names.isEmpty()) {
                select(ivy, names.stream().map(Cast::toString).toList());
            }
            long total = ivy.tests().testSuites.stream().flatMap(ts -> ts.testCases.stream())
                    .filter(tc -> !tc.hasSkipped()).count();
            AtomicLong done = new AtomicLong();
            if (call.progress != null) {
                ivy.onTestCaseEnd((ts, tc) -> call.progress.report(done.incrementAndGet(), total,
                        tc.originalName + " " + Status.name(tc.status)));
            }
            ivy.process();
        } catch (IvyException | IllegalArgumentException e) {
            return failure(ivy.secrets().hide(e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            ivy.close();
            delete(out);
        }
        Map<String, Object> report = report(ivy, console.toString(StandardCharsets.UTF_8));
        // secrets are hidden in the whole answer, whatever field they reached
        String hidden = ivy.secrets().hide(Json.write(report, Json.COMPACT));
        Map<String, Object> safe = Cast.toStringMap(Json.parse(hidden));
        return ivy.tests().status == Status.PASS ? success(safe) : failure(safe);
    }

    /** Skips the test cases not named; fails when a name matches none. */
    private static void select(Ivy ivy, List<String> names) throws ToolException {
        List<String> unknown = new ArrayList<>(names);
        for (TestSuite ts : ivy.tests().testSuites) {
            for (TestCase tc : ts.testCases) {
                if (names.contains(tc.originalName) || names.contains(tc.name)) {
                    unknown.remove(tc.originalName);
                    unknown.remove(tc.name);
                } else {
                    tc.addSkipped("not selected");
                }
            }
        }
        if (!unknown.isEmpty()) {
            throw new ToolException("no test case named " + unknown);
        }
    }

    private Map<String, Object> report(Ivy ivy, String console) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", Status.name(ivy.tests().status));
        List<Object> suites = new ArrayList<>();
        for (TestSuite ts : ivy.tests().testSuites) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("name", ts.name);
            sm.put("file", relative(Path.of(ts.filepath)));
            sm.put("status", Status.name(ts.status));
            List<Object> cases = new ArrayList<>();
            for (TestCase tc : ts.testCases) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", tc.originalName);
                cm.put("status", Status.name(tc.status));
                if (tc.skipped != null && !tc.skipped.isEmpty()) {
                    cm.put("skipped", tc.skipped.stream().map(s -> s.value()).toList());
                }
                List<Object> failed = new ArrayList<>();
                for (TestStepResult r : tc.testStepResults) {
                    if (r.hasErrors()) {
                        failed.add(failedStep(r));
                    }
                }
                if (!failed.isEmpty()) {
                    cm.put("failedSteps", failed);
                }
                cases.add(cm);
            }
            sm.put("testcases", cases);
            suites.add(sm);
        }
        m.put("suites", suites);
        if (!ivy.warnings().isEmpty()) {
            m.put("warnings", ivy.warnings());
        }
        if (ivy.isCancelled()) {
            m.put("cancelled", true);
        }
        m.put("console", tail(console, 4 * MAX_VALUE));
        return m;
    }

    private static Map<String, Object> failedStep(TestStepResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", r.number);
        m.put("name", r.name);
        m.put("errors", r.errorList().stream().map(Failure::toString).toList());
        if (r.computedInfo != null && !r.computedInfo.isEmpty()) {
            m.put("info", r.computedInfo);
        }
        Object result = r.computedVars.get("result");
        if (result != null) {
            // the actual result, to fix assertions from
            m.put("result", Json.write(result, Json.COMPACT.withMaxString(MAX_VALUE)));
        }
        if (!r.stdout.isBlank()) {
            m.put("stdout", tail(r.stdout, MAX_VALUE));
        }
        if (!r.stderr.isBlank()) {
            m.put("stderr", tail(r.stderr, MAX_VALUE));
        }
        return m;
    }

    private static Map<String, Object> evaluate(Map<String, Object> args) throws ToolException {
        if (!(args.get("expression") instanceof String source) || source.isBlank()) {
            throw new ToolException("expression is required");
        }
        Map<String, Object> vars = args.get("scope") instanceof Map<?, ?> s ? Cast.toStringMap(s) : Map.of();
        Scope scope = Scope.of(vars);
        try {
            Object value = Template.has(source) ? Template.compile(source).render(scope)
                    : Expression.compile(source).evaluate(scope);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", value == Scope.MISSING ? null : Values.unwrap(value));
            m.put("type", value == Scope.MISSING ? "undefined" : Values.typeOf(value));
            return success(m);
        } catch (ExprException e) {
            throw new ToolException(e.getMessage());
        }
    }

    private Map<String, Object> listStepDefinitions(Map<String, Object> args) throws ToolException, IOException {
        String given = Cast.toString(args.get("dir"));
        Path dir = inWorkspace(given.isBlank() ? "." : given);
        if (!Files.isDirectory(dir)) {
            throw new ToolException("\"" + relative(dir) + "\" is not a directory");
        }
        StepLibrary library;
        try {
            library = StepLibrary.load(List.of(dir, dir.resolve("steps"), dir.resolve("lib")));
        } catch (xyz.fokion.ivy.core.yaml.Yaml.YamlException e) {
            throw new ToolException(e.getMessage());
        }
        List<Object> definitions = new ArrayList<>();
        for (StepLibrary.Definition d : library.definitions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("expression", d.text());
            m.put("file", relative(Path.of(d.source())));
            if (d.step() != null) {
                m.put("step", d.step());
            }
            if (!d.assertions().isEmpty()) {
                m.put("assertions", d.assertions());
            }
            definitions.add(m);
        }
        return success(Map.of("definitions", definitions));
    }

    private Map<String, Object> gherkinSteps(Map<String, Object> args) throws ToolException {
        Path path = existingFile(Cast.toString(args.get("path")));
        if (!path.getFileName().toString().endsWith(".feature")) {
            throw new ToolException("gherkin_steps reads a .feature file");
        }
        Ivy ivy = newRun(discard());
        try {
            ivy.parse(List.of(path.toString()));
        } catch (IvyException | RuntimeException e) {
            throw new ToolException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        List<Object> scenarios = new ArrayList<>();
        long undefined = 0;
        for (TestSuite ts : ivy.tests().testSuites) {
            for (TestCase tc : ts.testCases) {
                if (tc.gherkin != null) {
                    scenarios.add(tc.gherkin.toJson());
                    undefined += tc.gherkin.problems().size();
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarios", scenarios);
        m.put("problems", undefined);
        return success(m);
    }

    // ------------------------------------------------------------ runs

    /** A run with the settings of the command line and the shared connectors. */
    private Ivy newRun(PrintStream console) throws ToolException {
        try {
            Ivy ivy = runs.create(console);
            connectors.forEach(ivy::addConnectors);
            return ivy;
        } catch (IvyException e) {
            throw new ToolException(e.getMessage());
        }
    }

    private static PrintStream discard() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    /** Connectors loaded once for all calls: closing a run does not close them. */
    private record Shared(ConnectorInfoManager delegate) implements ConnectorInfoManager {
        @Override
        public List<ConnectorInfo> connectorInfos() {
            return delegate.connectorInfos();
        }

        @Override
        public Optional<ConnectorFacade> find(String type) {
            return delegate.find(type);
        }

        @Override
        public void close() {
        }
    }

    // ------------------------------------------------------------ paths

    /** A path inside the workspace, after symbolic links are resolved; it may not exist yet. */
    private Path inWorkspace(String given) throws ToolException {
        if (given == null || given.isBlank()) {
            throw new ToolException("path is required");
        }
        Path p = workspace.resolve(given).normalize();
        try {
            Path root = workspace.toRealPath();
            Path existing = p;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (!p.startsWith(workspace) || existing == null || !existing.toRealPath().startsWith(root)) {
                throw new ToolException("\"" + given + "\" is outside the workspace " + workspace);
            }
        } catch (IOException e) {
            throw new ToolException("cannot resolve \"" + given + "\": " + e.getMessage());
        }
        return p;
    }

    private Path existingFile(String given) throws ToolException {
        Path p = inWorkspace(given);
        if (!Files.isRegularFile(p)) {
            throw new ToolException("no file \"" + given + "\" in the workspace " + workspace);
        }
        return p;
    }

    private String relative(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(workspace) ? workspace.relativize(abs).toString() : p.toString();
    }

    private static void delete(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException ignored) {
            // a temporary directory left behind is harmless
        }
    }

    // ------------------------------------------------------------ results and schemas

    static Map<String, Object> success(Map<String, Object> structured) {
        return result(structured, false);
    }

    static Map<String, Object> failure(Map<String, Object> structured) {
        return result(structured, true);
    }

    static Map<String, Object> failure(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", message)));
        m.put("isError", true);
        return m;
    }

    private static Map<String, Object> result(Map<String, Object> structured, boolean error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", Json.write(structured, Json.GO_INDENT))));
        m.put("structuredContent", structured);
        m.put("isError", error);
        return m;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", new java.util.TreeMap<>(properties));
        if (!required.isEmpty()) {
            m.put("required", required);
        }
        return m;
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> readOnly() {
        return Map.of("readOnlyHint", true, "openWorldHint", false);
    }

    private static Map<String, Object> annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        return Map.of("readOnlyHint", readOnly, "destructiveHint", destructive, "idempotentHint", idempotent,
                "openWorldHint", !idempotent);
    }

    /** {@code java.util.List<java.lang.String>} as {@code List<String>}. */
    static String simpleType(String javaType) {
        return javaType.replaceAll("\\b[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*\\.(?=[A-Z])", "");
    }

    /** An example step: the required properties, or the first ones when none is required. */
    static String example(ConnectorInfo info) {
        StringBuilder sb = new StringBuilder("- type: ").append(info.type()).append('\n');
        List<PropertyInfo> shown = info.properties().stream().filter(PropertyInfo::required).toList();
        if (shown.isEmpty()) {
            shown = info.properties().stream().limit(2).toList();
        }
        for (PropertyInfo p : shown) {
            sb.append("  ").append(p.name()).append(": ").append(placeholder(p)).append('\n');
        }
        if (!info.resultFields().isEmpty()) {
            String first = info.resultFields().keySet().iterator().next();
            sb.append("  assertions:\n  - result.").append(first).append(" != null\n");
        }
        return sb.toString();
    }

    private static String placeholder(PropertyInfo p) {
        String t = simpleType(p.type());
        if (t.startsWith("List") || t.endsWith("[]")) {
            return "[]";
        }
        if (t.startsWith("Map")) {
            return "{}";
        }
        return switch (t) {
            case "int", "long", "Integer", "Long" -> "0";
            case "double", "float", "Double", "Float" -> "0.0";
            case "boolean", "Boolean" -> "false";
            default -> "\"<" + p.name() + ">\"";
        };
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "..." + s.substring(s.length() - max);
    }

    private static String doc(String name) throws ToolException {
        try (InputStream in = Tools.class.getResourceAsStream("/xyz/fokion/ivy/cli/docs/" + name)) {
            if (in == null) {
                throw new ToolException("documentation " + name + " is missing from this build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("cannot read " + name + ": " + e.getMessage());
        }
    }

    static final String SUITE_SYNTAX = """
            name: my suite
            parallel: true                # false: never runs at the same time as another suite
            secrets: [token]              # variables hidden in logs and reports
            vars:                         # suite variables, may use ${...} of previous ones
              base: http://localhost:8080
            testcases:
            - name: create item
              if: env.CI == "true"        # optional: the test case runs when true
              vars: {user: ada}
              steps:
              - type: http                # a step type from list_step_types
                name: create              # optional
                method: POST
                url: ${base}/items        # ${...} templates in any value
                body: '{"user": "${user}"}'
                assertions:               # expressions; all must be true
                - result.status == 201
                - result.body.id != null
                - {must: result.body.user == "ada"}   # must: stops the test case when false
                set:                      # values for later steps and test cases
                  id: result.body.id
                info: "created ${result.body.id}"
                retry: 3                  # with delay (seconds) and retryIf (an expression)
                delay: 1
                timeout: 10               # seconds
              - script: echo ${id}        # a step with script or command is an exec step
                range: [1, 2, 3]          # repeats the step: index, key, value
                if: value > 1
                with: {label: "n${value}"}  # variables of this step only
            - name: read item
              steps:
              - type: http
                url: ${base}/items/${cases["create-item"].id}   # values set by an earlier test case
            """;

    static final String BUILTINS = """
            env.NAME                      environment variables
            cases.<test case>.<name>      values set by an earlier test case of the suite (name or slug)
            result                        the result of the step, in assertions, retryIf, info and set
            index, key, value             the current item of a ranged step
            input.<name>                  the inputs of a user executor (lib/*.yml)
            ivy.suite.name, .shortName, .file, .path, .workdir, .totalSteps
            ivy.case.name, .tags, .totalSteps
            ivy.step.number
            ivy.datetime, ivy.timestamp, ivy.executable, ivy.outputDir, ivy.libDir
            """;
}
