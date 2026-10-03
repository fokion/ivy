package xyz.fokion.ivy.core.engine;

import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.core.log.IvyLog.Fields;

/**
 * The position in a run: log fields, the file of the suite being run, and the log and console
 * of that suite.
 */
record RunContext(Fields fields, String filepath, IvyLog log, Console console) {

    static RunContext root(IvyLog log, Console console) {
        return new RunContext(Fields.EMPTY, "", log, console);
    }

    /** The context of a suite, with a log and a console of its own. */
    RunContext withTestsuite(String name, String file, IvyLog suiteLog, Console suiteConsole) {
        return new RunContext(fields.withTestsuite(name), file, suiteLog, suiteConsole);
    }

    RunContext withTestcase(String name) {
        return new RunContext(fields.withTestcase(name), filepath, log, console);
    }

    RunContext withExecutor(String name) {
        return new RunContext(fields.withExecutor(name), filepath, log, console);
    }

    /** The same position in another file, for the steps of a user executor. */
    RunContext withFile(String file) {
        return new RunContext(fields, file, log, console);
    }
}
