package xyz.fokion.ivy.core.connectors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.DefaultAssertionsProvider;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.LazyJson;

/**
 * Runs a command or a script.
 * <p>
 * Result: {@code stdout}, {@code stderr}, {@code exitCode}, {@code json} (stdout parsed when it
 * is a JSON document, else null), {@code durationMs} and {@code error}.
 */
@ConnectorClass(type = "exec", configurationClass = ExecConfiguration.class)
public final class ExecConnector implements Connector<ExecConfiguration>, DefaultAssertionsProvider {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "stdout", "the standard output, trailing newline removed",
                "stderr", "the standard error",
                "exitCode", "the exit code of the process",
                "json", "stdout parsed as JSON, when it is JSON",
                "durationMs", "the duration in milliseconds",
                "error", "why the process could not run, empty otherwise");
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    @Override
    public List<Object> defaultAssertions() {
        return List.of("result.exitCode == 0");
    }

    private static Struct result(String out, String err, String error, long exitCode, long durationMs) {
        return Struct.builder("Result")
                .put("stdout", out)
                .put("stderr", err)
                .put("exitCode", exitCode)
                .put("json", LazyJson.of(out))
                .put("durationMs", durationMs)
                .put("error", error)
                .build();
    }

    @Override
    public Object run(ExecConfiguration config, StepContext context) throws Exception {
        List<String> command = new ArrayList<>(config.getCommand());
        Path scriptPath = null;
        String script = config.getScript();
        if (script != null && !script.isEmpty()) {
            String shell = "/bin/sh";
            if (script.startsWith("#!")) {
                String first = script.split("\n", 2)[0];
                shell = stripTrailing(first.substring(2), " \t\r\n");
            }
            List<String> opts = new ArrayList<>();
            if (WINDOWS) {
                shell = "PowerShell";
                opts.addAll(List.of("-ExecutionPolicy", "Bypass", "-Command"));
            }
            scriptPath = Files.createTempFile("ivy-", WINDOWS ? ".PS1" : "");
            Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
            context.log(StepContext.Level.DEBUG, "work with tmp file " + scriptPath);
            if (WINDOWS) {
                opts.add("& { $ErrorActionPreference='Stop'; & " + scriptPath + " ;exit $LastExitCode}");
            } else {
                Files.setPosixFilePermissions(scriptPath, PosixFilePermissions.fromString("rwx------"));
                opts.add(scriptPath.toString());
            }
            command = new ArrayList<>();
            command.add(shell);
            command.addAll(opts);
        }
        try {
            return execute(command, config.getStdin(), context);
        } finally {
            if (scriptPath != null) {
                Files.deleteIfExists(scriptPath);
            }
        }
    }

    private static Struct execute(List<String> command, String stdin, StepContext context) throws InterruptedException {
        long start = System.nanoTime();
        context.log(StepContext.Level.DEBUG, "teststep exec '" + String.join(" ", command) + "'");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(context.workdir().toFile());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            context.log(StepContext.Level.DEBUG, "error on cmd.Start: " + e.getMessage());
            return result("", "", e.getMessage(), 127, 0);
        }
        CompletableFuture<String> out = drain(process.getInputStream());
        CompletableFuture<String> err = drain(process.getErrorStream());
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // the process may not read its input
        }
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw e;
        }
        String stdout = GoStrings.removeNotPrintable(stripTrailing(out.join(), "\n"));
        String stderr = GoStrings.removeNotPrintable(stripTrailing(err.join(), "\n"));
        if (!stderr.isEmpty()) {
            context.log(StepContext.Level.DEBUG, stderr);
        }
        return result(stdout, stderr, "", code, (System.nanoTime() - start) / 1_000_000);
    }

    private static CompletableFuture<String> drain(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                in.transferTo(buf);
                return buf.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Thread::startVirtualThread);
    }

    private static String stripTrailing(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }
}
