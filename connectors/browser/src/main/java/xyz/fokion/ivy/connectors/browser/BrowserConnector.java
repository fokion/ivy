package xyz.fokion.ivy.connectors.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;

import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.DefaultAssertionsProvider;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;

/**
 * Drives a browser with Playwright. A test case has one page, kept from step to step (a login
 * carries over), in a context of its own (no cookies from other test cases).
 * <p>
 * Actions run in order; the first failing one sets {@code error}, saves a screenshot and stops
 * the step. Values of {@code text}, {@code attribute} and {@code evaluate} go to
 * {@code result.values} under their {@code as} name.
 */
@ConnectorClass(type = "browser", configurationClass = BrowserConfiguration.class)
public final class BrowserConnector implements Connector<BrowserConfiguration>, DefaultAssertionsProvider {

    private BrowserHosts.Host host;
    private BrowserContext context;
    private Page page;

    @Override
    public List<Object> defaultAssertions() {
        // a comparison, so that a failure shows the value of result.error
        return List.of("result.error == \"\"");
    }

    @Override
    public Map<String, String> resultFields() {
        return Connector.fields(
                "url", "the URL of the page after the actions",
                "title", "the title of the page",
                "values", "the values of text, attribute and evaluate actions, by their 'as' name",
                "screenshots", "the paths of the screenshots taken, including the one of a failed action",
                "durationMs", "the duration in milliseconds",
                "error", "the first failed action and why, empty otherwise");
    }

    @Override
    public Object run(BrowserConfiguration config, StepContext ctx) throws Exception {
        BrowserHosts.Key key = new BrowserHosts.Key(config.getBrowser(), config.isHeadless(), config.getWsEndpoint());
        if (host == null) {
            host = BrowserHosts.borrow(key);
            try {
                host.call(() -> {
                    context = host.browser().newContext();
                    page = context.newPage();
                    return null;
                });
            } catch (RuntimeException e) {
                BrowserHosts.release(host);
                host = null;
                throw e;
            }
        } else if (!host.key().equals(key)) {
            throw new ConnectorException("the steps of a test case use one browser: " + host.key().browser()
                    + (host.key().wsEndpoint().isEmpty() ? "" : " at " + host.key().wsEndpoint()));
        }
        long start = System.nanoTime();
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> screenshots = new ArrayList<>();
        Path shots = screenshotDir(ctx);
        String error = host.call(() -> {
            context.setDefaultTimeout(config.getActionTimeout());
            List<Map<String, Object>> actions = config.getActions();
            for (int i = 0; i < actions.size(); i++) {
                Map<String, Object> action = actions.get(i);
                String name = BrowserConfiguration.name(action);
                try {
                    perform(name, action, values, screenshots, shots);
                } catch (PlaywrightException | ConnectorException e) {
                    String failure = "action #" + (i + 1) + " (" + name + "): " + BrowserHosts.firstLine(BrowserHosts.message(e));
                    if (!host.browser().isConnected()) {
                        host.markBroken();
                        return failure + " (the browser is gone)";
                    }
                    failureScreenshot(ctx, shots, screenshots);
                    return failure;
                }
            }
            return "";
        });
        String url = "";
        String title = "";
        if (host.usable()) {
            String[] page = host.call(() -> new String[] {this.page.url(), safeTitle()});
            url = page[0];
            title = page[1];
        }
        return Struct.builder("Result")
                .put("url", url)
                .put("title", title)
                .put("values", values)
                .put("screenshots", screenshots)
                .put("durationMs", (System.nanoTime() - start) / 1_000_000)
                .put("error", error)
                .build();
    }

    /** Runs one action on the host thread. */
    private void perform(String name, Map<String, Object> action, Map<String, Object> values, List<String> screenshots,
            Path shots) throws Exception {
        Object arg = action.get(name);
        switch (name) {
            case "goto" -> page.navigate(text(arg, "url"));
            case "fill" -> page.fill(selector(arg), text(field(arg, "value"), "value"));
            case "click" -> page.click(selector(arg));
            case "press" -> page.press(selector(arg), text(field(arg, "key"), "key"));
            case "check" -> page.check(selector(arg));
            case "select" -> page.selectOption(selector(arg), text(field(arg, "value"), "value"));
            case "waitFor" -> {
                Page.WaitForSelectorOptions options = new Page.WaitForSelectorOptions();
                if (field(arg, "state") instanceof String state) {
                    options.setState(WaitForSelectorState.valueOf(state.toUpperCase(java.util.Locale.ROOT)));
                }
                if (field(arg, "timeout") instanceof Number timeout) {
                    options.setTimeout(timeout.doubleValue());
                }
                page.waitForSelector(selector(arg), options);
            }
            case "text" -> values.put((String) action.get("as"), page.innerText(selector(arg)));
            case "attribute" -> values.put((String) action.get("as"),
                    page.getAttribute(selector(arg), text(field(arg, "name"), "name")));
            case "evaluate" -> values.put((String) action.get("as"), page.evaluate(text(arg, "script")));
            case "screenshot" -> {
                Path file = shots.resolve(text(arg, "path"));
                Files.createDirectories(file.toAbsolutePath().getParent());
                page.screenshot(new Page.ScreenshotOptions().setPath(file));
                screenshots.add(file.toAbsolutePath().normalize().toString());
            }
            default -> throw new ConnectorException("unknown action " + name);
        }
    }

    /** The screenshot of a failed action, named after the suite, the test case and the step. */
    private void failureScreenshot(StepContext ctx, Path shots, List<String> screenshots) {
        String name = (ctx.var("ivy.suite.shortName") + "-" + ctx.var("ivy.case.name") + "-step"
                + ctx.var("ivy.step.number") + "-failure.png").replaceAll("[^A-Za-z0-9._-]+", "_");
        try {
            Path file = shots.resolve(name);
            Files.createDirectories(file.toAbsolutePath().getParent());
            page.screenshot(new Page.ScreenshotOptions().setPath(file));
            screenshots.add(file.toAbsolutePath().normalize().toString());
        } catch (Exception ignored) {
            // the page may be gone: the error says what failed
        }
    }

    private String safeTitle() {
        try {
            return page.title();
        } catch (PlaywrightException e) {
            return "";
        }
    }

    /** Screenshots go to the output directory, or next to the suite. */
    private static Path screenshotDir(StepContext ctx) {
        String out = ctx.var("ivy.outputDir");
        return out == null || out.isEmpty() ? ctx.workdir() : Path.of(out);
    }

    @Override
    public void close() {
        if (host == null) {
            return;
        }
        try {
            host.call(() -> {
                if (context != null) {
                    context.close();
                }
                return null;
            });
        } catch (RuntimeException ignored) {
            // a context that cannot close goes with its browser, which release checks
        } finally {
            BrowserHosts.release(host);
            host = null;
        }
    }

    // ------------------------------------------------------------ action arguments

    /** The selector of an action: its value, or the {@code selector} of a map. */
    private static String selector(Object arg) {
        return text(arg instanceof Map<?, ?> m ? m.get("selector") : arg, "selector");
    }

    private static Object field(Object arg, String name) {
        return arg instanceof Map<?, ?> m ? m.get(name) : null;
    }

    private static String text(Object v, String what) {
        if (v == null || v instanceof Map<?, ?> || v instanceof List<?>) {
            throw new ConnectorException(what + " is required");
        }
        return String.valueOf(v);
    }
}
