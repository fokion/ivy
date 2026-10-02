package xyz.fokion.ivy.spi;

/**
 * Version of the connector SPI. A bundle declares the version it was built against in its
 * {@code Ivy-Framework-Version} manifest attribute; ivy loads bundles with the same major
 * version.
 */
public final class Framework {

    public static final String VERSION = "1.0";

    private Framework() {
    }

    public static boolean isCompatible(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        return major(version).equals(major(VERSION));
    }

    private static String major(String v) {
        int dot = v.indexOf('.');
        return dot < 0 ? v.trim() : v.substring(0, dot).trim();
    }
}
