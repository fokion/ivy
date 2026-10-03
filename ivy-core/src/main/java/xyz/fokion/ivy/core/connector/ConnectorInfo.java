package xyz.fokion.ivy.core.connector;

import java.util.List;
import java.util.Map;

/**
 * What ivy knows about a connector before running it.
 *
 * @param properties the bindable configuration properties
 * @param defaultAssertions assertions applied when a step declares none, or {@code null}
 * @param resultFields the fields of a result and their description, empty when unknown
 */
public record ConnectorInfo(ConnectorKey key, String displayName, List<PropertyInfo> properties,
        List<Object> defaultAssertions, Map<String, String> resultFields) {

    public ConnectorInfo {
        resultFields = resultFields == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(resultFields));
    }

    /** A connector whose result fields are unknown. */
    public ConnectorInfo(ConnectorKey key, String displayName, List<PropertyInfo> properties,
            List<Object> defaultAssertions) {
        this(key, displayName, properties, defaultAssertions, Map.of());
    }

    public String type() {
        return key.type();
    }

    public record PropertyInfo(String name, String type, boolean required, boolean secret, String help) {
    }
}
