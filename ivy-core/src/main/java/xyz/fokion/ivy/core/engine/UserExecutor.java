package xyz.fokion.ivy.core.engine;

import java.util.List;
import java.util.Map;

/**
 * An executor written in YAML: a file in a lib directory with {@code executor}, {@code input}
 * (the inputs and their defaults), {@code steps} and {@code output}.
 */
record UserExecutor(String executor, Map<String, Object> input, List<Map<String, Object>> steps, Object output,
        String filename) {
}
