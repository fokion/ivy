package xyz.fokion.ivy.core.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import xyz.fokion.ivy.core.engine.Ivy.IvyException;
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
 * and optionally an HTML report of all suites. Reports are streamed to their files; secrets are
 * hidden and long strings cut while writing.
 */
public final class Outputs {

    private Outputs() {
    }

    /** Writes the reports of the run. */
    public static void write(Ivy ivy) throws IvyException {
        if (ivy.outputDir.isEmpty()) {
            return;
        }
        Path dir = Path.of(ivy.outputDir);
        Tests tests = ivy.tests;
        Json.Options options = Json.GO_INDENT.withMaxString(ivy.reportMaxValue);
        // every secret of the run is hidden in every report
        UnaryOperator<String> hide = ivy.secrets.filter();
        for (TestSuite suite : tests.testSuites) {
            suite.testCases.removeIf(tc -> !tc.isEvaluated);
            Tests result = tests.withSuites(List.of(suite));
            String name = Path.of(suite.filepath).getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = dot < 0 ? name : name.substring(0, dot);
            String extension = ivy.outputFormat.equals("cucumber") ? "cucumber.json" : ivy.outputFormat;
            Path file = dir.resolve("test_results_" + base + "." + extension);
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                switch (ivy.outputFormat) {
                    case "json" -> Json.write(result.toJson(), options, out, hide);
                    case "tap" -> out.write(hide.apply(tap(result)));
                    case "yml", "yaml" -> {
                        StringBuilder json = new StringBuilder();
                        Json.write(result.toJson(), Json.GO.withMaxString(ivy.reportMaxValue), json, hide);
                        out.write(Yaml.dump(Json.parse(json.toString())));
                    }
                    case "xml" -> out.write(hide.apply(xml(result, ivy.verbose)));
                    case "cucumber" -> out.write(hide.apply(CucumberReport.write(result)));
                    case "html" -> throw new IvyException("Error: you have to use the --html-report flag");
                    default -> throw new IvyException("Error: unknown output format " + ivy.outputFormat);
                }
            } catch (IOException | UncheckedIOException e) {
                throw new IvyException("Error while creating file " + file + ": " + e.getMessage(), e);
            }
            ivy.print("Writing file " + file + "\n");
        }
        if (ivy.htmlReport) {
            Path file = dir.resolve(uniqueFilename(dir, "test_results.html"));
            ivy.print("Writing html file " + file + "\n");
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                html(tests, out, options.withIndent(" "), hide);
            } catch (IOException | UncheckedIOException e) {
                throw new IvyException("Error while creating file " + file + ": " + e.getMessage(), e);
            }
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

    /** JUnit XML, laid out as Go's {@code encoding/xml} writes it. */
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
                        out.append(r.stdout.replace("\u0003", ""));
                    }
                    err.append(r.stderr.replace("\u0003", ""));
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

    private static final String PLACEHOLDER = "__IVY_REPORT_JSON__";

    /** Writes the HTML report: the template with the results streamed in place of its placeholder. */
    static void html(Tests tests, Writer out, Json.Options options, UnaryOperator<String> hide)
            throws IvyException, IOException {
        String template;
        try (InputStream in = Outputs.class.getResourceAsStream("/xyz/fokion/ivy/core/output/report.html")) {
            if (in == null) {
                throw new IvyException("the HTML report template is missing");
            }
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int at = template.indexOf(PLACEHOLDER);
        if (at < 0) {
            throw new IvyException("the HTML report template has no " + PLACEHOLDER);
        }
        out.write(template, 0, at);
        // escaping < > & keeps the data inside its <script> element
        Json.write(tests.toJson(), options, out, hide);
        out.write(template, at + PLACEHOLDER.length(), template.length() - at - PLACEHOLDER.length());
    }

    /** The HTML report as a string. */
    static String html(Tests tests) throws IvyException {
        java.io.StringWriter out = new java.io.StringWriter();
        try {
            html(tests, out, Json.GO_INDENT.withIndent(" "), UnaryOperator.identity());
        } catch (IOException e) {
            throw new IvyException(e.getMessage(), e);
        }
        return out.toString();
    }
}
