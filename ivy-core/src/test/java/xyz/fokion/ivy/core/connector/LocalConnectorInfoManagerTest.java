package xyz.fokion.ivy.core.connector;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.fokion.ivy.spi.Framework;

class LocalConnectorInfoManagerTest {

    private static Path createBundleJar(Path dir, String name, String version) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(LocalConnectorInfoManager.BUNDLE_NAME, name);
        manifest.getMainAttributes().putValue(LocalConnectorInfoManager.BUNDLE_VERSION, version);
        manifest.getMainAttributes().putValue(LocalConnectorInfoManager.FRAMEWORK_VERSION, Framework.VERSION);

        Path jarPath = dir.resolve(name + ".jar");
        try (OutputStream os = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(os, manifest)) {
            jar.putNextEntry(new JarEntry("lib/nested.jar"));
            jar.write("nested-content".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return jarPath;
    }

    @Test
    void closesLoadersAndDeletesTempDirectories(@TempDir Path tempDir) throws Exception {
        Path bundlesDir = tempDir.resolve("bundles");
        Files.createDirectories(bundlesDir);
        createBundleJar(bundlesDir, "test-bundle", "1.0");

        LocalConnectorInfoManager manager = LocalConnectorInfoManager.fromBundles(bundlesDir);
        assertEquals(0, manager.connectorInfos().size());

        manager.close();
        // Calling close again should be idempotent and not throw
        assertDoesNotThrow(manager::close);
    }

    @Test
    void closeHandlesMissingTempDirsGracefully(@TempDir Path tempDir) throws Exception {
        Path bundlesDir = tempDir.resolve("bundles");
        Files.createDirectories(bundlesDir);
        createBundleJar(bundlesDir, "bundle1", "1.0");

        LocalConnectorInfoManager manager = LocalConnectorInfoManager.fromBundles(bundlesDir);
        assertDoesNotThrow(manager::close);
    }
}
