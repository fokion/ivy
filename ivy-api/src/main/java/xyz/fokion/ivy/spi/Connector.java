package xyz.fokion.ivy.spi;

/**
 * Runs test steps of one type.
 * <p>
 * A new instance is created for every test case that uses the connector, {@link #open} is called
 * before its first step and {@link #close} once the test case ends, so instances may keep
 * sessions (connections, browsers) in fields.
 *
 * @param <C> the configuration bound from each step
 */
public interface Connector<C extends Configuration> {

    /**
     * Runs one step and returns its result: a {@link Struct}, a {@code Map}, a {@code List}, a
     * scalar or {@code null}. The result is flattened into {@code result.*} variables.
     */
    Object run(C configuration, StepContext context) throws Exception;

    /** Called before the first step of a test case. */
    default void open(StepContext context) throws Exception {
    }

    /** Called when the test case ends. */
    default void close() throws Exception {
    }

    /**
     * The fields of the result, each with a one-line description, such as {@code status} for an
     * HTTP response; validation warns about assertions reading other fields. Empty when unknown.
     */
    default java.util.Map<String, String> resultFields() {
        return java.util.Map.of();
    }

    /** Result fields in order, from names and descriptions: {@code fields("status", "the HTTP status", ...)}. */
    static java.util.Map<String, String> fields(String... namesAndDescriptions) {
        if (namesAndDescriptions.length % 2 != 0) {
            throw new IllegalArgumentException("fields takes names and descriptions in pairs");
        }
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < namesAndDescriptions.length; i += 2) {
            m.put(namesAndDescriptions[i], namesAndDescriptions[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
