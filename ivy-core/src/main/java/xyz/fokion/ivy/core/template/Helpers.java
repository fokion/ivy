package xyz.fokion.ivy.core.template;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import xyz.fokion.ivy.core.template.GoTemplate.Function;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.GoFormat;
import xyz.fokion.ivy.spi.util.Json;

/**
 * The template helpers available in venom suites (a subset of sprig).
 * <p>
 * Each helper declares the Go parameter types of the original function so that calls with too
 * few arguments fail the same way ({@code missing params (expected: int, string)}).
 */
public final class Helpers {

    /** Helper names, in a fixed order. */
    public static final Map<String, Function> FUNCTIONS;

    private Helpers() {
    }

    private interface Body {
        Object apply(Object[] a) throws Exception;
    }

    private static final String INT = "int";
    private static final String STRING = "string";
    private static final String ANY = "";

    static {
        Map<String, Function> f = new LinkedHashMap<>();
        def(f, "abbrev", a -> abbrev(i(a[0]), s(a[1])), INT, STRING);
        def(f, "abbrevboth", a -> abbrevBoth(i(a[0]), i(a[1]), s(a[2])), INT, INT, STRING);
        def(f, "trunc", a -> trunc(i(a[0]), s(a[1])), INT, STRING);
        def(f, "trim", a -> GoStrings.trimSpace(s(a[0])), STRING);
        def(f, "upper", a -> GoStrings.toUpper(s(a[0])), STRING);
        def(f, "lower", a -> GoStrings.toLower(s(a[0])), STRING);
        def(f, "title", a -> GoStrings.title(s(a[0])), STRING);
        def(f, "untitle", a -> untitle(s(a[0])), STRING);
        def(f, "substr", a -> substring(i(a[0]), i(a[1]), s(a[2])), INT, INT, STRING);
        def(f, "repeat", a -> s(a[1]).repeat(Math.max(0, i(a[0]))), INT, STRING);
        def(f, "trimall", a -> GoStrings.trim(s(a[1]), s(a[0])), STRING, STRING);
        def(f, "trimAll", a -> GoStrings.trim(s(a[1]), s(a[0])), STRING, STRING);
        def(f, "trimSuffix", a -> s(a[1]).endsWith(s(a[0])) ? s(a[1]).substring(0, s(a[1]).length() - s(a[0]).length()) : s(a[1]), STRING, STRING);
        def(f, "trimPrefix", a -> s(a[1]).startsWith(s(a[0])) ? s(a[1]).substring(s(a[0]).length()) : s(a[1]), STRING, STRING);
        def(f, "nospace", a -> nospace(s(a[0])), STRING);
        def(f, "initials", a -> initials(s(a[0])), STRING);
        def(f, "randAlphaNum", a -> random(i(a[0]), true, true), INT);
        def(f, "randAlpha", a -> random(i(a[0]), true, false), INT);
        def(f, "randASCII", a -> randomAscii(i(a[0])), INT);
        def(f, "randNumeric", a -> random(i(a[0]), false, true), INT);
        def(f, "swapcase", a -> swapCase(s(a[0])), STRING);
        def(f, "shuffle", a -> shuffle(s(a[0])), STRING);
        def(f, "snakecase", a -> CaseConversions.toSnakeCase(s(a[0])), STRING);
        def(f, "camelcase", a -> CaseConversions.toCamelCase(s(a[0])), STRING);
        variadic(f, "quote", a -> joinEach(a, x -> GoFormat.quote(strval(x))));
        variadic(f, "squote", a -> joinEach(a, x -> "'" + sprintNil(x) + "'"));
        def(f, "indent", a -> indent(i(a[0]), s(a[1])), INT, STRING);
        def(f, "nindent", a -> "\n" + indent(i(a[0]), s(a[1])), INT, STRING);
        def(f, "replace", a -> s(a[2]).replace(s(a[0]), s(a[1])), STRING, STRING, STRING);
        def(f, "plural", a -> i(a[2]) == 1 ? s(a[0]) : s(a[1]), STRING, STRING, INT);
        def(f, "toString", a -> strval(a[0]), ANY);
        variadic(f, "default", Helpers::dfault);
        def(f, "empty", a -> empty(a[0]), ANY);
        variadic(f, "coalesce", a -> {
            for (Object v : a) {
                if (!empty(v)) {
                    return v;
                }
            }
            return null;
        });
        def(f, "toJSON", a -> Json.write(a[0], Json.GO), ANY);
        def(f, "toPrettyJSON", a -> Json.write(a[0], Json.GO_INDENT), ANY);
        def(f, "b64enc", a -> Base64.getEncoder().encodeToString(s(a[0]).getBytes(StandardCharsets.UTF_8)), STRING);
        def(f, "b64dec", a -> base64Decode(s(a[0])), STRING);
        def(f, "escape", a -> s(a[0]).replace("_", "-").replace("/", "-").replace(".", "-"), STRING);
        def(f, "stringQuote", a -> GoStrings.quoteInner(s(a[0])), STRING);
        variadic(f, "add", a -> {
            long sum = 0;
            for (Object v : a) {
                sum += Cast.toLong(v);
            }
            return sum;
        });
        def(f, "sub", a -> Cast.toLong(a[0]) - Cast.toLong(a[1]), ANY, ANY);
        defVariadicTail(f, "mul", a -> {
            long v = Cast.toLong(a[0]);
            for (int k = 1; k < a.length; k++) {
                v *= Cast.toLong(a[k]);
            }
            return v;
        }, ANY, ANY);
        def(f, "div", a -> divide(Cast.toLong(a[0]), Cast.toLong(a[1]), false), ANY, ANY);
        def(f, "mod", a -> divide(Cast.toLong(a[0]), Cast.toLong(a[1]), true), ANY, ANY);
        def(f, "ternary", a -> Cast.toBool(a[2]) ? a[0] : a[1], ANY, ANY, ANY);
        def(f, "urlencode", a -> GoStrings.queryEscape(s(a[0])), STRING);
        def(f, "dirname", a -> GoStrings.dir(s(a[0])), STRING);
        def(f, "basename", a -> GoStrings.base(s(a[0])), STRING);
        FUNCTIONS = Collections.unmodifiableMap(f);
    }

