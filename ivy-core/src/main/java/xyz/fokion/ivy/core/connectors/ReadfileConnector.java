package xyz.fokion.ivy.core.connectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
import xyz.fokion.ivy.spi.util.LazyJson;

/**
 * Reads files matching a path or a glob. Errors are reported in {@code result.error} rather than
 * failing the step.
 * <p>
 * Result: {@code files} (each with {@code path}, {@code content}, {@code json}, {@code md5},
 * {@code size}, {@code modTime} and {@code mode}), {@code content} (all files),
 * {@code json} (the content parsed, YAML files included), {@code durationMs} and {@code error}.
 */
@ConnectorClass(type = "readfile", configurationClass = ReadfileConfiguration.class)
public final class ReadfileConnector implements Connector<ReadfileConfiguration>, DefaultAssertionsProvider {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "files", "each file read: path, content, json, md5, size, modTime, mode",
                "content", "the content of all files, concatenated",
                "json", "the content parsed as JSON or YAML",
                "durationMs", "the duration in milliseconds",
                "error", "why reading failed, empty otherwise");
    }

    @Override
    public List<Object> defaultAssertions() {
        return List.of("isEmpty(result.error)");
    }

    @Override
    public Object run(ReadfileConfiguration config, StepContext context) {
        long start = System.nanoTime();
        List<Object> files = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        String error = "";
        try {
            read(config.getPath(), context, files, content);
        } catch (ReadException e) {
            error = e.getMessage();
        }
        String all = content.toString();
        return Struct.builder("Result")
                .put("files", files)
                .put("content", all)
                .put("json", json(config.getPath(), all, context))
                .put("durationMs", (System.nanoTime() - start) / 1_000_000)
                .put("error", error)
                .build();
    }

    private static final class ReadException extends Exception {
        ReadException(String message) {
            super(message);
        }
    }

    private static void read(String path, StepContext context, List<Object> files, StringBuilder content)
            throws ReadException {
        Path wd = context.workdir();
        String absPath = Path.of(path).isAbsolute() ? path : wd.resolve(path).toString();
        if (Files.isDirectory(Path.of(absPath))) {
            absPath = Path.of(absPath).getParent().toString();
        }
        List<Path> matched;
        try {
            matched = SuiteFiles.glob(absPath);
        } catch (Exception e) {
            throw new ReadException("Error reading files on path:" + absPath + " :file does not exist");
        }
        if (matched.isEmpty()) {
            throw new ReadException("Invalid path '" + absPath + "' or file not found");
        }
        for (Path f : matched) {
            String relative = wd.relativize(f.toAbsolutePath()).toString();
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(f);
            } catch (IOException e) {
                throw new ReadException("error while opening file: " + e.getMessage());
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            content.append(text);
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", relative);
            file.put("content", text);
            file.put("json", json(relative, text, context));
            file.put("md5", md5(bytes));
            try {
                file.put("size", Files.size(f));
                file.put("modTime", Files.getLastModifiedTime(f).toInstant().toEpochMilli());
                file.put("mode", mode(f));
            } catch (IOException e) {
                throw new ReadException("error while reading the attributes of " + relative + ": " + e.getMessage());
            }
            files.add(file);
        }
    }

    /** The content parsed when the first time it is read: YAML files as YAML, others as JSON. */
    private static Object json(String path, String text, StepContext context) {
        if (path.endsWith(".yaml") || path.endsWith(".yml")) {
            try {
                return Yaml.load(text);
            } catch (Yaml.YamlException e) {
                context.log(StepContext.Level.WARN, "could not read " + path + " as YAML: " + e.getMessage());
                return null;
            }
        }
        return LazyJson.of(text);
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
