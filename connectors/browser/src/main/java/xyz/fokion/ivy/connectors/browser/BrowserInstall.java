package xyz.fokion.ivy.connectors.browser;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.microsoft.playwright.impl.driver.Driver;

/**
 * Installs browsers with the Playwright driver of the bundle: {@code ivy browser install chromium}
 * calls {@link #install} through the class loader of the bundle.
 */
public final class BrowserInstall {

    private BrowserInstall() {
    }

    /** Runs {@code playwright install <args>}, printing to the console, and returns its exit code. */
    public static int install(List<String> args) throws IOException, InterruptedException {
        Driver driver = Driver.ensureDriverInstalled(Map.of(), false);
        ProcessBuilder pb = driver.createProcessBuilder();
        pb.command().add("install");
        pb.command().addAll(args);
        pb.inheritIO();
        return pb.start().waitFor();
    }
}
