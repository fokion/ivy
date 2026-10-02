package xyz.fokion.ivy.core.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import xyz.fokion.ivy.core.engine.Ivy.IvyException;

/**
 * Finds suite files: directories contribute their {@code *.yml}/{@code *.yaml} files, patterns
 * support {@code *}, {@code ?}, {@code [...]}, {@code {a,b}} and {@code **} (any depth, including
 * none) like {@code mattn/go-zglob}.
 */
public final class SuiteFiles {

    private SuiteFiles() {
    }

    public static List<Path> find(List<String> paths) throws IvyException {
        Set<Path> files = new LinkedHashSet<>();
        for (String raw : paths) {
            String p = raw.strip();
            if (Files.isDirectory(Path.of(p))) {
                p = p + "/*.y*ml";
            }
            for (Path f : glob(p)) {
                String name = f.getFileName().toString();
                if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                    files.add(f);
                }
            }
        }
        if (files.isEmpty()) {
            throw new IvyException("no YAML (*.yml or *.yaml) file found or defined");
        }
        return new ArrayList<>(files);
    }

    public static List<Path> glob(String pattern) throws IvyException {
        String normalized = pattern.replace('\\', '/');
        if (!hasMeta(normalized)) {
            Path p = Path.of(pattern);
            if (!Files.exists(p)) {
                throw new IvyException("error reading files on path \"" + pattern + "\": file does not exist");
            }
            return List.of(p);
        }
        // walk from the longest directory prefix without wildcards
        String[] segments = normalized.split("/", -1);
        StringBuilder base = new StringBuilder();
        int i = 0;
        for (; i < segments.length - 1 && !hasMeta(segments[i]); i++) {
            base.append(segments[i]).append('/');
        }
        String baseDir = base.length() == 0 ? "" : base.toString();
        Path root = baseDir.isEmpty() ? Path.of(".") : Path.of(baseDir);
        Pattern regex = Pattern.compile(toRegex(normalized));
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            throw new IvyException("error reading files on path \"" + pattern + "\": file does not exist");
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                String rel = root.relativize(f).toString().replace('\\', '/');
                String candidate = baseDir + rel;
                if (regex.matcher(candidate).matches()) {
                    out.add(Path.of(candidate));
                }
            });
        } catch (IOException e) {
            throw new IvyException("error reading files on path \"" + pattern + "\": " + e.getMessage(), e);
        }
        if (out.isEmpty()) {
            throw new IvyException("error reading files on path \"" + pattern + "\": file does not exist");
        }
        out.sort(null);
        return out;
    }

    private static boolean hasMeta(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0 || s.indexOf('{') >= 0;
    }

    static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int braces = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                            sb.append("(?:.*/)?");
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '[' -> {
                    int end = glob.indexOf(']', i + 1);
                    if (end < 0) {
                        sb.append("\\[");
                    } else {
                        String body = glob.substring(i + 1, end);
                        if (body.startsWith("!") || body.startsWith("^")) {
                            body = "^" + body.substring(1);
                        }
                        sb.append('[').append(body.replace("\\", "\\\\")).append(']');
                        i = end;
                    }
                }
                case '{' -> {
                    braces++;
                    sb.append("(?:");
                }
                case '}' -> {
                    if (braces > 0) {
                        braces--;
                        sb.append(')');
                    } else {
                        sb.append("\\}");
                    }
                }
                case ',' -> sb.append(braces > 0 ? "|" : ",");
                default -> {
                    if ("\\.^$+()|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
