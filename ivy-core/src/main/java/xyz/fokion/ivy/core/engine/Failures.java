package xyz.fokion.ivy.core.engine;

import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.util.GoFormat;

final class Failures {

    private Failures() {
    }

    /**
     * A step failure, located at the step or assertion:
     * {@code Testcase "name", step #2 (suite.yml:14): message}.
     *
     * @param rangedIndex the iteration of a ranged step, or -1
     * @param assertionIndex the assertion, or -1
     */
    static Failure newFailure(RunContext ctx, TestCase tc, int stepNumber, int rangedIndex, int assertionIndex,
            String assertion, String error) {
        // step numbers are 1-based, source lines are indexed from 0
        int line = tc.findSourceLine(stepNumber - 1, assertionIndex);
        String step = rangedIndex >= 0 ? stepNumber + "-" + rangedIndex : Integer.toString(stepNumber);
        String location = ctx.filepath().isEmpty() ? "" : " (" + ctx.filepath() + ":" + line + ")";
        String value = "Testcase " + GoFormat.quote(tc.originalName) + ", step #" + step + location + ": "
                + GoStrings.removeNotPrintable(error);
        Failure f = new Failure(value);
        f.testcaseClassname = ctx.filepath();
        f.testcaseName = tc.name;
        f.testcaseLineNumber = line;
        f.stepNumber = stepNumber;
        f.assertion = assertion;
        f.error = error;
        return f;
    }
}
