package xyz.fokion.ivy.core.engine;

import java.io.PrintStream;

import xyz.fokion.ivy.core.log.Secrets;

/**
 * The console output of one suite.
 * <p>
 * When suites run one at a time, lines are written as they come, as before. When they run in
 * parallel, the lines of a test case are held and written as one block when it ends, so that the
 * lines of different suites are never mixed:
 * <pre>
 *  suite A: print ── held ── flush() at the end of each test case ─┐
 *                                                                  ├─► out (one block at a time,
 *  suite B: print ── held ── flush() at the end of each test case ─┘   secrets hidden)
 * </pre>
 */
final class Console {

    private final PrintStream out;
    private final Secrets secrets;
    /** The held lines, or null when lines are written as they come. */
    private final StringBuilder held;

    private Console(PrintStream out, Secrets secrets, boolean hold) {
        this.out = out;
        this.secrets = secrets;
        this.held = hold ? new StringBuilder() : null;
    }

    /** A console writing lines as they come. */
    static Console direct(PrintStream out, Secrets secrets) {
        return new Console(out, secrets, false);
    }

    /** A console holding lines until {@link #flush()}. */
    static Console held(PrintStream out, Secrets secrets) {
        return new Console(out, secrets, true);
    }

    void print(String s) {
        if (held == null) {
            synchronized (out) {
                out.print(secrets.hide(s));
            }
        } else {
            held.append(s);
        }
    }

    void println(String s) {
        if (held == null) {
            synchronized (out) {
                out.println(secrets.hide(s));
            }
        } else {
            held.append(s).append(System.lineSeparator());
        }
    }

    /**
     * Writes the held lines as one block; secrets captured after a line was printed are hidden
     * in it too.
     */
    void flush() {
        if (held == null || held.isEmpty()) {
            return;
        }
        String block = secrets.hide(held.toString());
        held.setLength(0);
        synchronized (out) {
            out.print(block);
            out.flush();
        }
    }
}
