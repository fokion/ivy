package xyz.fokion.ivy.spi;

import java.util.List;

/**
 * Supplies the assertions applied to a step that declares none.
 */
public interface DefaultAssertionsProvider {

    /** Assertions: expressions such as {@code result.status == 200}, or {@code {must: expression}} maps. */
    List<Object> defaultAssertions();
}
