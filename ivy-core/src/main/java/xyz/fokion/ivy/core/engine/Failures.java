package xyz.fokion.ivy.core.engine;

import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.util.GoFormat;

final class Failures {

    private Failures() {
    }

    /** venom's {@code newFailure}, with the source location of the step or assertion. */
    static Failure newFailure(RunContext ctx, TestCase tc, int stepNumber, int rangedIndex, int assertionIndex,
            String assertion, String error) {
        String filepath = ctx.var("venom.testsuite.filepath");
        // step numbers are 1-based, source lines are indexed from 0
        int line = tc.findSourceLine(stepNumber - 1, assertionIndex);
        String value;
        if (!assertion.isEmpty()) {
            value = "Testcase " + GoFormat.quote(tc.originalName) + ", step #" + stepNumber + "-" + rangedIndex
                    + ": Assertion " + GoFormat.quote(GoStrings.removeNotPrintable(assertion)) + " failed. "
                    + GoStrings.removeNotPrintable(error) + " (" + filepath + ":" + line + ")";
        } else {
            value = "Testcase " + GoFormat.quote(tc.originalName) + ", step #" + stepNumber + "-" + rangedIndex
                    + ": " + GoStrings.removeNotPrintable(error) + " (" + filepath + ":" + line + ")";
        }
        Failure f = new Failure(value);
        f.testcaseClassname = filepath;
        f.testcaseName = tc.name;
        f.testcaseLineNumber = line;
        f.stepNumber = stepNumber;
        f.assertion = assertion;
        f.error = error;
        return f;
    }
}
