package xyz.fokion.ivy.core.engine;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.model.TestSuite;

/**
 * Runs suites, up to {@code parallel} at a time, each on a virtual thread. The test cases of a
 * suite still run one after the other.
 * <pre>
 *  suites, in order ──► acquire permits ──► virtual thread: run the suite ──► release permits
 *                        ├─ suite                  → 1 permit
 *                        └─ suite (parallel:false) → all permits: it runs alone
 *
 *  a suite throws ──► no other suite starts; running suites finish; the first error is rethrown
 * </pre>
 * Permits are taken in suite order by the calling thread, so a suite that waits for all of them
 * is never overtaken by the suites after it.
 */
final class Scheduler {

    /** Runs one suite. */
    interface SuiteRunner {
        void run(TestSuite ts) throws IvyException;
    }

    private Scheduler() {
    }

    static void run(List<TestSuite> suites, int parallel, SuiteRunner runner) throws IvyException {
        if (parallel <= 1) {
            for (TestSuite ts : suites) {
                runner.run(ts);
            }
            return;
        }
        Semaphore permits = new Semaphore(parallel, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (TestSuite ts : suites) {
                int needed = ts.parallel ? 1 : parallel;
                permits.acquireUninterruptibly(needed);
                if (failure.get() != null) {
                    permits.release(needed);
                    break;
                }
                threads.submit(() -> {
                    try {
                        runner.run(ts);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        permits.release(needed);
                    }
                });
            }
        }
        rethrow(failure.get());
    }

    private static void rethrow(Throwable t) throws IvyException {
        switch (t) {
            case null -> {
            }
            case IvyException e -> throw e;
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            default -> throw new IvyException(t.getMessage() == null ? t.toString() : t.getMessage(), t);
        }
    }
}
