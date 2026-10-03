package xyz.fokion.ivy.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.connector.LocalConnectorInfoManager;
import xyz.fokion.ivy.core.connector.remote.RemoteConnectorInfoManager;
import xyz.fokion.ivy.core.engine.Ivy;
import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.engine.Outputs;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.core.yaml.Yaml.YamlException;

/**
 * The command line: {@code run}, {@code validate}, {@code mcp} and {@code version}.
 * <p>
 * Settings come from environment variables, then the {@code .ivyrc} file,
 * looked up in {@code cwd} then {@code home}, then flags, each overriding the previous. Paths are
 * used as given, relative to the process working directory.
 */
public final class Cli {

    static final String USAGE = """
            Usage:
              ivy run [paths...] [flags]
              ivy validate [paths...] [flags]   check suites without running them
              ivy mcp [flags]                   serve tools to write and run suites (MCP over stdio)
              ivy browser install [browsers...] install browsers for the browser connector (needs --bundles-dir)
              ivy version

            Flags of run:
                  --format string           xml (JUnit), json, yaml, tap or cucumber (default "xml")
                  --stop-on-failure         stop running a test suite on its first failing test case
                  --parallel n              run up to n test suites at the same time (default 1)
                  --html-report             also write an HTML report
              -v, --verbose                 -v: INFO level in ivy.log, -vv: DEBUG level and step dumps
                  --var name=value          a variable, repeatable
                  --var-from-file file      a YAML file of variables, repeatable
                  --secret name=value       a variable whose value is hidden in logs and reports, repeatable
                                            (prefer IVY_SECRET_<name> or a file: the command line is visible)
                  --secret-from-file file   a YAML file of secret variables, repeatable
                  --output-dir string       directory of the reports and of ivy.log
                  --lib-dir string          directories of user executors, separated by ':'
                  --bundles-dir string      directory of connector bundles (JVM only)
                  --connector-server value  a connector server as key@host:port, repeatable
                  --tags expression         run the scenarios matching a tag expression, e.g. "@smoke and not @slow"
                  --report-max-value n      cut strings longer than n characters in reports, 0 to keep them whole
                                            (default 65536)

            Flags of mcp (and the connector, variable and secret flags of run):
                  --workspace dir           the only directory whose suites are read, written and run
                                            (default: the current directory)
                  --no-run                  validate and write suites, never run them

            Examples:
              ivy run                                     run the suites of the current directory
              ivy run tests/*.yml --format=json --output-dir=out
              ivy run suite.yml --var="foo=bar" --var-from-file vars.yaml
              ivy run features/ --tags "@smoke" --format=cucumber --output-dir=out
            """;

    private final Map<String, String> env;
    private final PrintStream out;
    private final PrintStream err;
    private final Path cwd;
    private final Path home;

    /** The run settings, merged from all sources. */
    static final class Settings {
        String format = "xml";
        boolean stopOnFailure;
        boolean htmlReport;
        String outputDir = "";
        String libDir = "";
        int verbose;
        String bundlesDir = "";
        String tags = "";
        int reportMaxValue = 64 * 1024;
        int parallel = 1;
        final List<String> variables = new ArrayList<>();
        final List<String> varFiles = new ArrayList<>();
        final List<String> secrets = new ArrayList<>();
        final List<String> secretFiles = new ArrayList<>();
        final List<String> connectorServers = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
    }

    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    public Cli(Map<String, String> env, PrintStream out, PrintStream err, Path cwd, Path home) {
        this.env = env;
        this.out = out;
        this.err = err;
        this.cwd = cwd;
        this.home = home;
    }

    /** Runs a command line and returns the exit code: 0 when all suites passed, 2 otherwise. */
    public int run(String... args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        return switch (args[0]) {
            case "version" -> {
                out.println("Version ivy: " + Ivy.VERSION);
                yield 0;
            }
            case "run" -> runCommand(List.of(args).subList(1, args.length));
            case "validate" -> validateCommand(List.of(args).subList(1, args.length));
            case "mcp" -> mcpCommand(List.of(args).subList(1, args.length));
            case "browser" -> browserCommand(List.of(args).subList(1, args.length));
            default -> {
                err.println("unknown command \"" + args[0] + "\"");
                err.print(USAGE);
                yield 2;
            }
        };
    }

