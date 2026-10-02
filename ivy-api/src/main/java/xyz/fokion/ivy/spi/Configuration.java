package xyz.fokion.ivy.spi;

/**
 * The typed view of a step, bound from its keys by the framework.
 * <p>
 * Implementations are plain beans: a public no-arg constructor and setters annotated with
 * {@link ConfigurationProperty}.
 */
public interface Configuration {

    /**
     * Checks the bound values, throwing {@link ConnectorException} when they are unusable.
     */
    default void validate() {
    }
}
