package xyz.fokion.ivy.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs venom's own test suites, unchanged, with ivy in a separate JVM whose working directory
 * is a copy of the suites. A {@code venom} script in that directory runs ivy, for the suites
 * that check the console output of failing runs.
 */
class VenomSuitesTest {

    @TempDir
    static Path dir;

    private static final List<String> SUITES = List.of(
            "assertions_only", "assertions_operators", "assertions", "builtin_var", "case", "exec", "extract",
            "hide_secrets", "interpolate_once", "json_quote", "multilines", "play-with-vars", "random_vars", "ranged",
            "readfile", "retry_if", "skip", "tmpl", "user_executor_camel_case", "user_executor_custom_multisteps",
            "user_executor_custom_must_assertion", "user_executor_custom", "user_executor_many",
            "user_executor_no_param", "user_executor_with_array", "user_executor_with_failure", "user_executor",
            "vars_quoted", "vars", "verbose_output");

    private static final List<String> HTTP_SUITES = List.of(
            "http", "http_content_type", "http_resolve_test", "interpolation", "user_executor_http_array", "user_executor_http_direct");

    @BeforeAll
    static void copySuites() throws IOException, URISyntaxException {
        Path source = Path.of(VenomSuitesTest.class.getResource("/venom-tests").toURI());
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path target = dir.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path script = dir.resolve("venom");
        Files.writeString(script, "#!/bin/sh\nexec \"" + java() + "\" -cp \"" + System.getProperty("java.class.path")
                + "\" xyz.fokion.ivy.cli.Main \"$@\"\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    record Run(int code, String output) {
    }

    private static Run ivy(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(java(), "-cp", System.getProperty("java.class.path"),
                "xyz.fokion.ivy.cli.Main"));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("IS_TTY", "false");
        pb.environment().put("VENOM_VAR_MY_ENVAR", "foo");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out);
    }

    private static List<String> args(List<String> suites) {
        List<String> args = new ArrayList<>(List.of("run", "--format=xml", "--output-dir=out", "--lib-dir=./lib_custom",
                "--var=array_from_var=[\"biz\",\"buz\"]", "--var-from-file", "./vars/vars.yml"));
        suites.forEach(s -> args.add("./" + s + ".yml"));
        return args;
    }

    @Test
    void venomSuitesPass() throws Exception {
        Run run = ivy(args(SUITES));
        assertEquals(0, run.code(), run.output());
        assertTrue(run.output().contains("final status: PASS"));
        assertTrue(Files.exists(dir.resolve("out/test_results_exec.xml")));
    }

    @Test
    void venomAssertionSuitesPass() throws Exception {
        List<String> args = new ArrayList<>(List.of("run", "--format=json", "--output-dir=out-assertions"));
        try (Stream<Path> files = Files.list(dir.resolve("assertions"))) {
            files.filter(f -> !f.getFileName().toString().equals("ShouldBeLessThan.yml"))
                    .map(f -> "assertions/" + f.getFileName()).sorted().forEach(args::add);
        }
        Run run = ivy(args);
        assertEquals(0, run.code(), run.output());
    }

    @Test
    void failingSuitesFail() throws Exception {
        try (Stream<Path> files = Files.list(dir.resolve("failing"))) {
            for (Path f : files.sorted().toList()) {
                Run run = ivy(List.of("run", "--output-dir=out-failing", "--lib-dir=./lib_custom", "failing/" + f.getFileName()));
                assertEquals(2, run.code(), f + "\n" + run.output());
                assertTrue(run.output().contains("final status: FAIL"), run.output());
            }
        }
    }

    /** Needs network access and httpbin on localhost:9280, as venom's own test stack. */
    @Test
    @EnabledIfEnvironmentVariable(named = "IVY_NETWORK_TESTS", matches = "true")
    void venomHttpSuitesPass() throws Exception {
        List<String> suites = new ArrayList<>(HTTP_SUITES);
        Run run = ivy(args(suites));
        assertEquals(0, run.code(), run.output());
        Run lessThan = ivy(List.of("run", "--output-dir=out-http", "assertions/ShouldBeLessThan.yml"));
        assertEquals(0, lessThan.code(), lessThan.output());
    }
}
