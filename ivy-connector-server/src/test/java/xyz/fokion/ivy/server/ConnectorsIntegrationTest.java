package xyz.fokion.ivy.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.fokion.ivy.core.connector.BundleClassLoader;
import xyz.fokion.ivy.core.connector.ConnectorInfo;
import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.connector.LocalConnectorInfoManager;
import xyz.fokion.ivy.core.connector.remote.RemoteConnectorInfoManager;
import xyz.fokion.ivy.core.engine.Ivy;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorException;

/**
 * The connector framework end to end: the sql bundle loaded locally, then served by a
 * connector server, running the same suite through the engine.
 */
class ConnectorsIntegrationTest {

    private static Path bundles() {
        return Path.of(System.getProperty("ivy.test.bundles"));
    }

    private static Path suite(Path dir) throws IOException, URISyntaxException {
        Path source = Path.of(ConnectorsIntegrationTest.class.getResource("/sql-suite").toURI());
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path target = dir.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return dir.resolve("sql.yml");
    }

    private static Status run(Path suite, ConnectorInfoManager connectors, ByteArrayOutputStream out) throws Exception {
        Ivy ivy = new Ivy(new PrintStream(out, true, StandardCharsets.UTF_8)).colors(false)
                .outputDir(suite.getParent().resolve("out").toString());
        ivy.initLogger();
        ivy.addConnectors(connectors);
        try {
            ivy.parse(List.of(suite.toString()));
            ivy.process();
        } finally {
            ivy.close();
        }
        return ivy.tests().status;
    }

    @Test
    void runsBundleConnectorsLocally(@TempDir Path dir) throws Exception {
        LocalConnectorInfoManager connectors = LocalConnectorInfoManager.fromBundles(bundles());
        ConnectorInfo info = connectors.find("sql").orElseThrow().info();
        assertEquals("sql", info.key().bundleName());
        assertTrue(info.properties().stream().anyMatch(p -> p.name().equals("dsn") && p.required() && p.secret()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(Status.PASS, run(suite(dir), connectors, out), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void runsConnectorsThroughAConnectorServer(@TempDir Path dir) throws Exception {
        try (LocalConnectorInfoManager local = LocalConnectorInfoManager.fromBundles(bundles());
             ConnectorServer server = ConnectorServer.start(local, "127.0.0.1", 0, "s3cret", false)) {
            RemoteConnectorInfoManager remote = RemoteConnectorInfoManager.connect("s3cret@127.0.0.1:" + server.port());
            assertEquals(List.of("sql"), remote.connectorInfos().stream().map(ConnectorInfo::type).toList());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(Status.PASS, run(suite(dir), remote, out), out.toString(StandardCharsets.UTF_8));

            ConnectorException denied = assertThrows(ConnectorException.class,
                    () -> RemoteConnectorInfoManager.connect("wrong@127.0.0.1:" + server.port()));
            assertEquals("invalid key", denied.getMessage());
        }
    }

    @Test
    void reportsConnectorErrorsFromTheServer(@TempDir Path dir) throws Exception {
        Path suite = suite(dir);
        Files.writeString(suite, """
                name: failing sql
                testcases:
                - name: bad-query
                  steps:
                  - type: sql
                    driver: sqlite
                    dsn: ":memory:"
                    commands: ["SELECT * FROM missing"]
                """);
        try (LocalConnectorInfoManager local = LocalConnectorInfoManager.fromBundles(bundles());
             ConnectorServer server = ConnectorServer.start(local, "127.0.0.1", 0, "k", false)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(Status.FAIL, run(suite, RemoteConnectorInfoManager.connect("k@127.0.0.1:" + server.port()), out));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("failed to exec command number 0"),
                    out.toString(StandardCharsets.UTF_8));
        }
    }

    private static Path jar(Path file, String entry, String content, Manifest manifest) throws IOException {
        try (OutputStream os = Files.newOutputStream(file);
             JarOutputStream jar = manifest == null ? new JarOutputStream(os) : new JarOutputStream(os, manifest)) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(content.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }

    @Test
    void isolatesBundles(@TempDir Path dir) throws Exception {
        Path a = jar(dir.resolve("a.jar"), "driver/version.txt", "1.0", null);
        Path b = jar(dir.resolve("b.jar"), "driver/version.txt", "2.0", null);
        try (BundleClassLoader la = new BundleClassLoader("a", List.of(a.toUri().toURL()));
             BundleClassLoader lb = new BundleClassLoader("b", List.of(b.toUri().toURL()))) {
            assertEquals("1.0", read(la.getResource("driver/version.txt")));
            assertEquals("2.0", read(lb.getResource("driver/version.txt")));
            // the SPI is shared with ivy, nothing else of ivy's classpath is visible
            assertSame(Connector.class, la.loadClass(Connector.class.getName()));
            assertThrows(ClassNotFoundException.class, () -> la.loadClass(Ivy.class.getName()));
            assertThrows(ClassNotFoundException.class, () -> la.loadClass("org.snakeyaml.engine.v2.api.Load"));
        }
    }

    @Test
    void refusesIncompatibleBundles(@TempDir Path dir) throws Exception {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue(LocalConnectorInfoManager.BUNDLE_NAME, "future");
        m.getMainAttributes().putValue(LocalConnectorInfoManager.BUNDLE_VERSION, "1");
        m.getMainAttributes().putValue(LocalConnectorInfoManager.FRAMEWORK_VERSION, "2.0");
        Path bundles = Files.createDirectory(dir.resolve("bundles"));
        jar(bundles.resolve("future.jar"), "x.txt", "x", m);
        ConnectorException e = assertThrows(ConnectorException.class, () -> LocalConnectorInfoManager.fromBundles(bundles));
        assertTrue(e.getMessage().contains("framework version 2.0"));
    }

    private static String read(URL url) throws IOException {
        try (var in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
