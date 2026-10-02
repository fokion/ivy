package xyz.fokion.ivy.spi;

import java.nio.file.Path;
import java.util.Map;

/**
 * What a connector may know about the step being run.
 */
public interface StepContext {

    /** All variables visible to the step, flattened to strings. */
    Map<String, String> vars();

    /** One variable, or {@code null}. */
    default String var(String name) {
        return vars().get(name);
    }

    /** The test suite directory; relative paths in steps resolve against it. */
    default Path workdir() {
        String dir = var("venom.testsuite.workdir");
        return dir == null ? Path.of("").toAbsolutePath() : Path.of(dir);
    }

    /** The interpolated step, as parsed from YAML. */
    Map<String, Object> step();

    /** Writes to the ivy log file. */
    void log(Level level, String message);

    enum Level {
        DEBUG, INFO, WARN, ERROR
    }
}
