package xyz.fokion.ivy.core.connector;

/** Identifies a connector: the bundle it comes from and the step type it handles. */
public record ConnectorKey(String bundleName, String bundleVersion, String type) {

    @Override
    public String toString() {
        return bundleName + ":" + bundleVersion + ":" + type;
    }
}
