package xyz.fokion.ivy.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.connector.LocalConnectorInfoManager;
import xyz.fokion.ivy.core.connector.remote.RemoteConnectorInfoManager;
import xyz.fokion.ivy.core.engine.Ivy;
import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.engine.Outputs;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.template.Interpolator;
import xyz.fokion.ivy.core.template.Interpolator.InterpolationException;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.core.yaml.Yaml.YamlException;

/**
 * The command line, port of venom's {@code run} and {@code version} commands.
 * <p>
 * Settings come from environment variables, then the {@code .ivyrc} file (or {@code .venomrc}),
 * looked up in {@code cwd} then {@code home}, then flags, each overriding the previous. Paths are
 * used as given, relative to the process working directory. Both {@code IVY_*} and {@code VENOM_*} variables are
 * read, {@code IVY_*} winning.
 */
public final class Cli {

    static final String USAGE = """
            Usage:
              ivy run [paths...] [flags]
              ivy version

            Flags of run:
                  --format string           json, tap, xml or yaml (default "xml")
                  --stop-on-failure         stop running a test suite on its first failing test case
                  --html-report             also write an HTML report
              -v, --verbose                 -v: INFO level in ivy.log, -vv: DEBUG level and step dumps
                  --var name=value          a variable, repeatable
                  --var-from-file file      a YAML file of variables, repeatable
                  --output-dir string       directory of the reports and of ivy.log
                  --lib-dir string          directories of user executors, separated by ':'
                  --bundles-dir string      directory of connector bundles (JVM only)
                  --connector-server value  a connector server as key@host:port, repeatable

            Examples:
              ivy run                                     run the suites of the current directory
              ivy run tests/*.yml --format=json --output-dir=out
              ivy run suite.yml --var="foo=bar" --var-from-file vars.yaml
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
        final List<String> variables = new ArrayList<>();
        final List<String> varFiles = new ArrayList<>();
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
            default -> {
                err.println("unknown command \"" + args[0] + "\"");
                err.print(USAGE);
                yield 2;
            }
        };
    }

    private int runCommand(List<String> args) {
        Settings s = new Settings();
        try {
            fromEnv(s);
            fromConfigFile(s);
            fromArgs(s, args);
        } catch (UsageException e) {
            err.println(e.getMessage());
            return 2;
        }
        if (s.paths.isEmpty()) {
            s.paths.add(".");
        }

        boolean colors = colors();
        Ivy ivy = new Ivy(out)
                .outputDir(s.outputDir)
                .libDir(s.libDir)
                .outputFormat(s.format)
                .stopOnFailure(s.stopOnFailure)
                .htmlReport(s.htmlReport)
                .verbose(s.verbose)
                .colors(colors);
        try {
            ivy.initLogger();
            ivy.addVariables(readInitialVariables(s));
            if (!s.bundlesDir.isEmpty()) {
                ivy.addConnectors(LocalConnectorInfoManager.fromBundles(Path.of(s.bundlesDir)));
            }
            for (String server : s.connectorServers) {
                ivy.addConnectors(RemoteConnectorInfoManager.connect(server));
            }
            ivy.parse(s.paths);
            ivy.process();
            Outputs.write(ivy);
        } catch (IvyException | IOException | RuntimeException e) {
            err.println(e.getMessage() == null ? e.toString() : e.getMessage());
            ivy.close();
            return 2;
        }
        ivy.close();
        Status status = ivy.tests().status;
        if (status == Status.PASS) {
            out.println("final status: " + (colors ? "\u001b[32m" + status + "\u001b[0m" : status));
            return 0;
        }
        out.println("final status: " + (colors ? "\u001b[31m" + status + "\u001b[0m" : status));
        return 2;
    }

    /** As venom: colors unless {@code IS_TTY} is set to something else than true or 1. */
    private boolean colors() {
        String isTty = env.getOrDefault("IS_TTY", "");
        return isTty.isEmpty() || isTty.equalsIgnoreCase("true") || isTty.equals("1");
    }

    private String getenv(String suffix) {
        String v = env.get("IVY_" + suffix);
        if (v == null || v.isEmpty()) {
            v = env.get("VENOM_" + suffix);
        }
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
        if (!getenv("BUNDLES_DIR").isEmpty()) {
            s.bundlesDir = getenv("BUNDLES_DIR");
        }
        if (!getenv("VERBOSE").isEmpty()) {
            try {
                s.verbose = Integer.parseInt(getenv("VERBOSE"));
            } catch (NumberFormatException e) {
                throw new UsageException("invalid value for IVY_VERBOSE, must be 1, 2 or 3");
            }
        }
        // VENOM_VAR_x first so that IVY_VAR_x wins
        for (String prefix : List.of("VENOM_VAR_", "IVY_VAR_")) {
            env.forEach((k, v) -> {
                if (k.startsWith(prefix)) {
                    s.variables.add(k.substring(prefix.length()) + "=" + v);
                }
            });
        }
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
        for (Path candidate : List.of(cwd.resolve(".ivyrc"), cwd.resolve(".venomrc"),
                home.resolve(".ivyrc"), home.resolve(".venomrc"))) {
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
        if (config.containsKey("verbosity")) {
            s.verbose = (int) Cast.toLong(config.get("verbosity"));
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

    /** Replaces a variable of the same name, or appends it. */
    static void mergeVariable(String variable, List<String> existing) {
        int idx = variable.indexOf('=');
        String name = idx < 0 ? variable : variable.substring(0, idx);
        for (int i = 0; i < existing.size(); i++) {
            String e = existing.get(i);
            int eIdx = e.indexOf('=');
            if (eIdx > 1 && e.substring(0, eIdx).equals(name)) {
                existing.set(i, variable);
                return;
            }
        }
        existing.add(variable);
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
                case "--format", "--output-dir", "--lib-dir", "--var", "--var-from-file", "--bundles-dir",
                     "--connector-server" -> {
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
                        case "--var" -> mergeVariable(value, s.variables);
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

    /** venom's {@code readInitialVariables}: variables files first, then name=value variables parsed as YAML. */
    Map<String, Object> readInitialVariables(Settings s) throws IvyException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String f : s.varFiles) {
            if (f.isEmpty()) {
                continue;
            }
            Path p = Path.of(f);
            try {
                String content = Interpolator.interpolate(Files.readString(p), Map.of());
                result.putAll(Yaml.loadMap(content));
            } catch (IOException e) {
                throw new IvyException("unable to open var-from-file " + f + ": " + e.getMessage(), e);
            } catch (InterpolationException e) {
                throw new IvyException("unable to interpolate file: " + e.getMessage(), e);
            } catch (YamlException e) {
                throw new IvyException("unable to unmarshal file: " + e.getMessage(), e);
            }
        }
        for (String arg : s.variables) {
            if (arg.isEmpty()) {
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq < 0) {
                throw new IvyException("invalid variable declaration: " + arg);
            }
            String value;
            try {
                value = Interpolator.interpolate(arg.substring(eq + 1), Map.of());
            } catch (InterpolationException e) {
                throw new IvyException("unable to interpolate arg " + arg + ": " + e.getMessage(), e);
            }
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
