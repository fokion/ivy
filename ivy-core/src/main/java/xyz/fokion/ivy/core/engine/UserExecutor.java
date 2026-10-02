package xyz.fokion.ivy.core.engine;

/**
 * An executor written in YAML: a file in a lib directory with {@code executor}, {@code input},
 * {@code steps} and {@code output}.
 *
 * @param rawInputs the text of the {@code input} section
 * @param raw the whole file
 */
record UserExecutor(String executor, String rawInputs, String raw, String filename) {
}
