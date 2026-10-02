package xyz.fokion.ivy.core.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.Skipped;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.model.TestStepResult;
import xyz.fokion.ivy.core.model.TestSuite;
import xyz.fokion.ivy.core.model.Tests;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.spi.util.GoFormat;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Writes the reports of a run: one file per suite in the chosen format (json, yaml, tap, xml)
 * and optionally an HTML report of all suites.
 */
public final class Outputs {

    private Outputs() {
    }

    /** venom's {@code OutputResult}. */
    public static void write(Ivy ivy) throws IvyException {
        if (ivy.outputDir.isEmpty()) {
            return;
        }
        Path dir = Path.of(ivy.outputDir);
        Tests tests = ivy.tests;
        List<TestSuite> cleaned = new ArrayList<>();
        for (TestSuite suite : tests.testSuites) {
            suite.testCases.removeIf(tc -> !tc.isEvaluated);
            TestSuite ts = cleanUpSecrets(ivy, suite);
            cleaned.add(ts);
            Tests result = tests.withSuites(List.of(ts));
            String data = switch (ivy.outputFormat) {
                case "json" -> Json.write(result.toJson(), Json.GO_INDENT);
                case "tap" -> tap(result);
                case "yml", "yaml" -> Yaml.dump(Json.parse(Json.write(result.toJson())));
                case "xml" -> xml(result, ivy.verbose);
                case "html" -> throw new IvyException("Error: you have to use the --html-report flag");
                default -> throw new IvyException("Error: unknown output format " + ivy.outputFormat);
            };
            String name = Path.of(ts.filepath).getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = dot < 0 ? name : name.substring(0, dot);
            Path file = dir.resolve("test_results_" + base + "." + ivy.outputFormat);
            writeFile(file, data);
            ivy.print("Writing file " + file + "\n");
        }
        if (ivy.htmlReport) {
            Path file = dir.resolve(uniqueFilename(dir, "test_results.html"));
            ivy.print("Writing html file " + file + "\n");
            writeFile(file, html(tests.withSuites(cleaned)));
        }
    }

    private static void writeFile(Path file, String data) throws IvyException {
        try {
            Files.writeString(file, data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IvyException("Error while creating file " + file + ": " + e.getMessage(), e);
        }
    }

    /** {@code name}, or {@code base.N.ext} when it already exists in the directory. */
    static String uniqueFilename(Path dir, String name) {
        if (!Files.isRegularFile(dir.resolve(name))) {
            return name;
        }
        int dot = name.indexOf('.');
        String base = name.substring(0, dot);
        String ext = name.substring(dot + 1);
        for (int i = 0; ; i++) {
            String candidate = base + "." + i + "." + ext;
            if (!Files.isRegularFile(dir.resolve(candidate))) {
                return candidate;
            }
        }
    }

    /** venom's {@code CleanUpSecrets}: a copy of the suite with secrets hidden. */
    static TestSuite cleanUpSecrets(Ivy ivy, TestSuite suite) {
        TestSuite ts = suite.copy();
        List<String> suiteSecrets = ivy.computeSecrets(ts, null);
        IvyLog.redactVars(ts.vars, ts.secrets, suiteSecrets);
        for (TestCase tc : ts.testCases) {
            List<String> secrets = ivy.computeSecrets(suite, tc);
            IvyLog.redactVars(tc.vars, ts.secrets, secrets);
            if (secrets.isEmpty()) {
                continue;
            }
            for (TestStepResult r : tc.testStepResults) {
                IvyLog.redactVars(r.computedVars, ts.secrets, secrets);
                IvyLog.redactStringVars(r.inputVars, ts.secrets, secrets);
                r.raw = hide(r.raw, secrets);
                r.interpolated = hide(r.interpolated, secrets);
                r.systemout = IvyLog.hideSensitive(r.systemout, secrets);
                r.systemerr = IvyLog.hideSensitive(r.systemerr, secrets);
                if (r.computedInfo != null) {
                    r.computedInfo.replaceAll(i -> IvyLog.hideSensitive(i, secrets));
                }
            }
        }
        return ts;
    }

    private static byte[] hide(byte[] data, List<String> secrets) {
        if (data == null) {
            return null;
        }
        return IvyLog.hideSensitive(new String(data, StandardCharsets.UTF_8), secrets).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ TAP

    /** TAP version 13: one line per test case, failures as diagnostics. */
    static String tap(Tests tests) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (TestSuite ts : tests.testSuites) {
            for (TestCase tc : ts.testCases) {
                n++;
                String name = ts.name + " / " + tc.name;
                if (tc.hasSkipped()) {
                    sb.append("ok ").append(n).append(" # SKIP ").append(name).append('\n');
                    continue;
                }
                List<Failure> failures = new ArrayList<>();
                tc.testStepResults.forEach(r -> failures.addAll(r.errorList()));
                if (failures.isEmpty()) {
                    sb.append("ok ").append(n).append(" - ").append(name).append('\n');
                } else {
                    sb.append("not ok ").append(n).append(" - ").append(name).append('\n');
                    for (Failure f : failures) {
                        String msg = stripTrailingNewlines("Error: " + f.value).replace("\n", "\n# ");
                        sb.append("# ").append(msg).append('\n');
                    }
                }
            }
        }
        sb.append("TAP version 13\n");
        if (n > 0) {
            sb.append("1..").append(n).append('\n');
        }
        return sb.toString();
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end);
    }

