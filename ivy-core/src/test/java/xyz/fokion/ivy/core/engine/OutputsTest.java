package xyz.fokion.ivy.core.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestSuite;
import xyz.fokion.ivy.core.model.Tests;

class OutputsTest {

    @Test
    void htmlReportIsSelfContainedAndSafe() throws Exception {
        TestSuite ts = new TestSuite();
        ts.name = "suite </script><script>alert(1)</script>";
        ts.status = Status.PASS;
        TestCase tc = new TestCase();
        tc.name = "case";
        tc.status = Status.PASS;
        ts.testCases.add(tc);
        Tests tests = new Tests();
        tests.testSuites = List.of(ts);
        tests.status = Status.PASS;

        String html = Outputs.html(tests);
        assertFalse(html.contains("__IVY_REPORT_JSON__"));
        assertTrue(html.contains("\\u003c/script\\u003e"), "the JSON must escape </script>");
        assertFalse(html.contains("</script><script>alert(1)"));
        // no CDN or remote resources: the report works offline
        assertFalse(Pattern.compile("(src|href)=\"https?://").matcher(html).find());
    }
}
