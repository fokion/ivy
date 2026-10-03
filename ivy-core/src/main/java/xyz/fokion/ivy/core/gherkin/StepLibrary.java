package xyz.fokion.ivy.core.gherkin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.yaml.Yaml;
import xyz.fokion.ivy.core.yaml.Yaml.YamlException;

/**
 * Step definitions, from {@code *.steps.yml} files:
 *
 * <pre>
 * steps:
 *   - match: 'I GET "(?&lt;url&gt;[^"]+)"'          # a regex matching the whole step text
 *     step: { type: http, method: GET, url: "${base_url}${url}" }
 *   - expression: 'the status is {int}'        # a Cucumber expression: {int} {float} {word} {string} {}
 *     assertions: [ "result.status == arg1" ]
 * </pre>
 *
 * Captured values are variables of the step: named groups by name, every group as {@code arg1},
 * {@code arg2}..., a doc string as {@code docstring}, a data table as {@code table} (rows of
 * cells) and {@code rows} (objects keyed by the header row). A definition with {@code step} adds
 * a step; one with only {@code assertions} adds them to the previous step.
 */
public final class StepLibrary {

    /**
     * One definition; {@code step} is null for an assertions-only definition. {@code quoted}
     * tells, per capturing group, whether the value is a quoted {@code {string}} to unquote;
     * {@code text} is the expression or regex as written.
     */
    public record Definition(String source, Pattern pattern, List<Boolean> quoted, Map<String, Object> step,
            List<Object> assertions, String text) {
    }

    /** A Cucumber expression as a regex, with the {@code {string}} groups. */
    record Translated(String regex, List<Boolean> quoted) {
    }

    /** A step text matched by a definition, with its captured values. */
    public record Match(Definition definition, Map<String, String> args) {
    }

    private final List<Definition> definitions = new ArrayList<>();

    /** Loads every {@code *.steps.yml} under the directories that exist. */
    public static StepLibrary load(List<Path> dirs) throws IOException, YamlException {
        StepLibrary lib = new StepLibrary();
        Set<Path> files = new TreeSet<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(f -> f.getFileName().toString().endsWith(".steps.yml")
                        || f.getFileName().toString().endsWith(".steps.yaml")).forEach(f -> files.add(f.toAbsolutePath().normalize()));
            }
        }
        for (Path f : files) {
            lib.add(f.toString(), Files.readString(f));
        }
        return lib;
    }

    /** Adds the definitions of one file. */
    @SuppressWarnings("unchecked")
    public void add(String source, String yaml) throws YamlException {
        Object steps = Yaml.loadMap(yaml).get("steps");
        if (!(steps instanceof List<?> list)) {
            throw new YamlException(source + ": a 'steps' list is expected", null);
        }
        for (Object o : list) {
            Map<String, Object> d = Cast.toStringMap(o);
            String regex;
            List<Boolean> quoted = List.of();
            if (d.containsKey("match")) {
                regex = Cast.toString(d.get("match"));
            } else if (d.containsKey("expression")) {
                Translated t = fromExpression(Cast.toString(d.get("expression")));
                regex = t.regex();
                quoted = t.quoted();
            } else {
                throw new YamlException(source + ": a definition needs 'match' or 'expression'", null);
            }
            Map<String, Object> step = d.get("step") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            List<Object> assertions = d.get("assertions") instanceof List<?> l ? new ArrayList<>(l) : List.of();
            if (step == null && assertions.isEmpty()) {
                throw new YamlException(source + ": definition '" + regex + "' has neither 'step' nor 'assertions'", null);
            }
            String text = Cast.toString(d.containsKey("expression") ? d.get("expression") : d.get("match"));
            definitions.add(new Definition(source, Pattern.compile(regex.replace("(?P<", "(?<")), quoted, step, assertions,
                    text));
        }
    }

    /** The definitions, in the order of their files. */
    public List<Definition> definitions() {
        return List.copyOf(definitions);
    }

    /** Translates a Cucumber expression into a regex. */
    static Translated fromExpression(String expression) {
        StringBuilder sb = new StringBuilder();
        List<Boolean> quoted = new ArrayList<>();
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '{') {
                int end = expression.indexOf('}', i);
                if (end < 0) {
                    sb.append("\\{");
                    continue;
                }
                String type = expression.substring(i + 1, end);
                sb.append(switch (type) {
                    case "int" -> "(-?\\d+)";
                    case "float" -> "(-?\\d*\\.?\\d+)";
                    case "word" -> "([^\\s]+)";
                    case "string" -> "(\"[^\"]*\"|'[^']*')";
                    case "" -> "(.*)";
                    default -> throw new IllegalArgumentException("unknown parameter type {" + type + "} in \"" + expression + "\"");
                });
                quoted.add(type.equals("string"));
                i = end;
            } else if (c == '(') {
                // optional text: "cucumber(s)"
                int end = expression.indexOf(')', i);
                if (end < 0) {
                    sb.append("\\(");
                    continue;
                }
                sb.append("(?:").append(Pattern.quote(expression.substring(i + 1, end))).append(")?");
                i = end;
            } else if ("\\.[]^$|?*+".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return new Translated(sb.toString(), quoted);
    }

    private static final Pattern GROUP_NAME = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

    /** The definitions matching a step text; more than one is ambiguous. */
    public List<Match> find(String text) {
        List<Match> matches = new ArrayList<>();
        for (Definition d : definitions) {
            Matcher m = d.pattern().matcher(text);
            if (!m.matches()) {
                continue;
            }
            Map<String, String> args = new LinkedHashMap<>();
            for (int g = 1; g <= m.groupCount(); g++) {
                String value = m.group(g) == null ? "" : m.group(g);
                if (g - 1 < d.quoted().size() && d.quoted().get(g - 1) && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                args.put("arg" + g, value);
            }
            Matcher names = GROUP_NAME.matcher(d.pattern().pattern());
            while (names.find()) {
                String value = m.group(names.group(1));
                args.put(names.group(1), value == null ? "" : value);
            }
            matches.add(new Match(d, args));
        }
        return matches;
    }

    /** A definition to start from, for a step nothing matches. */
    public static String snippet(String text) {
        String expression = text.replaceAll("\"[^\"]*\"", "{string}").replaceAll("(?<![\\w.])-?\\d+(?![\\w.])", "{int}");
        return "- expression: '" + expression.replace("'", "''") + "'\n  step: { script: echo TODO }";
    }
}
