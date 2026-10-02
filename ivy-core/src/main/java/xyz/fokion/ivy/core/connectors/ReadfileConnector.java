package xyz.fokion.ivy.core.connectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.engine.SuiteFiles;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.DefaultAssertionsProvider;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.ZeroValueResultProvider;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Reads files, port of venom's {@code readfile} executor. Errors are reported in
 * {@code result.err} rather than failing the step.
 */
@ConnectorClass(type = "readfile", configurationClass = ReadfileConfiguration.class)
public final class ReadfileConnector implements Connector<ReadfileConfiguration>, DefaultAssertionsProvider,
        ZeroValueResultProvider {

    @Override
    public List<Object> defaultAssertions() {
        return List.of("result.err ShouldBeEmpty");
    }

    @Override
    public Object zeroValueResult() {
        return new ReadResult(null).result(0);
    }

    @Override
    public Object run(ReadfileConfiguration config, StepContext context) {
        long start = System.nanoTime();
        ReadResult files = new ReadResult(null);
        try {
            files = read(config.getPath(), context);
        } catch (ReadException e) {
            files = e.partial;
            files.err = e.getMessage();
        }
        return files.result((System.nanoTime() - start) / 1e9);
    }

    /** The accumulated result. */
    private static final class ReadResult {
        String content = "";
        Object contentJson;
        String err = "";
        final Map<String, String> md5sum = new LinkedHashMap<>();
        final Map<String, Long> size = new LinkedHashMap<>();
        final Map<String, Long> modtime = new LinkedHashMap<>();
        final Map<String, String> mod = new LinkedHashMap<>();

        ReadResult(Object contentJson) {
            this.contentJson = contentJson;
        }

        Struct result(double seconds) {
            return Struct.builder("Result")
                    .put("content", content)
                    .put("contentjson", contentJson)
                    .put("err", err)
                    .put("timeseconds", seconds)
                    .put("md5sum", md5sum)
                    .put("size", size)
                    .put("modtime", modtime)
                    .put("mod", mod)
                    .build();
        }
    }

    private static final class ReadException extends Exception {
        final transient ReadResult partial;

        ReadException(String message, ReadResult partial) {
            super(message);
            this.partial = partial;
        }
    }

    private static ReadResult read(String path, StepContext context) throws ReadException {
        ReadResult r = new ReadResult(null);
        String workdir = context.var("venom.testsuite.workdir");
        Path wd = workdir == null || workdir.isEmpty() ? Path.of("").toAbsolutePath() : Path.of(workdir);
        String absPath = Path.of(path).isAbsolute() ? path : wd.resolve(path).toString();
        if (Files.isDirectory(Path.of(absPath))) {
            absPath = Path.of(absPath).getParent().toString();
        }
        List<Path> files;
        try {
            files = SuiteFiles.glob(absPath);
        } catch (Exception e) {
            throw new ReadException("Error reading files on path:" + absPath + " :file does not exist", r);
        }
        if (files.isEmpty()) {
            throw new ReadException("Invalid path '" + absPath + "' or file not found", r);
        }
        StringBuilder content = new StringBuilder();
        for (Path f : files) {
            String relative = wd.relativize(f.toAbsolutePath()).toString();
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(f);
            } catch (IOException e) {
                throw new ReadException("error while opening file: " + e.getMessage(), r);
            }
            content.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            r.md5sum.put(relative, md5(bytes));
            try {
                r.size.put(relative, Files.size(f));
                r.modtime.put(relative, Files.getLastModifiedTime(f).toInstant().getEpochSecond());
                r.mod.put(relative, mode(f));
            } catch (IOException e) {
                throw new ReadException("error while compute file size: " + e.getMessage(), r);
            }
        }
        r.content = content.toString();
        r.contentJson = List.of();
        String json = r.content;
        if (path.endsWith("yaml") || path.endsWith("yml")) {
            context.log(StepContext.Level.DEBUG, "trying to parse yaml file");
            try {
                json = Yaml.toJson(Yaml.load(r.content));
            } catch (Yaml.YamlException e) {
                context.log(StepContext.Level.WARN, "could not convert payload from file");
                return r;
            }
        }
        Object parsed = Json.tryParse(json);
        if (parsed != null) {
            r.contentJson = parsed;
        }
        return r;
    }

    private static String md5(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Go's {@code FileMode.String()} for regular files. */
    private static String mode(Path f) {
        try {
            return "-" + PosixFilePermissions.toString(Files.getPosixFilePermissions(f));
        } catch (IOException | UnsupportedOperationException e) {
            return "-rw-rw-rw-";
        }
    }
}
