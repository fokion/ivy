package xyz.fokion.ivy.core.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.fokion.ivy.core.template.GoTemplate.TemplateException;
import xyz.fokion.ivy.core.template.GoTemplate.Val;

/**
 * Port of venom's {@code interpolate.Do}: renders {@code {{ }}} expressions against flat
 * {@code a.b.c} variables, leaving expressions that reference unknown variables or helpers as
 * they are (unless they use {@code default} or {@code ternary}).
 */
public final class Interpolator {

    private static final Pattern EXPRESSION = Pattern.compile("(\\{\\{[.\"a-zA-Z0-9._\\-µ|\\s]+}})");
    private static final String DASH = "µµµ";

    private Interpolator() {
    }

    public static final class InterpolationException extends Exception {
        public InterpolationException(String message) {
            super(message);
        }
    }

    public static String interpolate(String input, Map<String, String> vars) throws InterpolationException {
        if (!input.contains("{{")) {
            return input;
        }
        Val data = new Val();
        Map<String, String> flatData = new HashMap<>(vars.size());

        // replace the deepest variables first
        List<String> keys = new ArrayList<>(vars.keySet());
        keys.sort((a, b) -> Integer.compare(dots(b), dots(a)));

        List<String[]> replacements = new ArrayList<>();
        boolean dashes = false;
        for (String k : keys) {
            String kb = k.replace("-", DASH);
            dashes |= !kb.equals(k);
            String[] tokens = kb.split("\\.", -1);
            Val tmp = data;
            for (int i = 0; i < tokens.length - 1; i++) {
                Object child = tmp.children().get(tokens[i]);
                if (!(child instanceof Val v)) {
                    Val created = new Val();
                    if (child != null) {
                        created.setLeaf(child);
                    }
                    tmp.children().put(tokens[i], created);
                    tmp = created;
                } else {
                    tmp = v;
                }
            }
            String last = tokens[tokens.length - 1];
            Object existing = tmp.children().get(last);
            if (existing instanceof Val v) {
                // manages {{.cds.env.lb.prefix}}.{{.cds.env.lb}}
                v.setLeaf(vars.get(k));
            } else {
                tmp.children().put(last, vars.get(k));
            }
            flatData.put(kb, vars.get(k));
            replacements.add(new String[] {"." + k + " ", "." + kb + " "});
            replacements.add(new String[] {"." + k + "}", "." + kb + "}"});
            replacements.add(new String[] {"." + k + "|", "." + kb + "|"});
        }
        if (dashes) {
            input = replaceAll(input, replacements);
        }

        Set<String> processed = new HashSet<>();
        Matcher m = EXPRESSION.matcher(input);
        List<String> matches = new ArrayList<>();
        while (m.find()) {
            matches.add(m.group(1));
        }
        for (String match : matches) {
            String current = match;
            String expression = current.strip();
            if (!processed.add(expression)) {
                continue;
            }
            List<String> quotedStuff = new ArrayList<>();
            String trimmed = expression;
            if (trimmed.startsWith("{{")) {
                trimmed = trimmed.substring(2);
            }
            if (trimmed.endsWith("}}")) {
                trimmed = trimmed.substring(0, trimmed.length() - 2);
            }
            String[] split = trimmed.split(" ", -1);
            if (split.length == 1) {
                split = trimmed.split("\\|", -1);
            }
            Set<String> usedVariables = new LinkedHashSet<>();
            Set<String> usedHelpers = new LinkedHashSet<>();
            for (int i = 0; i < split.length; i++) {
                split[i] = split[i].strip();
                String s = split[i];
                if (s.isEmpty()) {
                    continue;
                }
                char c = s.charAt(0);
                if (c == '.') {
                    usedVariables.add(s.substring(1));
                } else if (c >= '0' && c <= '9') {
                    for (int j = i; j < split.length; j++) {
                        quotedStuff.add(split[j]);
                    }
                } else if (c == '"') {
                    String q = s.startsWith("\"") ? s.substring(1) : s;
                    q = q.endsWith("\"") ? q.substring(0, q.length() - 1) : q;
                    quotedStuff.add(q);
                } else if (c != '|') {
                    usedHelpers.add(s);
                }
            }
            boolean defaultIsUsed = usedHelpers.contains("default") || usedHelpers.contains("ternary");
            boolean unknownVariables = usedVariables.stream().anyMatch(v -> !flatData.containsKey(v));
            boolean unknownHelpers = usedHelpers.stream().anyMatch(h -> !Helpers.FUNCTIONS.containsKey(h));
            if (!defaultIsUsed && (unknownVariables || unknownHelpers)) {
                for (String s : quotedStuff) {
                    String q = current.replace("\"" + s + "\"", "\\\"" + s + "\\\"");
                    input = replaceFirst(input, current, q);
                    current = q;
                }
                input = input.replace(current, "{{\"" + current + "\"}}");
            }
        }

        try {
            GoTemplate t = GoTemplate.parse("input", input, Helpers.FUNCTIONS);
            try {
                return t.execute(data);
            } catch (TemplateException e) {
                throw new InterpolationException("failed to execute template: " + e.getMessage());
            }
        } catch (TemplateException e) {
            throw new InterpolationException("invalid template format \"" + input + "\": " + e.getMessage());
        }
    }

    private static int dots(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '.') {
                n++;
            }
        }
        return n;
    }

    private static String replaceFirst(String s, String target, String replacement) {
        int i = s.indexOf(target);
        if (i < 0) {
            return s;
        }
        return s.substring(0, i) + replacement + s.substring(i + target.length());
    }

    /**
     * Go's {@code strings.NewReplacer}: at each position the first pair, in argument order,
     * whose old string matches is applied; matches never overlap.
     */
    static String replaceAll(String input, List<String[]> pairs) {
        // every old string starts with '.', index them by the following character
        Map<Character, List<String[]>> byChar = new HashMap<>();
        for (String[] p : pairs) {
            if (p[0].length() > 1) {
                byChar.computeIfAbsent(p[0].charAt(1), c -> new ArrayList<>()).add(p);
            }
        }
        StringBuilder sb = new StringBuilder(input.length());
        int i = 0;
        outer:
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '.' && i + 1 < input.length()) {
                List<String[]> candidates = byChar.get(input.charAt(i + 1));
                if (candidates != null) {
                    for (String[] p : candidates) {
                        if (input.startsWith(p[0], i)) {
                            sb.append(p[1]);
                            i += p[0].length();
                            continue outer;
                        }
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
