package xyz.fokion.ivy.connectors.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xyz.fokion.ivy.spi.ConnectorException;

/** The browser connector without a browser: configuration, errors and the installer. */
class BrowserConnectorTest {

    private static BrowserConfiguration config(List<Map<String, Object>> actions) {
        BrowserConfiguration c = new BrowserConfiguration();
        c.setActions(actions);
        return c;
    }

    @Test
    void acceptsNamedCaptures() {
        config(List.of(Map.of("goto", "http://x"), Map.of("text", ".a", "as", "a"), Map.of("evaluate", "() => 1", "as", "b")))
                .validate();
    }

    @Test
    void rejectsCapturesWithoutAUniqueName() {
        ConnectorException missing = assertThrows(ConnectorException.class,
                () -> config(List.of(Map.of("text", ".a"))).validate());
        assertTrue(missing.getMessage().contains("text needs 'as'"), missing.getMessage());
        ConnectorException twice = assertThrows(ConnectorException.class,
                () -> config(List.of(Map.of("text", ".a", "as", "x"), Map.of("attribute", Map.of("selector", "a", "name", "href"),
                        "as", "x"))).validate());
        assertTrue(twice.getMessage().contains("two actions capture 'x'"), twice.getMessage());
    }

    @Test
    void rejectsUnknownActionsAndBrowsers() {
        assertTrue(assertThrows(ConnectorException.class, () -> config(List.of(Map.of("hover", ".a"))).validate())
                .getMessage().contains("an action has one of"));
        assertTrue(assertThrows(ConnectorException.class, () -> config(List.of()).validate())
                .getMessage().contains("actions are required"));
        BrowserConfiguration c = config(List.of(Map.of("goto", "http://x")));
        c.setBrowser("netscape");
        assertTrue(assertThrows(ConnectorException.class, c::validate).getMessage().contains("chromium, firefox or webkit"));
    }

    @Test
    void explainsHowToGetABrowserAndDownloadsNothing() {
        // the test task points PLAYWRIGHT_BROWSERS_PATH to an empty directory
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> BrowserHosts.borrow(new BrowserHosts.Key("chromium", true, "")));
        assertTrue(e.getMessage().startsWith("no chromium found: run `ivy browser install chromium`"), e.getMessage());
        assertEquals(0, BrowserHosts.open(), "the failed host is closed");
    }

    @Test
    void reportsItsResultFieldsAndDefaultAssertion() {
        BrowserConnector c = new BrowserConnector();
        assertEquals(List.of("url", "title", "values", "screenshots", "durationMs", "error"), List.copyOf(c.resultFields().keySet()));
        assertEquals(List.of("result.error == \"\""), c.defaultAssertions());
    }

    @Test
    void installsWithTheDriverOfTheBundle() throws Exception {
        // --dry-run prints what would be downloaded, and downloads nothing
        assertEquals(0, BrowserInstall.install(List.of("--dry-run", "chromium")));
    }

    @Test
    void keepsWhatWentWrongFromPlaywrightMessages() {
        assertEquals("Timeout 15000ms exceeded.", BrowserHosts.firstLine("""
                Error {
                  message='Timeout 15000ms exceeded.
                =========================== logs ===========================
                waiting for locator("#wob_wc") to be visible
                  name='TimeoutError
                }"""));
        assertEquals("net::ERR_NAME_NOT_RESOLVED at http://nope/", BrowserHosts.firstLine(
                "net::ERR_NAME_NOT_RESOLVED at http://nope/\nCall log:\n  - navigating"));
    }
}