    private static void def(Map<String, Function> f, String name, Body body, String... params) {
        f.put(name, args -> {
            checkParams(args, params);
            if (args.size() > params.length) {
                throw new IllegalArgumentException("reflect: Call with too many input arguments");
            }
            return body.apply(args.toArray());
        });
    }

    private static void defVariadicTail(Map<String, Function> f, String name, Body body, String... fixed) {
        f.put(name, args -> {
            checkParams(args, fixed);
            return body.apply(args.toArray());
        });
    }

    private static void variadic(Map<String, Function> f, String name, Body body) {
        f.put(name, args -> {
            checkParams(args, new String[] {ANY});
            return body.apply(args.toArray());
        });
    }

    private static void checkParams(List<Object> args, String[] params) {
        if (args.size() < params.length) {
            throw new IllegalArgumentException("missing params (expected: " + String.join(", ", params) + ")");
        }
    }

    private static String s(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String str) {
            return str;
        }
        return Cast.toString(v);
    }

    private static int i(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String str) {
            try {
                return (int) Cast.parseGoInt(str);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("wrong type for value; expected int; got string");
            }
        }
        throw new IllegalArgumentException("wrong type for value; expected int; got " + (v == null ? "<nil>" : v.getClass().getSimpleName()));
    }

    /** sprig's {@code strval}. */
    static String strval(Object v) {
        return switch (v) {
            case null -> "<nil>";
            case String s -> s;
            case byte[] b -> new String(b, StandardCharsets.UTF_8);
            case Throwable t -> t.getMessage();
            default -> GoFormat.sprint(v);
        };
    }

    private static String sprintNil(Object v) {
        return v == null ? "<nil>" : GoFormat.sprint(v);
    }

    private interface Each {
        String apply(Object v);
    }

    private static String joinEach(Object[] a, Each each) {
        List<String> out = new ArrayList<>();
        for (Object v : a) {
            out.add(each.apply(v));
        }
        return String.join(" ", out);
    }

    /** venom's {@code dfault}: the last non-empty string argument. */
    private static String dfault(Object[] a) {
        if (a.length == 0) {
            return "";
        }
        if (a.length == 1) {
            return a[0] instanceof String str ? str : "";
        }
        for (int k = a.length - 1; k >= 0; k--) {
            if (a[k] instanceof String str && !str.isEmpty()) {
                return str;
            }
        }
        return "";
    }

    static boolean empty(Object v) {
        return switch (v) {
            case null -> true;
            case String s -> s.isEmpty();
            case Collection<?> c -> c.isEmpty();
            case Map<?, ?> m -> m.isEmpty();
            case Boolean b -> !b;
            case Number n -> n.doubleValue() == 0;
            case Struct s -> false;
            default -> false;
        };
    }

    private static long divide(long a, long b, boolean mod) {
        if (b == 0) {
            throw new ArithmeticException("runtime error: integer divide by zero");
        }
        return mod ? a % b : a / b;
    }

    private static String abbrev(int width, String s) {
        if (width < 4) {
            return s;
        }
        return abbreviateFull(s, 0, width);
    }

    private static String abbrevBoth(int left, int right, String s) {
        if (right < 4 || left > 0 && right < 7) {
            return s;
        }
        return abbreviateFull(s, left, right);
    }

    /** goutils {@code AbbreviateFull}; argument errors yield "". */
    private static String abbreviateFull(String str, int offset, int maxWidth) {
        if (str.isEmpty() || maxWidth < 4) {
            return "";
        }
        if (str.length() <= maxWidth) {
            return str;
        }
        if (offset > str.length()) {
            offset = str.length();
        }
        if (str.length() - offset < maxWidth - 3) {
            offset = str.length() - (maxWidth - 3);
        }
        String marker = "...";
        if (offset <= 4) {
            return str.substring(0, maxWidth - 3) + marker;
        }
        if (maxWidth < 7) {
            return "";
        }
        if (offset + maxWidth - 3 < str.length()) {
            return marker + abbreviateFull(str.substring(offset), 0, maxWidth - 3);
        }
        return marker + str.substring(str.length() - (maxWidth - 3));
    }

    private static String trunc(int c, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= c) {
            return s;
        }
        return new String(bytes, 0, Math.max(0, c), StandardCharsets.UTF_8);
    }

    private static String substring(int start, int length, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        int from;
        int to;
        if (start < 0) {
            from = 0;
            to = length;
        } else if (length < 0) {
            from = start;
            to = b.length;
        } else {
            from = start;
            to = length;
        }
        if (from < 0 || to > b.length || from > to) {
            throw new IndexOutOfBoundsException("runtime error: slice bounds out of range [" + from + ":" + to + "]");
        }
        return new String(b, from, to - from, StandardCharsets.UTF_8);
    }

    private static String untitle(String s) {
        StringBuilder sb = new StringBuilder();
        boolean next = true;
        for (int k = 0; k < s.length(); ) {
            int c = s.codePointAt(k);
            if (GoStrings.isSpace(c)) {
                next = true;
                sb.appendCodePoint(c);
            } else if (next) {
                sb.appendCodePoint(Character.toLowerCase(c));
                next = false;
            } else {
                sb.appendCodePoint(c);
            }
            k += Character.charCount(c);
        }
        return sb.toString();
    }

    private static String nospace(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().filter(c -> !GoStrings.isSpace(c)).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static String initials(String s) {
        StringBuilder sb = new StringBuilder();
        boolean gap = true;
        for (char c : s.toCharArray()) {
            if (GoStrings.isSpace(c)) {
                gap = true;
            } else if (gap) {
                sb.append(c);
                gap = false;
            }
        }
        return sb.toString();
    }

    private static String swapCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean whitespace = true;
        for (int k = 0; k < s.length(); ) {
            int c = s.codePointAt(k);
            if (Character.isUpperCase(c) || Character.isTitleCase(c)) {
                sb.appendCodePoint(Character.toLowerCase(c));
                whitespace = false;
            } else if (Character.isLowerCase(c)) {
                sb.appendCodePoint(whitespace ? Character.toTitleCase(c) : Character.toUpperCase(c));
                whitespace = false;
            } else {
                sb.appendCodePoint(c);
                whitespace = GoStrings.isSpace(c);
            }
            k += Character.charCount(c);
        }
        return sb.toString();
    }

    private static String shuffle(String s) {
        List<Integer> runes = new ArrayList<>(s.codePoints().boxed().toList());
        Collections.shuffle(runes, ThreadLocalRandom.current());
        StringBuilder sb = new StringBuilder();
        runes.forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static String indent(int spaces, String v) {
        String pad = " ".repeat(Math.max(0, spaces));
        return pad + v.replace("\n", "\n" + pad);
    }

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";

    private static String random(int count, boolean letters, boolean numbers) {
        String alphabet = (letters ? LETTERS : "") + (numbers ? DIGITS : "");
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < count; k++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String randomAscii(int count) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < count; k++) {
            sb.append((char) (32 + ThreadLocalRandom.current().nextInt(95)));
        }
        return sb.toString();
    }

    private static String base64Decode(String v) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        try {
            return new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            int idx = 0;
            while (idx < v.length() && alphabet.indexOf(v.charAt(idx)) >= 0) {
                idx++;
            }
            return "illegal base64 data at input byte " + idx;
        }
    }
}
