package xyz.fokion.ivy.core.testing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import xyz.fokion.ivy.core.engine.Ivy;
import xyz.fokion.ivy.core.model.Status;
import xyz.fokion.ivy.core.model.TestStepResult;

/**
 * Runs suites through the engine with the connectors of the test classpath, for connector
 * integration tests.
 */
public final class SuiteRunner {

    /** The outcome of a run: its status, what it printed and the failures of its steps. */
    public record Outcome(Status status, String output, List<String> failures) {
        public boolean passed() {
            return status == Status.PASS;
        }

        /** A message for assertion failures. */
        public String describe() {
            return output + "\n" + String.join("\n", failures);
        }
    }

    private SuiteRunner() {
    }

    /**
     * Copies a classpath directory of suites to {@code workdir} and runs the given suite there
     * with the variables.
     */
    public static Outcome run(Class<?> anchor, String resourceDir, Path workdir, String suite, Map<String, Object> vars)
            throws Exception {
        Path source = Path.of(Objects.requireNonNull(anchor.getResource(resourceDir)).toURI());
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path target = workdir.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Ivy ivy = new Ivy(new PrintStream(out, true, StandardCharsets.UTF_8)).colors(false)
                .outputDir(workdir.resolve("out").toString());
        ivy.initLogger();
        ivy.addVariables(vars);
        try {
            ivy.parse(List.of(workdir.resolve(suite).toString()));
            ivy.process();
        } finally {
            ivy.close();
        }
        List<String> failures = ivy.tests().testSuites.stream()
                .flatMap(ts -> ts.testCases.stream())
                .flatMap(tc -> tc.testStepResults.stream())
                .flatMap((TestStepResult r) -> r.errorList().stream())
                .map(f -> f.value)
                .toList();
        return new Outcome(ivy.tests().status, out.toString(StandardCharsets.UTF_8), failures);
    }
}
