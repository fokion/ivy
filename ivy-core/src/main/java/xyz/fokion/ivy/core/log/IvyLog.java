package xyz.fokion.ivy.core.log;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The run log ({@code ivy.log}), formatted like logrus' nested formatter:
 * {@code Oct  2 21:46:33.794 [INFO] [suite] [testcase] [executor] message}.
 * <p>
 * Known secret values are replaced by {@code __hidden__} in every message (see {@link Secrets}).
 * <p>
 * Lines are held until {@link #flush()}. Each suite logs into its own {@link #buffer()}, so the
 * suites that run at the same time never write each other's lines:
 * <pre>
 *  suite A: buffer() ── lines ── flush() at the end of each test case ─┐
 *                                                                      ├─► writer (one lock,
 *  suite B: buffer() ── lines ── flush() at the end of each test case ─┘   a block at a time)
 * </pre>
 */
public final class IvyLog {

    public enum Level {
        DEBUG("DEBU"), INFO("INFO"), WARN("WARN"), ERROR("ERRO");

        private final String label;

        Level(String label) {
            this.label = label;
        }
    }

    /** Logging fields of the current position in the run. */
    public record Fields(String testsuite, String testcase, String step, String executor) {
        public static final Fields EMPTY = new Fields(null, null, null, null);

        public Fields withTestsuite(String name) {
            return new Fields(name, testcase, step, executor);
        }

        public Fields withTestcase(String name) {
            return new Fields(testsuite, name, step, executor);
        }

        public Fields withExecutor(String name) {
            return new Fields(testsuite, testcase, step, name);
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss.SSS", Locale.ENGLISH);

    private final PrintWriter out;
    private final Level threshold;
    private final Secrets secrets;
    /** Shared by a log and its buffers: guards the writer and every pending list. */
    private final Object lock;
    private final java.util.List<String> pending = new java.util.ArrayList<>();

    public IvyLog(Writer out, Level threshold, Secrets secrets) {
        this(out == null ? null : new PrintWriter(out, true), threshold, secrets, new Object());
    }

    private IvyLog(PrintWriter out, Level threshold, Secrets secrets, Object lock) {
        this.out = out;
        this.threshold = threshold;
        this.secrets = secrets;
        this.lock = lock;
    }

    /** A log with pending lines of its own, written to the same file. */
    public IvyLog buffer() {
        return new IvyLog(out, threshold, secrets, lock);
    }

    /** A log that discards everything. */
    public static IvyLog discard() {
        return new IvyLog(null, Level.ERROR, new Secrets());
    }

    public boolean enabled(Level level) {
        return out != null && level.ordinal() >= threshold.ordinal();
    }

    public void log(Level level, Fields fields, String message) {
        if (!enabled(level)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(LocalDateTime.now().format(STAMP)).append(" [").append(level.label).append("] ");
        for (String f : new String[] {fields.testsuite(), fields.testcase(), fields.step(), fields.executor()}) {
            if (f != null) {
                sb.append('[').append(f).append("] ");
            }
        }
        sb.append(message);
        synchronized (lock) {
            pending.add(sb.toString());
        }
    }

    /**
     * Writes the pending lines of this log. Lines are held until the end of each test case, so that
     * values a step captures as secrets are hidden in the lines written before the capture too.
     */
    public void flush() {
        if (out == null) {
            return;
        }
        synchronized (lock) {
            for (String line : pending) {
                out.println(secrets.hide(line));
            }
            pending.clear();
        }
    }

    public void debug(Fields f, String message) {
        log(Level.DEBUG, f, message);
    }

    public void info(Fields f, String message) {
        log(Level.INFO, f, message);
    }

    public void warn(Fields f, String message) {
        log(Level.WARN, f, message);
    }

    public void error(Fields f, String message) {
        log(Level.ERROR, f, message);
    }
}
