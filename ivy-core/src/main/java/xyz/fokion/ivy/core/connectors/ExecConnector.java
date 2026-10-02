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
import xyz.fokion.ivy.spi.ZeroValueResultProvider;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Runs a command or a script, port of venom's {@code exec} executor.
 * <p>
 * Result: {@code systemout}, {@code systemoutjson}, {@code systemerr}, {@code systemerrjson},
 * {@code err}, {@code code} (a string) and {@code timeseconds}.
 */
@ConnectorClass(type = "exec", configurationClass = ExecConfiguration.class)
public final class ExecConnector implements Connector<ExecConfiguration>, DefaultAssertionsProvider,
        ZeroValueResultProvider {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    @Override
    public List<Object> defaultAssertions() {
        return List.of("result.code ShouldEqual 0");
    }

    @Override
    public Object zeroValueResult() {
        return result("", null, "", null, "", "", 0);
    }

    private static Struct result(String out, Object outJson, String err, Object errJson, String error, String code,
            double seconds) {
        return Struct.builder("Result")
                .put("systemout", out)
                .put("systemoutjson", outJson)
                .put("systemerr", err)
                .put("systemerrjson", errJson)
                .put("err", error)
                .put("code", code)
                .put("timeseconds", seconds)
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
        String workdir = context.var("venom.testsuite.workdir");
        if (workdir != null && !workdir.isEmpty()) {
            pb.directory(Path.of(workdir).toFile());
        }
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            context.log(StepContext.Level.DEBUG, "error on cmd.Start: " + e.getMessage());
            return result("", null, "", null, e.getMessage(), "127", 0);
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
        String systemout = GoStrings.removeNotPrintable(stripTrailing(out.join(), "\n"));
        String systemerr = GoStrings.removeNotPrintable(stripTrailing(err.join(), "\n"));
        if (!systemerr.isEmpty()) {
            context.log(StepContext.Level.DEBUG, systemerr);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        return result(systemout, Json.tryParse(systemout), systemerr, Json.tryParse(systemerr), "",
                Integer.toString(code), seconds);
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