    /** The settings of a command: environment, then {@code .ivyrc}, then flags. */
    Settings settings(List<String> args) throws UsageException {
        Settings s = new Settings();
        fromEnv(s);
        fromConfigFile(s);
        fromArgs(s, args);
        if (s.parallel < 1) {
            throw new UsageException("invalid value for parallel: " + s.parallel + ", must be at least 1");
        }
        if (s.paths.isEmpty()) {
            s.paths.add(".");
        }
        return s;
    }

    /**
     * A run configured from the settings: flags, variables and secrets, writing its console to
     * {@code console}; connectors are added by the caller (see {@link #connectors}).
     */
    Ivy newIvy(Settings s, PrintStream console, boolean colors) throws IvyException {
        Ivy ivy = new Ivy(console)
                .outputDir(s.outputDir)
                .libDir(s.libDir)
                .outputFormat(s.format)
                .stopOnFailure(s.stopOnFailure)
                .htmlReport(s.htmlReport)
                .verbose(s.verbose)
                .reportMaxValue(s.reportMaxValue)
                .parallel(s.parallel)
                .colors(colors);
        try {
            ivy.tags(s.tags);
        } catch (IllegalArgumentException e) {
            throw new IvyException(e.getMessage(), e);
        }
        ivy.addVariables(readInitialVariables(s));
        ivy.addSecrets(readSecrets(s));
        return ivy;
    }

    /** The plugin connectors of the settings: a bundles directory and connector servers. */
    static List<ConnectorInfoManager> connectors(Settings s) throws IOException {
        List<ConnectorInfoManager> managers = new ArrayList<>();
        try {
            if (!s.bundlesDir.isEmpty()) {
                managers.add(LocalConnectorInfoManager.fromBundles(Path.of(s.bundlesDir)));
            }
            for (String server : s.connectorServers) {
                managers.add(RemoteConnectorInfoManager.connect(server));
            }
        } catch (IOException | RuntimeException e) {
            for (ConnectorInfoManager m : managers) {
                try {
                    m.close();
                } catch (Exception ignored) {
                    // closing is best effort
                }
            }
            throw e;
        }
        return managers;
    }

    private int runCommand(List<String> args) {
        Settings s;
        try {
            s = settings(args);
        } catch (UsageException e) {
            err.println(e.getMessage());
            return 2;
        }
        boolean colors = colors();
        Ivy ivy;
        try {
            ivy = newIvy(s, out, colors);
        } catch (IvyException e) {
            err.println(e.getMessage());
            return 2;
        }
        try {
            ivy.initLogger();
            for (ConnectorInfoManager m : connectors(s)) {
                ivy.addConnectors(m);
            }
            ivy.parse(s.paths);
            ivy.process();
            Outputs.write(ivy);
        } catch (IvyException | IOException | RuntimeException e) {
            err.println(ivy.secrets().hide(e.getMessage() == null ? e.toString() : e.getMessage()));
            return 2;
        } finally {
            ivy.close();
        }
        Status status = ivy.tests().status;
        if (status == Status.PASS) {
            out.println("final status: " + (colors ? "\u001b[32m" + status + "\u001b[0m" : status));
            return 0;
        }
        out.println("final status: " + (colors ? "\u001b[31m" + status + "\u001b[0m" : status));
        return 2;
    }

    /** Parses and validates suites without running them: 0 when valid, 2 otherwise. */
    private int validateCommand(List<String> args) {
        Settings s;
        Ivy ivy;
        try {
            s = settings(args);
            ivy = newIvy(s, out, colors());
        } catch (UsageException | IvyException e) {
            err.println(e.getMessage());
            return 2;
        }
        try {
            for (ConnectorInfoManager m : connectors(s)) {
                ivy.addConnectors(m);
            }
            ivy.parse(s.paths);
        } catch (IvyException | IOException | RuntimeException e) {
            err.println(ivy.secrets().hide(e.getMessage() == null ? e.toString() : e.getMessage()));
            return 2;
        } finally {
            ivy.close();
        }
        ivy.warnings().forEach(w -> err.println("warning: " + ivy.secrets().hide(w)));
        int cases = ivy.tests().testSuites.stream().mapToInt(ts -> ts.testCases.size()).sum();
        out.println("valid: " + ivy.tests().testSuites.size() + " suite(s), " + cases + " test case(s)");
        return 0;
    }

