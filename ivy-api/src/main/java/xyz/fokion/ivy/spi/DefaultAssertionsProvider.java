package xyz.fokion.ivy.spi;

import java.util.List;

/**
 * Supplies the assertions applied to a step that declares none.
 */
public interface DefaultAssertionsProvider {

    /** Assertions as strings, or maps for logical operators ({@code and}, {@code or}...). */
    List<Object> defaultAssertions();
}
