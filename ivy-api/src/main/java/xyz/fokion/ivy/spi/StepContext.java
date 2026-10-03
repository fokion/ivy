package xyz.fokion.ivy.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.spi.util.LazyJson;

/**
 * What a connector may know about the step being run.
 */
public interface StepContext {

    /**
     * The variables visible to the step, as nested values: maps, lists, strings, numbers and
     * booleans. The map is a read-only view; nothing is copied.
     */
    Map<String, Object> vars();

    /**
     * The value at a path such as {@code ivy.suite.workdir} or {@code servers[0].url}, or
     * {@code null} when there is none.
     */
    default Object value(String path) {
        Object current = vars();
        int i = 0;
        while (current != null && i < path.length()) {
            int end = i;
            while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                end++;
            }
            if (end > i) {
                current = member(current, path.substring(i, end));
            }
            i = end;
            if (i < path.length() && path.charAt(i) == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    return null;
                }
                current = member(current, path.substring(i + 1, close).replace("\"", ""));
                i = close + 1;
            }
            if (i < path.length() && path.charAt(i) == '.') {
                i++;
            }
        }
        return current;
    }

    private static Object member(Object value, String name) {
        Object v = value instanceof LazyJson j ? j.value() : value;
        if (v instanceof Struct s) {
            return s.get(name);
        }
        if (v instanceof Map<?, ?> m) {
            return m.get(name);
        }
        if (v instanceof List<?> l) {
            try {
                int index = Integer.parseInt(name);
                return index >= 0 && index < l.size() ? l.get(index) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The value at a path as text, or {@code null} when there is none. */
    default String var(String path) {
        Object v = value(path);
        return v == null ? null : String.valueOf(v);
    }

    /** The test suite directory; relative paths in steps resolve against it. */
    default Path workdir() {
        String dir = var("ivy.suite.workdir");
        return dir == null || dir.isEmpty() ? Path.of("").toAbsolutePath() : Path.of(dir);
    }

    /** Replaces the {@code ${...}} expressions of a text, such as the content of a file to send. */
    default String interpolate(String text) {
        throw new UnsupportedOperationException("templates are not available to this connector");
    }

    /** The interpolated step, as parsed from YAML. */
    Map<String, Object> step();

    /** Writes to the ivy log file. */
    void log(Level level, String message);

    enum Level {
        DEBUG, INFO, WARN, ERROR
    }
}
