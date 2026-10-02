package xyz.fokion.ivy.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.fokion.ivy.core.util.GoStrings;

/** Text-level YAML helpers ported from venom. */
final class PartialYaml {

    private static final Pattern TEMPLATE_VALUE = Pattern.compile("(?m)(^.*[^\"\\s]\\s*)(:\\s+)(\\{\\{.*?}})(.*?)(?:\\s*)$");

    private PartialYaml() {
    }

    /** venom's {@code readPartialYML}: the lines of one top-level key. */
    static String read(String content, String attribute) {
        List<String> result = new ArrayList<>();
        boolean record = false;
        for (String raw : content.split("\n", -1)) {
            String line = trimNonGraphic(raw);
            if (line.startsWith(attribute + ":")) {
                record = true;
            } else if (!line.isEmpty()) {
                char c = line.charAt(0);
                if (!GoStrings.isSpace(c) && !line.startsWith("-")) {
                    record = false;
                }
            }
            if (record) {
                result.add(line);
            }
        }
        return String.join("\n", result);
    }

    /** Trims leading and trailing runes that are not graphic ({@code unicode.IsGraphic}). */
    private static String trimNonGraphic(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && !isGraphic(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && !isGraphic(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    private static boolean isGraphic(int c) {
        return xyz.fokion.ivy.spi.util.GoFormat.isPrint(c) || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    /**
     * venom's {@code quoteTemplateExpressions}: quotes {@code key: {{...}}} values so that
     * remaining template expressions parse as YAML strings.
     */
    static String quoteTemplateExpressions(String content) {
        Matcher m = TEMPLATE_VALUE.matcher(content);
        return m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + r.group(2) + "\"" + r.group(3) + r.group(4) + "\""));
    }

    /** venom's {@code escapeQuotes}: Go-quotes values containing a double quote, without the outer quotes. */
    static String escapeQuotes(String s) {
        if (s.contains("\"")) {
            return GoStrings.quoteInner(s);
        }
        return s;
    }
}
