package xyz.fokion.ivy.connectors.browser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import xyz.fokion.ivy.spi.ConnectorException;

/**
 * Browsers shared by the test cases of a run. Playwright objects must be used on the thread that
 * created them, so each browser lives on a thread of its own, and every call to it is submitted
 * there.
 * <pre>
 *  BrowserHosts: (browser, headless, wsEndpoint) → idle hosts
 *  ┌──────────── host ────────────┐
 *  │ one thread                   │   every Playwright call runs on this thread
 *  │ Playwright + Browser         │
 *  └──────────────────────────────┘
 *       ▲ borrow (or start one)    │ release when the test case ends
 *       │                          ▼
 *  test case: BrowserContext + Page, created and closed on the host thread
 *    ├─ browser crashed or disconnected ──► the host is closed, the next test case starts another
 *    └─ JVM shutdown ──► every host is closed
 * </pre>
 * A host serves one test case at a time: suites running in parallel get hosts of their own.
 */
final class BrowserHosts {

    record Key(String browser, boolean headless, String wsEndpoint) {
    }

    private static final Map<Key, Deque<Host>> IDLE = new HashMap<>();
    private static final Set<Host> ALL = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger COUNT = new AtomicInteger();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(BrowserHosts::closeAll, "ivy-browser-shutdown"));
    }

    private BrowserHosts() {
    }

    /** A browser on a thread of its own. */
    static final class Host {
        private final Key key;
        private final ExecutorService thread;
        private Playwright playwright;
        private Browser browser;
        private volatile boolean broken;

        private Host(Key key) {
            this.key = key;
            int n = COUNT.incrementAndGet();
            this.thread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ivy-browser-" + n);
                t.setDaemon(true);
                return t;
            });
        }

        Key key() {
            return key;
        }

        /** The browser; only used on the host thread, inside {@link #call}. */
        Browser browser() {
            return browser;
        }

        /** Runs work on the host thread and waits for it. */
        <T> T call(Callable<T> work) {
            try {
                return thread.submit(work).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectorException("interrupted while the browser was busy", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ConnectorException c) {
                    throw c;
                }
                throw new ConnectorException(message(cause), cause);
            }
        }

        /** Whether the host can serve another test case; asked on the host thread. */
        boolean usable() {
            if (broken) {
                return false;
            }
            try {
                return call(() -> browser != null && browser.isConnected());
            } catch (RuntimeException e) {
                return false;
            }
        }

        void markBroken() {
            broken = true;
        }

        private void start() {
            call(() -> {
                // browsers are installed apart (ivy browser install): never downloaded during a run
                playwright = Playwright.create(new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
                BrowserType type = switch (key.browser()) {
                    case "firefox" -> playwright.firefox();
                    case "webkit" -> playwright.webkit();
                    default -> playwright.chromium();
                };
                try {
                    browser = key.wsEndpoint().isEmpty()
                            ? type.launch(new BrowserType.LaunchOptions().setHeadless(key.headless()))
                            : type.connect(key.wsEndpoint());
                } catch (PlaywrightException e) {
                    playwright.close();
                    throw new ConnectorException(startError(key, e), e);
                }
                return null;
            });
        }

        private void close() {
            ALL.remove(this);
            try {
                thread.submit(() -> {
                    try {
                        if (browser != null) {
                            browser.close();
                        }
                    } finally {
                        if (playwright != null) {
                            playwright.close();
                        }
                    }
                    return null;
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // a browser that does not close in time is abandoned with its thread
            } finally {
                thread.shutdownNow();
            }
        }
    }

    /** An idle browser for the key, or a new one. */
    static Host borrow(Key key) {
        while (true) {
            Host idle;
            synchronized (IDLE) {
                Deque<Host> hosts = IDLE.get(key);
                idle = hosts == null ? null : hosts.pollFirst();
            }
            if (idle == null) {
                break;
            }
            if (idle.usable()) {
                return idle;
            }
            idle.close();
        }
        Host host = new Host(key);
        ALL.add(host);
        try {
            host.start();
        } catch (RuntimeException e) {
            host.close();
            throw e;
        }
        return host;
    }

    /** Gives a browser back once its test case ended; a broken one is closed. */
    static void release(Host host) {
        if (!host.usable()) {
            host.close();
            return;
        }
        synchronized (IDLE) {
            IDLE.computeIfAbsent(host.key(), k -> new ArrayDeque<>()).addFirst(host);
        }
    }

    /** The idle browsers, for tests. */
    static java.util.List<Host> idle() {
        synchronized (IDLE) {
            return IDLE.values().stream().flatMap(Deque::stream).toList();
        }
    }

    /** The number of browsers open, idle or in use. */
    static int open() {
        return ALL.size();
    }

    static void closeAll() {
        synchronized (IDLE) {
            IDLE.clear();
        }
        ALL.forEach(Host::close);
    }

    private static String startError(Key key, PlaywrightException e) {
        String m = message(e);
        if (key.wsEndpoint().isEmpty() && m.contains("Executable doesn't exist")) {
            return "no " + key.browser() + " found: run `ivy browser install " + key.browser() + "` (or `npx playwright install "
                    + key.browser() + "`), or set wsEndpoint to a Playwright server";
        }
        return key.wsEndpoint().isEmpty() ? "unable to start " + key.browser() + ": " + firstLine(m)
                : "unable to connect to " + key.wsEndpoint() + ": " + firstLine(m);
    }

    static String message(Throwable t) {
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /**
     * What went wrong, without the call log Playwright appends: the first line of the message, or
     * of its {@code message='...'} when it is written {@code Error { message='...' ... }}.
     */
    static String firstLine(String m) {
        int start = m.startsWith("Error {") ? m.indexOf("message='") : -1;
        String text = start < 0 ? m : m.substring(start + "message='".length());
        int nl = text.indexOf('\n');
        text = nl < 0 ? text : text.substring(0, nl);
        return start >= 0 && text.endsWith("'") ? text.substring(0, text.length() - 1) : text;
    }
}