    // ------------------------------------------------------------ JUnit XML

    /** JUnit XML as venom writes it with Go's {@code encoding/xml}. */
    static String xml(Tests tests, int verbose) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><testsuites>");
        for (TestSuite ts : tests.testSuites) {
            int errors = 0;
            int skipped = 0;
            for (TestCase tc : ts.testCases) {
                if (tc.status == Status.FAIL) {
                    errors++;
                } else if (tc.status == Status.SKIP) {
                    skipped++;
                }
            }
            sb.append("\n  <testsuite");
            if (errors > 0) {
                attr(sb, "errors", Integer.toString(errors));
            }
            attr(sb, "name", ts.name);
            if (!ts.filepath.isEmpty()) {
                attr(sb, "package", ts.filepath);
            }
            if (skipped > 0) {
                attr(sb, "skipped", Integer.toString(skipped));
            }
            attr(sb, "tests", Integer.toString(ts.testCases.size()));
            attr(sb, "time", String.format(Locale.ROOT, "%f", ts.duration));
            sb.append('>');
            for (TestCase tc : ts.testCases) {
                StringBuilder out = new StringBuilder();
                StringBuilder err = new StringBuilder();
                List<Failure> failures = new ArrayList<>();
                for (TestStepResult r : tc.testStepResults) {
                    failures.addAll(r.errorList());
                    if (r.hasErrors() || verbose > 1) {
                        out.append(r.systemout.replace("\u0003", ""));
                    }
                    err.append(r.systemerr.replace("\u0003", ""));
                }
                sb.append("\n    <testcase");
                if (!ts.filename.isEmpty()) {
                    attr(sb, "classname", ts.filename);
                }
                attr(sb, "name", tc.name);
                if (tc.duration != 0) {
                    attr(sb, "time", GoFormat.formatFloatG(tc.duration));
                }
                if (!tc.id.isEmpty()) {
                    attr(sb, "id", tc.id);
                }
                sb.append('>');
                for (Failure f : failures) {
                    sb.append("\n      <error>").append(cdata(f.value)).append("</error>");
                }
                if (tc.skipped != null) {
                    for (Skipped s : tc.skipped) {
                        sb.append("\n      <skipped>").append(cdata(s.value())).append("</skipped>");
                    }
                }
                sb.append("\n      <system-out>").append(cdata(out.toString())).append("</system-out>");
                sb.append("\n      <system-err>").append(cdata(err.toString())).append("</system-err>");
                sb.append("\n    </testcase>");
            }
            sb.append("\n  </testsuite>");
        }
        sb.append("\n</testsuites>");
        return sb.toString();
    }

    private static void attr(StringBuilder sb, String name, String value) {
        sb.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"');
    }

    /** Go's {@code xml.EscapeText} as used for attributes. */
    private static String escapeAttr(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("&#34;");
                case '\'' -> sb.append("&#39;");
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\t' -> sb.append("&#x9;");
                case '\n' -> sb.append("&#xA;");
                case '\r' -> sb.append("&#xD;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String cdata(String s) {
        if (s.isEmpty()) {
            return "";
        }
        return "<![CDATA[" + s.replace("]]>", "]]]]><![CDATA[>") + "]]>";
    }

    // ------------------------------------------------------------ HTML

    static String html(Tests tests) throws IvyException {
        String template;
        try (InputStream in = Outputs.class.getResourceAsStream("/xyz/fokion/ivy/core/output/report.html")) {
            if (in == null) {
                throw new IvyException("the HTML report template is missing");
            }
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IvyException("unable to make template: " + e.getMessage(), e);
        }
        Map<String, Object> json = new LinkedHashMap<>(tests.toJson());
        return template.replace("__IVY_REPORT_JSON__", Json.write(json, Json.GO_INDENT.withIndent(" ")));
    }
}
