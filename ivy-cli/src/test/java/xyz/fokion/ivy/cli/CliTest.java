package xyz.fokion.ivy.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ported from venom's cmd/venom/run/cmd_test.go, plus flag and config handling. */
class CliTest {

    private static Cli cli(Map<String, String> env, Path dir) {
        PrintStream discard = new PrintStream(new ByteArrayOutputStream());
        return new Cli(env, discard, discard, dir, dir);
    }

    @Test
    void readsInitialVariablesFromArgs(@TempDir Path dir) throws Exception {
        Cli.Settings s = new Cli.Settings();
        s.variables.addAll(List.of("a=1", "b=\"B\"", "c=[1,2,3]"));
        assertEquals(Map.of("a", 1L, "b", "B", "c", List.of(1L, 2L, 3L)), cli(Map.of(), dir).readInitialVariables(s));

        s = new Cli.Settings();
        s.variables.add("db.dsn=\"user=test password=test dbname=yo host=localhost port=1234 sslmode=disable\"");
        assertEquals(Map.of("db.dsn", "user=test password=test dbname=yo host=localhost port=1234 sslmode=disable"),
                cli(Map.of(), dir).readInitialVariables(s));
    }

    @Test
    void readsInitialVariablesFromFiles(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("vars.yml"), "\na: 1\nb: B\nc:\n  - 1\n  - 2\n  - 3");
        Cli.Settings s = new Cli.Settings();
        s.varFiles.add(f.toString());
        assertEquals(Map.of("a", 1L, "b", "B", "c", List.of(1L, 2L, 3L)), cli(Map.of(), dir).readInitialVariables(s));
    }

    @Test
    void mergesVariables() {
        List<String> a = new ArrayList<>(List.of("cc=dd", "ee=ff"));
        Cli.mergeVariable("aa=bb", a);
        assertEquals(3, a.size());
        List<String> b = new ArrayList<>(List.of("aa=dd"));
        Cli.mergeVariable("aa=bb", b);
        assertEquals(List.of("aa=bb"), b);
        List<String> c = new ArrayList<>(List.of("aa=dd"));
        Cli.mergeVariable("aa=bb=dd", c);
        assertEquals(1, c.size());
        List<String> d = new ArrayList<>(List.of("cc=dd"));
        Cli.mergeVariable("aa=bb=dd", d);
        assertEquals(2, d.size());
    }

    @Test
    void readsEnvironment(@TempDir Path dir) throws Exception {
        Cli.Settings s = new Cli.Settings();
        cli(Map.of("VENOM_VAR_a", "1", "VENOM_VAR_b", "\"B\"", "VENOM_VAR_c", "[1,2,3]", "VENOM_VAR_d", "\"e=f\"",
                "IVY_FORMAT", "json", "VENOM_FORMAT", "tap", "IVY_VERBOSE", "2"), dir).fromEnv(s);
        assertTrue(s.variables.containsAll(List.of("a=1", "b=\"B\"", "c=[1,2,3]", "d=\"e=f\"")));
        assertEquals("json", s.format);
        assertEquals(2, s.verbose);
        assertThrows(Cli.UsageException.class, () -> cli(Map.of("IVY_STOP_ON_FAILURE", "maybe"), dir).fromEnv(new Cli.Settings()));
    }

    @Test
    void flagsOverrideConfigFileWhichOverridesEnvironment(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".ivyrc"), """
                format: yaml
                output_dir: out
                variables:
                  - foo=from-rc
                verbosity: 1
                connector_servers:
                  - host: example
                    port: 9000
                    key: k
                """);
        Cli c = cli(Map.of("IVY_FORMAT", "json", "IVY_VAR_foo", "from-env"), dir);
        Cli.Settings s = new Cli.Settings();
        c.fromEnv(s);
        c.fromConfigFile(s);
        assertEquals("yaml", s.format);
        assertEquals(List.of("foo=from-rc"), s.variables);
        assertEquals(List.of("k@example:9000"), s.connectorServers);
        c.fromArgs(s, List.of("--format=xml", "-vv", "--var", "foo=from-cmd", "a.yml", "--stop-on-failure", "b.yml"));
        assertEquals("xml", s.format);
        assertEquals(2, s.verbose);
        assertTrue(s.stopOnFailure);
        assertEquals(List.of("foo=from-cmd"), s.variables);
        assertEquals(List.of("a.yml", "b.yml"), s.paths);
        assertThrows(Cli.UsageException.class, () -> c.fromArgs(new Cli.Settings(), List.of("--nope")));
    }

    @Test
    void printsVersionAndUsage(@TempDir Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Cli c = new Cli(Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8), System.err, dir, dir);
        assertEquals(0, c.run("version"));
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("Version ivy: "));
        assertEquals(2, c.run());
        assertEquals(2, c.run("nope"));
    }
}