    /**
     * Serves MCP tools on stdin and stdout. Stdout carries the protocol only: anything else
     * printing to {@code System.out} (connectors, drivers) goes to stderr.
     */
    private int mcpCommand(List<String> args) {
        Path workspace = cwd;
        boolean noRun = false;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--no-run")) {
                noRun = true;
            } else if (a.equals("--workspace") && i + 1 < args.size()) {
                workspace = cwd.resolve(args.get(++i));
            } else if (a.startsWith("--workspace=")) {
                workspace = cwd.resolve(a.substring("--workspace=".length()));
            } else {
                rest.add(a);
            }
        }
        Settings s;
        try {
            s = settings(rest);
        } catch (UsageException e) {
            err.println(e.getMessage());
            return 2;
        }
        PrintStream protocol = out;
        System.setOut(err);
        List<ConnectorInfoManager> connectors;
        try {
            connectors = connectors(s);
        } catch (IOException | RuntimeException e) {
            err.println(e.getMessage() == null ? e.toString() : e.getMessage());
            return 2;
        }
        try {
            new xyz.fokion.ivy.cli.mcp.McpServer(console -> newIvy(s, console, false), workspace, noRun, connectors, err)
                    .serve(System.in, protocol);
            return 0;
        } catch (IOException e) {
            err.println("mcp: " + e.getMessage());
            return 2;
        } finally {
            for (ConnectorInfoManager m : connectors) {
                try {
                    m.close();
                } catch (Exception ignored) {
                    // closing is best effort
                }
            }
        }
    }

    /**
     * {@code browser install [chromium|firefox|webkit|--dry-run...]}: runs the Playwright
     * installer of the browser bundle, so that no separate Node.js is needed.
     */
    private int browserCommand(List<String> args) {
        if (args.isEmpty() || !args.getFirst().equals("install")) {
            err.println("usage: ivy browser install [chromium|firefox|webkit] [--bundles-dir dir]");
            return 2;
        }
        List<String> installArgs = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--bundles-dir") && i + 1 < args.size()) {
                flags.add(a);
                flags.add(args.get(++i));
            } else if (a.startsWith("--bundles-dir=")) {
                flags.add(a);
            } else {
                installArgs.add(a);
            }
        }
        Settings s;
        try {
            s = settings(flags);
        } catch (UsageException e) {
            err.println(e.getMessage());
            return 2;
        }
        if (s.bundlesDir.isEmpty()) {
            err.println("the browser connector is a bundle: give its directory with --bundles-dir (or IVY_BUNDLES_DIR); "
                    + "with the native binary, run `npx playwright install` instead");
            return 2;
        }
        try (LocalConnectorInfoManager bundles = LocalConnectorInfoManager.fromBundles(Path.of(s.bundlesDir))) {
            var facade = bundles.find("browser").orElse(null);
            if (!(facade instanceof xyz.fokion.ivy.core.connector.LocalConnectorFacade local)) {
                err.println("no browser connector in " + s.bundlesDir);
                return 2;
            }
            Class<?> install = local.classLoader().loadClass("xyz.fokion.ivy.connectors.browser.BrowserInstall");
            return (int) install.getMethod("install", List.class).invoke(null, installArgs);
        } catch (java.lang.reflect.InvocationTargetException e) {
            err.println("install failed: " + e.getCause());
            return 2;
        } catch (Exception e) {
            err.println("install failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 2;
        }
    }

    /** Colors unless {@code IS_TTY} is set to something else than true or 1. */
    private boolean colors() {
        String isTty = env.getOrDefault("IS_TTY", "");
        return isTty.isEmpty() || isTty.equalsIgnoreCase("true") || isTty.equals("1");
    }

    private String getenv(String suffix) {
        String v = env.get("IVY_" + suffix);
        return v == null ? "" : v;
    }

    void fromEnv(Settings s) throws UsageException {
        if (!getenv("VAR").isEmpty()) {
            s.variables.clear();
            s.variables.addAll(List.of(getenv("VAR").split(" ")));
        }
        if (!getenv("VAR_FROM_FILE").isEmpty()) {
            s.varFiles.clear();
            s.varFiles.addAll(List.of(getenv("VAR_FROM_FILE").split(" ")));
        }
        if (!getenv("FORMAT").isEmpty()) {
            s.format = getenv("FORMAT");
        }
        if (!getenv("STOP_ON_FAILURE").isEmpty()) {
            s.stopOnFailure = parseBool(getenv("STOP_ON_FAILURE"), "invalid value for IVY_STOP_ON_FAILURE");
        }
        if (!getenv("HTML_REPORT").isEmpty()) {
            s.htmlReport = parseBool(getenv("HTML_REPORT"), "invalid value for IVY_HTML_REPORT");
        }
        if (!getenv("LIB_DIR").isEmpty()) {
            s.libDir = getenv("LIB_DIR");
        }
        if (!getenv("OUTPUT_DIR").isEmpty()) {
            s.outputDir = getenv("OUTPUT_DIR");
        }
        if (!getenv("TAGS").isEmpty()) {
            s.tags = getenv("TAGS");
        }
        if (!getenv("BUNDLES_DIR").isEmpty()) {
            s.bundlesDir = getenv("BUNDLES_DIR");
        }
        if (!getenv("PARALLEL").isEmpty()) {
            try {
                s.parallel = Integer.parseInt(getenv("PARALLEL"));
            } catch (NumberFormatException e) {
                throw new UsageException("invalid value for IVY_PARALLEL, must be a number of at least 1");
            }
        }
        if (!getenv("VERBOSE").isEmpty()) {
            try {
                s.verbose = Integer.parseInt(getenv("VERBOSE"));
            } catch (NumberFormatException e) {
                throw new UsageException("invalid value for IVY_VERBOSE, must be 1, 2 or 3");
            }
        }
        env.forEach((k, v) -> {
            if (k.startsWith("IVY_VAR_")) {
                mergeVariable(k.substring("IVY_VAR_".length()) + "=" + v, s.variables);
            } else if (k.startsWith("IVY_SECRET_")) {
                mergeVariable(k.substring("IVY_SECRET_".length()) + "=" + v, s.secrets);
            }
        });
    }

    private static boolean parseBool(String v, String message) throws UsageException {
        try {
            return Cast.parseGoBool(v);
        } catch (Cast.CastException e) {
            throw new UsageException(message);
        }
    }

    void fromConfigFile(Settings s) throws UsageException {
        Path file = null;
        for (Path candidate : List.of(cwd.resolve(".ivyrc"), home.resolve(".ivyrc"))) {
            if (Files.isRegularFile(candidate)) {
                file = candidate;
                break;
            }
        }
        if (file == null) {
            return;
        }
        Map<String, Object> config;
        try {
            config = Yaml.loadMap(Files.readString(file));
        } catch (IOException | YamlException e) {
            throw new UsageException(file + ": " + e.getMessage());
        }
        if (config.containsKey("format")) {
            s.format = Cast.toString(config.get("format"));
        }
        if (config.containsKey("lib_dir")) {
            s.libDir = Cast.toString(config.get("lib_dir"));
        }
        if (config.containsKey("output_dir")) {
            s.outputDir = Cast.toString(config.get("output_dir"));
        }
        if (config.containsKey("stop_on_failure")) {
            s.stopOnFailure = Cast.toBool(config.get("stop_on_failure"));
        }
        if (config.containsKey("html_report")) {
            s.htmlReport = Cast.toBool(config.get("html_report"));
        }
        if (config.get("variables") instanceof List<?> vars) {
            vars.forEach(v -> mergeVariable(Cast.toString(v), s.variables));
        }
        if (config.get("variables_files") instanceof List<?> files) {
            files.forEach(f -> {
                String name = Cast.toString(f);
                if (!s.varFiles.contains(name)) {
                    s.varFiles.add(name);
                }
            });
        }
        if (config.containsKey("parallel")) {
            if (!(config.get("parallel") instanceof Number n)) {
                throw new UsageException(file + ": parallel must be a number of at least 1");
            }
            s.parallel = n.intValue();
        }
        if (config.containsKey("verbosity")) {
            s.verbose = (int) Cast.toLong(config.get("verbosity"));
        }
        if (config.containsKey("tags")) {
            s.tags = Cast.toString(config.get("tags"));
        }
        if (config.containsKey("bundles_dir")) {
            s.bundlesDir = Cast.toString(config.get("bundles_dir"));
        }
        if (config.get("connector_servers") instanceof List<?> servers) {
            for (Object server : servers) {
                Map<String, Object> m = Cast.toStringMap(server);
                String key = Cast.toString(m.get("key"));
                String host = Cast.toString(m.getOrDefault("host", "localhost"));
                long port = Cast.toLong(m.getOrDefault("port", 8759L));
                s.connectorServers.add((key.isEmpty() ? "" : key + "@") + host + ":" + port
                        + (Cast.toBool(m.get("tls")) ? "?tls" : ""));
            }
        }
    }

    /** Replaces every variable of the same name, keeping the position of the first one. */
    static void mergeVariable(String variable, List<String> existing) {
        String name = name(variable);
        int first = -1;
        for (int i = existing.size() - 1; i >= 0; i--) {
            if (name(existing.get(i)).equals(name)) {
                existing.remove(i);
                first = i;
            }
        }
        existing.add(first < 0 ? existing.size() : first, variable);
    }

    private static String name(String variable) {
        int idx = variable.indexOf('=');
        return idx < 0 ? variable : variable.substring(0, idx);
    }

    void fromArgs(Settings s, List<String> args) throws UsageException {
        boolean onlyPaths = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (onlyPaths || !a.startsWith("-") || a.equals("-")) {
                s.paths.add(a);
                continue;
            }
            if (a.equals("--")) {
                onlyPaths = true;
                continue;
            }
            if (a.matches("-v+")) {
                verboseFlags += a.length() - 1;
                s.verbose = verboseFlags;
                continue;
            }
            String name = a;
            String value = null;
            int eq = a.indexOf('=');
            if (eq > 0) {
                name = a.substring(0, eq);
                value = a.substring(eq + 1);
            }
            switch (name) {
                case "--verbose" -> {
                    verboseFlags = value == null ? verboseFlags + 1 : parseInt(value, name);
                    s.verbose = verboseFlags;
                }
                case "--stop-on-failure" -> s.stopOnFailure = value == null || parseBool(value, "invalid value for " + name);
                case "--html-report" -> s.htmlReport = value == null || parseBool(value, "invalid value for " + name);
                case "--report-max-value", "--parallel" -> {
                    if (value == null) {
                        if (i + 1 >= args.size()) {
                            throw new UsageException("flag needs an argument: " + name);
                        }
                        value = args.get(++i);
                    }
                    if (name.equals("--parallel")) {
                        s.parallel = parseInt(value, name);
                    } else {
                        s.reportMaxValue = parseInt(value, name);
                    }
                }
                case "--format", "--output-dir", "--lib-dir", "--var", "--var-from-file", "--secret",
                     "--secret-from-file", "--bundles-dir",
                     "--connector-server", "--tags" -> {
                    if (value == null) {
                        if (i + 1 >= args.size()) {
                            throw new UsageException("flag needs an argument: " + name);
                        }
                        value = args.get(++i);
                    }
                    switch (name) {
                        case "--format" -> s.format = value;
                        case "--output-dir" -> s.outputDir = value;
                        case "--lib-dir" -> s.libDir = value;
                        case "--bundles-dir" -> s.bundlesDir = value;
                        case "--tags" -> s.tags = value;
                        case "--var" -> mergeVariable(value, s.variables);
                        case "--secret" -> mergeVariable(value, s.secrets);
                        case "--secret-from-file" -> s.secretFiles.add(value);
                        case "--connector-server" -> s.connectorServers.add(value);
                        default -> {
                            for (String f : value.split(",")) {
                                if (!s.varFiles.contains(f)) {
                                    s.varFiles.add(f);
                                }
                            }
                        }
                    }
                }
                case "--help", "-h" -> throw new UsageException(USAGE);
                default -> throw new UsageException("unknown flag: " + name + "\n" + USAGE);
            }
        }
    }

    /** Counts -v flags given on the command line; they replace the verbosity of env and config. */
    private int verboseFlags;

    private static int parseInt(String v, String name) throws UsageException {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new UsageException("invalid value for " + name);
        }
    }

    /** Variables files first, then name=value variables, whose values are read as YAML ({@code [1, 2]} is a list). */
    Map<String, Object> readInitialVariables(Settings s) throws IvyException {
        return readVariables(s.varFiles, s.variables);
    }

    /** The secret variables: files, then name=value pairs. */
    Map<String, Object> readSecrets(Settings s) throws IvyException {
        return readVariables(s.secretFiles, s.secrets);
    }

    private static Map<String, Object> readVariables(List<String> files, List<String> pairs) throws IvyException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String f : files) {
            if (f.isEmpty()) {
                continue;
            }
            Path p = Path.of(f);
            try {
                result.putAll(Yaml.loadMap(Files.readString(p)));
            } catch (IOException e) {
                throw new IvyException("unable to open var-from-file " + f + ": " + e.getMessage(), e);
            } catch (YamlException e) {
                throw new IvyException("unable to unmarshal file: " + e.getMessage(), e);
            }
        }
        for (String arg : pairs) {
            if (arg.isEmpty()) {
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq < 0) {
                throw new IvyException("invalid variable declaration: " + arg);
            }
            String value = arg.substring(eq + 1);
            Object typed;
            try {
                typed = Yaml.load(value);
            } catch (YamlException e) {
                typed = null;
            }
            result.put(arg.substring(0, eq), typed);
        }
        return result;
    }
}
