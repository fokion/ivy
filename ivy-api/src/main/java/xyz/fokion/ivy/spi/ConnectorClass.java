package xyz.fokion.ivy.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Connector} implementation and names the step {@code type} it handles.
 * <p>
 * The configuration class is instantiated and bound from the step's keys before every run.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConnectorClass {

    /** The value of the step {@code type} attribute this connector handles. */
    String type();

    /** The configuration bean bound from the step keys. */
    Class<? extends Configuration> configurationClass();

    /** Human readable name, defaults to {@link #type()}. */
    String displayName() default "";
}
