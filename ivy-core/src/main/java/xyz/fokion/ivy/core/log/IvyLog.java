package xyz.fokion.ivy.core.log;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import xyz.fokion.ivy.spi.util.GoFormat;

/**
 * The run log ({@code ivy.log}), formatted like venom's nested logrus output:
 * {@code Oct  2 21:46:33.794 [INFO] [suite] [testcase] [executor] message}.
 * <p>
 * Known secret values are replaced by {@code __hidden__} in every message.
 */
public final class IvyLog {

    public enum Level {
        DEBUG("DEBU"), INFO("INFO"), WARN("WARN"), ERROR("ERRO");

        private final String label;

        Level(String label) {
            this.label = label;
        }
    }

    /** Logging fields of the current position in the run. */
    public record Fields(String testsuite, String testcase, String step, String executor, List<String> secrets) {
        public static final Fields EMPTY = new Fields(null, null, null, null, List.of());

        public Fields withTestsuite(String name) {
            return new Fields(name, testcase, step, executor, secrets);
        }

        public Fields withTestcase(String name) {
            return new Fields(testsuite, name, step, executor, secrets);
        }

        public Fields withExecutor(String name) {
            return new Fields(testsuite, testcase, step, name, secrets);
        }

        public Fields withSecrets(List<String> s) {
            return new Fields(testsuite, testcase, step, executor, s);
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss.SSS", Locale.ENGLISH);

    private final PrintWriter out;
    private final Level threshold;

    public IvyLog(Writer out, Level threshold) {
        this.out = out == null ? null : new PrintWriter(out, true);
        this.threshold = threshold;
    }

    /** A log that discards everything. */
    public static IvyLog discard() {
        return new IvyLog(null, Level.ERROR);
    }

    public boolean enabled(Level level) {
        return out != null && level.ordinal() >= threshold.ordinal();
    }

    public void log(Level level, Fields fields, String message) {
        if (!enabled(level)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(LocalDateTime.now().format(STAMP)).append(" [").append(level.label).append("] ");
        for (String f : new String[] {fields.testsuite(), fields.testcase(), fields.step(), fields.executor()}) {
            if (f != null) {
                sb.append('[').append(f).append("] ");
            }
        }
        sb.append(replaceSecrets(message, fields.secrets()));
        synchronized (this) {
            out.println(sb);
        }
    }

    public void debug(Fields f, String message) {
        log(Level.DEBUG, f, message);
    }

    public void info(Fields f, String message) {
        log(Level.INFO, f, message);
    }

    public void warn(Fields f, String message) {
        log(Level.WARN, f, message);
    }

    public void error(Fields f, String message) {
        log(Level.ERROR, f, message);
    }

    /** venom's {@code HideSensitive}. */
    public static String hideSensitive(Object arg, List<String> secrets) {
        String s = arg instanceof String str ? str : GoFormat.sprint(arg);
        if (secrets == null || secrets.isEmpty()) {
            return s;
        }
        return replaceSecrets(s, secrets);
    }

    /** Replaces the longest secrets first. */
    public static String replaceSecrets(String s, List<String> secrets) {
        if (secrets == null || secrets.isEmpty() || s == null) {
            return s;
        }
        List<String> sorted = new ArrayList<>(secrets);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        for (String secret : sorted) {
            if (!secret.isEmpty()) {
                s = s.replace(secret, "__hidden__");
            }
        }
        return s;
    }

    /**
     * Adds the base64 encodings of the secret variables and, when {@code basic_auth_password}
     * is secret, of the basic auth token.
     */
    public static void appendDerivedSecrets(List<String> secrets, Set<String> seen, Map<String, Object> vars,
            List<String> secretKeys) {
        for (String key : secretKeys) {
            String val = GoFormat.sprint(vars.get(key));
            if (!val.isEmpty() && !val.equals("<nil>")) {
                add(secrets, seen, Base64.getEncoder().encodeToString(val.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        if (secretKeys.contains("basic_auth_password")) {
            String user = GoFormat.sprint(vars.get("basic_auth_user"));
            String pass = GoFormat.sprint(vars.get("basic_auth_password"));
            if (!pass.isEmpty() && !pass.equals("<nil>")) {
                add(secrets, seen, Base64.getEncoder().encodeToString((user + ":" + pass)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }

    private static void add(List<String> secrets, Set<String> seen, String value) {
        if (!value.isEmpty() && seen.add(value)) {
            secrets.add(value);
        }
    }

    /** Hides secret variables of a map in place: secret keys entirely, other values by content. */
    public static void redactVars(Map<String, Object> vars, List<String> secretKeys, List<String> secrets) {
        if (vars == null || vars.isEmpty() || secretKeys.isEmpty()) {
            return;
        }
        vars.replaceAll((k, v) -> {
            if (k.startsWith("venom.")) {
                return v;
            }
            if (secretKeys.contains(k)) {
                return "__hidden__";
            }
            return hideSensitive(v, secrets);
        });
    }

    /** {@link #redactVars} for string maps. */
    public static void redactStringVars(Map<String, String> vars, List<String> secretKeys, List<String> secrets) {
        if (vars == null || vars.isEmpty() || secretKeys.isEmpty()) {
            return;
        }
        vars.replaceAll((k, v) -> {
            if (k.startsWith("venom.")) {
                return v;
            }
            if (secretKeys.contains(k)) {
                return "__hidden__";
            }
            return hideSensitive(v, secrets);
        });
    }
}
