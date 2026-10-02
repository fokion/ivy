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
}
