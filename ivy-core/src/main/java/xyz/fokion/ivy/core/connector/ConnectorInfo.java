package xyz.fokion.ivy.core.connector;

import java.util.List;

/**
 * What ivy knows about a connector before running it.
 *
 * @param properties the bindable configuration properties
 * @param defaultAssertions assertions applied when a step declares none, or {@code null}
 * @param zeroValueResult an empty result used to discover produced variables, or {@code null}
 */
public record ConnectorInfo(ConnectorKey key, String displayName, List<PropertyInfo> properties,
        List<Object> defaultAssertions, Object zeroValueResult) {

    public String type() {
        return key.type();
    }

    public record PropertyInfo(String name, String type, boolean required, boolean secret, String help) {
    }
}
