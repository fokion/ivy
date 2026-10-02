package xyz.fokion.ivy.core.connector;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.Framework;

/**
 * Connectors running in this JVM: the built-in ones found on ivy's classpath, or the bundles
 * of a directory, each loaded by its own {@link BundleClassLoader}.
 */
public final class LocalConnectorInfoManager implements ConnectorInfoManager {

    public static final String BUNDLE_NAME = "Ivy-Bundle-Name";
    public static final String BUNDLE_VERSION = "Ivy-Bundle-Version";
    public static final String FRAMEWORK_VERSION = "Ivy-Framework-Version";

    private final Map<String, ConnectorFacade> facades = new LinkedHashMap<>();
    private final List<BundleClassLoader> loaders = new ArrayList<>();
    private final List<Path> tempDirs = new ArrayList<>();

    private LocalConnectorInfoManager() {
    }

    /** The connectors registered as services of the given class loader. */
    public static LocalConnectorInfoManager fromClassLoader(String bundleName, String bundleVersion, ClassLoader loader) {
        LocalConnectorInfoManager m = new LocalConnectorInfoManager();
        m.register(bundleName, bundleVersion, loader);
        return m;
    }

    /** The bundles ({@code *.jar}) of a directory; a missing directory gives no connectors. */
    public static LocalConnectorInfoManager fromBundles(Path dir) throws IOException {
        LocalConnectorInfoManager m = new LocalConnectorInfoManager();
        if (!Files.isDirectory(dir)) {
            return m;
        }
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            throw new ConnectorException("connector bundles cannot be loaded by the native binary; "
                    + "serve them with ivy-connector-server and declare it in connector_servers");
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            ds.forEach(jars::add);
        }
        jars.sort(Comparator.naturalOrder());
        for (Path jar : jars) {
            m.addBundle(jar);
        }
        return m;
    }

    private void addBundle(Path jar) throws IOException {
        String name;
        String version;
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        try (JarFile jf = new JarFile(jar.toFile())) {
            Attributes attributes = jf.getManifest() == null ? new Attributes() : jf.getManifest().getMainAttributes();
            name = attributes.getValue(BUNDLE_NAME);
            version = attributes.getValue(BUNDLE_VERSION);
            String framework = attributes.getValue(FRAMEWORK_VERSION);
            if (name == null || version == null) {
                throw new ConnectorException(jar + " is not a connector bundle: missing " + BUNDLE_NAME
                        + " or " + BUNDLE_VERSION + " in its manifest");
            }
            if (!Framework.isCompatible(framework)) {
                throw new ConnectorException(jar + " was built for framework version " + framework
                        + ", ivy provides " + Framework.VERSION);
            }
            Path libs = null;
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().startsWith("lib/") || !e.getName().endsWith(".jar")) {
                    continue;
                }
                if (libs == null) {
                    libs = Files.createTempDirectory("ivy-bundle-" + name + "-");
                    tempDirs.add(libs);
                }
                Path target = libs.resolve(e.getName().substring(4).replace('/', '_'));
                try (InputStream in = jf.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                urls.add(toUrl(target));
            }
        }
        BundleClassLoader loader = new BundleClassLoader(name + "-" + version, urls);
        loaders.add(loader);
        register(name, version, loader);
    }

    private static URL toUrl(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void register(String bundleName, String bundleVersion, ClassLoader loader) {
        ServiceLoader.load(Connector.class, loader).stream().forEach(provider -> {
            Class<? extends Connector<?>> type = (Class<? extends Connector<?>>) (Class<?>) provider.type();
            LocalConnectorFacade facade = new LocalConnectorFacade(bundleName, bundleVersion, type);
            ConnectorFacade existing = facades.putIfAbsent(facade.info().type(), facade);
            if (existing != null && existing != facade) {
                throw new ConnectorException("connector type \"" + facade.info().type() + "\" is provided by both "
                        + existing.info().key() + " and " + facade.info().key());
            }
        });
    }

    @Override
    public List<ConnectorInfo> connectorInfos() {
        return facades.values().stream().map(ConnectorFacade::info).toList();
    }

    @Override
    public Optional<ConnectorFacade> find(String type) {
        return Optional.ofNullable(facades.get(type));
    }

    @Override
    public void close() throws IOException {
        for (BundleClassLoader l : loaders) {
            l.close();
        }
        for (Path dir : tempDirs) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
