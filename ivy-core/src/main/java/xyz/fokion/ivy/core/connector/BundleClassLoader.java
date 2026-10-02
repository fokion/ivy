package xyz.fokion.ivy.core.connector;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import xyz.fokion.ivy.spi.Connector;

/**
 * Loads one connector bundle in isolation: it sees the JDK, the {@code xyz.fokion.ivy.spi}
 * classes shared with ivy and its own jars, nothing of ivy's classpath. Two bundles can
 * therefore embed different versions of the same driver.
 */
public final class BundleClassLoader extends URLClassLoader {

    private static final String SPI_PACKAGE = "xyz.fokion.ivy.spi.";
    private static final ClassLoader SPI_LOADER = Connector.class.getClassLoader();

    static {
        registerAsParallelCapable();
    }

    public BundleClassLoader(String name, List<URL> urls) {
        super(name, urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(SPI_PACKAGE)) {
            return SPI_LOADER.loadClass(name);
        }
        return super.loadClass(name, resolve);
    }

    @Override
    public URL getResource(String name) {
        if (isSpiResource(name)) {
            return SPI_LOADER.getResource(name);
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (isSpiResource(name)) {
            return Collections.emptyEnumeration();
        }
        return super.getResources(name);
    }

    private static boolean isSpiResource(String name) {
        return name.startsWith(SPI_PACKAGE.replace('.', '/'));
    }
}
