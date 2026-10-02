package xyz.fokion.ivy.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a bindable property of a {@link Configuration}, placed on its setter.
 * <p>
 * Properties are matched against step keys case-insensitively, by {@link #name()} when set and
 * by the bean property name otherwise.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigurationProperty {

    /** The step key bound to this property; defaults to the bean property name. */
    String name() default "";

    /** Whether binding fails when the key is missing. */
    boolean required() default false;

    /** Whether the value must be hidden from logs and reports. */
    boolean secret() default false;

    String help() default "";
}
