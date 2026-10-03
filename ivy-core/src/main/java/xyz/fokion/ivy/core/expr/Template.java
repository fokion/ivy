package xyz.fokion.ivy.core.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import xyz.fokion.ivy.spi.util.Json;

/**
 * Text with {@code ${expression}} parts, such as {@code ${base_url}/users/${user.id}}.
 * {@code $${} writes a literal {@code ${}. A text that is a single {@code ${expression}}
 * renders to the value itself (a list, a number...), not to its text.
 */
public final class Template {

    private static final int CACHE_LIMIT = 10_000;
    private static final Map<String, Template> CACHE = new ConcurrentHashMap<>();

    /** A literal text or an expression. */
    private record Part(String text, Expression expression) {
    }

    private final String source;
    private final List<Part> parts;

    private Template(String source, List<Part> parts) {
        this.source = source;
        this.parts = parts;
    }

    /** Whether a text has anything to render. */
    public static boolean has(String text) {
        return text != null && text.contains("${");
    }

    /** Parses a template; throws {@link ExprException} on a syntax error. */
    public static Template compile(String source) {
        Template t = CACHE.get(source);
        if (t != null) {
            return t;
        }
        t = new Template(source, parse(source));
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(source, t);
        return t;
    }

    private static List<Part> parse(String s) {
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("$${", i)) {
                literal.append("${");
                i += 3;
                continue;
            }
            if (!s.startsWith("${", i)) {
                literal.append(s.charAt(i));
                i++;
                continue;
            }
            int end = closingBrace(s, i + 2);
            if (end < 0) {
                throw new ExprException("unclosed ${ in template", s, i);
            }
            String code = s.substring(i + 2, end);
            Expression e;
            try {
                e = Expression.compile(code.strip());
            } catch (ExprException ex) {
                throw new ExprException(ex.getMessage().replace(" in `" + code.strip() + "`", "") + " of ${" + code + "}",
                        s, i);
            }
            if (!literal.isEmpty()) {
                parts.add(new Part(literal.toString(), null));
                literal.setLength(0);
            }
            parts.add(new Part(null, e));
            i = end + 1;
        }
        if (!literal.isEmpty()) {
            parts.add(new Part(literal.toString(), null));
        }
        return parts;
    }

    /** The index of the brace closing an expression, skipping strings and nested braces. */
    private static int closingBrace(String s, int from) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < s.length() && s.charAt(i) != c; i++) {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    public String source() {
        return source;
    }

    /** The value: the expression's value for a single {@code ${...}}, else the rendered text. */
    public Object render(Scope scope) {
        if (parts.size() == 1 && parts.getFirst().expression() != null) {
            return evaluate(parts.getFirst().expression(), scope);
        }
        return renderString(scope);
    }

    public String renderString(Scope scope) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p.expression() == null) {
                sb.append(p.text());
            } else {
                sb.append(Values.display(evaluate(p.expression(), scope)));
            }
        }
        return sb.toString();
    }

    /**
     * The text with each {@code ${...}} replaced by its value written as a literal (strings
     * quoted), for templates inside expressions: {@code result.name == ${name}}.
     */
    public String renderLiterals(Scope scope) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p.expression() == null) {
                sb.append(p.text());
            } else {
                Object v = Values.unwrap(evaluate(p.expression(), scope));
                sb.append(v instanceof Double d ? Values.formatDouble(d) : Json.write(v, Json.COMPACT));
            }
        }
        return sb.toString();
    }

    private Object evaluate(Expression e, Scope scope) {
        try {
            return e.evaluate(scope);
        } catch (ExprException ex) {
            String message = ex.getMessage();
            if (message.startsWith("unknown variable ")) {
                String name = message.substring("unknown variable ".length()).split(" ", 2)[0];
                if (name.chars().allMatch(c -> Character.isUpperCase(c) || Character.isDigit(c) || c == '_')) {
                    message += " (to pass ${" + name + "} to a shell, write $${" + name + "})";
                }
            }
            throw new ExprException(message + " in template \"" + source + "\"");
        }
    }

    /** The variables the template reads. */
    public Set<String> roots() {
        Set<String> out = new LinkedHashSet<>();
        for (Part p : parts) {
            if (p.expression() != null) {
                out.addAll(p.expression().roots());
            }
        }
        return out;
    }

    /** The fields its expressions read directly on a variable (see {@link Expression#members}). */
    public Set<String> members(String rootName) {
        Set<String> out = new LinkedHashSet<>();
        for (Part p : parts) {
            if (p.expression() != null) {
                out.addAll(p.expression().members(rootName));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ trees

    /**
     * Renders the templates of a parsed YAML value: strings with {@code ${} are rendered, maps
     * and lists are rebuilt only where something changed.
     */
    public static Object interpolate(Object value, Scope scope) {
        return switch (value) {
            case String s when has(s) -> compile(s).render(scope);
            case Map<?, ?> m -> {
                Map<String, Object> out = null;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Object v = interpolate(e.getValue(), scope);
                    if (v != e.getValue() && out == null) {
                        out = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> before : m.entrySet()) {
                            if (before.getKey() == e.getKey()) {
                                break;
                            }
                            out.put(String.valueOf(before.getKey()), before.getValue());
                        }
                    }
                    if (out != null) {
                        out.put(String.valueOf(e.getKey()), v);
                    }
                }
                yield out == null ? m : out;
            }
            case List<?> l -> {
                List<Object> out = null;
                for (int i = 0; i < l.size(); i++) {
                    Object v = interpolate(l.get(i), scope);
                    if (v != l.get(i) && out == null) {
                        out = new ArrayList<>(l.subList(0, i));
                    }
                    if (out != null) {
                        out.add(v);
                    }
                }
                yield out == null ? l : out;
            }
            case null, default -> value;
        };
    }

    /**
     * Renders a map of variables in order, each value seeing the ones before it, as in
     * {@code with: {n: "${arg1 - 1}", link: "row >> nth=${n}"}}. A name that is not defined yet
     * reads the enclosing scope, so {@code user: "${user}-x"} extends an outer {@code user}.
     */
    public static Map<String, Object> interpolateInOrder(Map<?, ?> vars, Scope scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        Scope inner = scope.child(out);
        for (Map.Entry<?, ?> e : vars.entrySet()) {
            out.put(String.valueOf(e.getKey()), interpolate(e.getValue(), inner));
        }
        return out;
    }

    /** Adds the variables read by the templates of a parsed YAML value. */
    public static void collectRoots(Object value, Set<String> out) {
        switch (value) {
            case String s when has(s) -> out.addAll(compile(s).roots());
            case Map<?, ?> m -> m.values().forEach(v -> collectRoots(v, out));
            case List<?> l -> l.forEach(v -> collectRoots(v, out));
            case null, default -> {
            }
        }
    }
}
