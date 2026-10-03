package xyz.fokion.ivy.connectors.browser;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

/** A browser step: which browser, and the actions to run on the page of the test case. */
public final class BrowserConfiguration implements Configuration {

    /** The actions, each a map with one of these keys. */
    static final Set<String> ACTIONS = Set.of("goto", "fill", "click", "press", "check", "select", "waitFor", "text",
            "attribute", "evaluate", "screenshot");
    /** The actions whose value goes to {@code result.values}, under their {@code as} name. */
    static final Set<String> CAPTURES = Set.of("text", "attribute", "evaluate");
    static final Set<String> BROWSERS = Set.of("chromium", "firefox", "webkit");

    private String browser = "chromium";
    private boolean headless = true;
    private String wsEndpoint = "";
    private long actionTimeout = 10_000;
    private List<Map<String, Object>> actions = List.of();

    public String getBrowser() {
        return browser;
    }

    @ConfigurationProperty(help = "chromium (default), firefox or webkit")
    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public boolean isHeadless() {
        return headless;
    }

    @ConfigurationProperty(help = "false shows the browser window (default true)")
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public String getWsEndpoint() {
        return wsEndpoint;
    }

    @ConfigurationProperty(help = "ws://... of a Playwright server (npx playwright run-server) to use instead of a local browser")
    public void setWsEndpoint(String wsEndpoint) {
        this.wsEndpoint = wsEndpoint;
    }

    public long getActionTimeout() {
        return actionTimeout;
    }

    @ConfigurationProperty(help = "milliseconds an action may wait for the page (default 10000)")
    public void setActionTimeout(long actionTimeout) {
        this.actionTimeout = actionTimeout;
    }

    public List<Map<String, Object>> getActions() {
        return actions;
    }

    @ConfigurationProperty(required = true, help = "goto, fill, click, press, check, select, waitFor, text, attribute, "
            + "evaluate, screenshot; text, attribute and evaluate need 'as', the name of their value in result.values")
    public void setActions(List<Map<String, Object>> actions) {
        this.actions = actions;
    }

    /** The name of an action: its one known key. */
    static String name(Map<String, Object> action) {
        List<String> known = action.keySet().stream().filter(ACTIONS::contains).toList();
        if (known.size() != 1) {
            throw new ConnectorException("an action has one of " + new java.util.TreeSet<>(ACTIONS) + ", got " + action.keySet());
        }
        return known.getFirst();
    }

    @Override
    public void validate() {
        if (!BROWSERS.contains(browser)) {
            throw new ConnectorException("browser must be chromium, firefox or webkit, got \"" + browser + "\"");
        }
        if (actions.isEmpty()) {
            throw new ConnectorException("actions are required");
        }
        if (actionTimeout <= 0) {
            throw new ConnectorException("actionTimeout must be positive");
        }
        Set<String> names = new HashSet<>();
        for (Map<String, Object> action : actions) {
            String name = name(action);
            if (CAPTURES.contains(name)) {
                Object as = action.get("as");
                if (!(as instanceof String s) || s.isBlank()) {
                    throw new ConnectorException(name + " needs 'as': the name of its value in result.values");
                }
                if (!names.add(s)) {
                    throw new ConnectorException("two actions capture '" + s + "': names in result.values must differ");
                }
            }
        }
    }
}
