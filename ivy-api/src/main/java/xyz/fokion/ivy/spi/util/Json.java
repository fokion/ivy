package xyz.fokion.ivy.spi.util;

import java.math.BigDecimal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import xyz.fokion.ivy.spi.Struct;

/**
 * A small JSON reader and writer whose output matches Go's {@code encoding/json}.
 * <p>
 * Reading produces {@code LinkedHashMap}, {@code ArrayList}, {@code String}, {@code Long}
 * (integral literals that fit), {@code Double}, {@code Boolean} and {@code null}.
 */
public final class Json {

    /** Go's {@code json.Marshal}: compact, map keys sorted, HTML characters escaped. */
    public static final Options GO = new Options("", true, true);
    /** Go's {@code json.MarshalIndent(v, "", indent)}. */
    public static final Options GO_INDENT = new Options("  ", true, true);
    /** Compact output keeping map insertion order. */
    public static final Options COMPACT = new Options("", false, false);

    private Json() {
    }

    /**
     * @param maxString strings longer than this are cut, with a marker telling how much was left
     *        out; 0 keeps them whole
     */
    public record Options(String indent, boolean sortKeys, boolean escapeHtml, int maxString) {
        public Options(String indent, boolean sortKeys, boolean escapeHtml) {
            this(indent, sortKeys, escapeHtml, 0);
        }

        public Options withIndent(String indent) {
            return new Options(indent, sortKeys, escapeHtml, maxString);
        }

        public Options withMaxString(int max) {
            return new Options(indent, sortKeys, escapeHtml, max);
        }
    }

    public static String write(Object value) {
        return write(value, GO);
    }

    public static String write(Object value, Options options) {
        StringBuilder sb = new StringBuilder();
        new Writer(sb, options, UnaryOperator.identity()).value(value, 0);
        return sb.toString();
    }

    /**
     * Streams a value to {@code out}, without building the document in memory; {@code strings}
     * rewrites every string before it is written (to hide secrets, for instance).
     */
    public static void write(Object value, Options options, Appendable out, UnaryOperator<String> strings) {
        new Writer(out, options, strings).value(value, 0);
    }

    public static Object parse(String json) {
        Reader r = new Reader(json);
        r.skipWs();
        Object v = r.value();
        r.skipWs();
        if (r.pos < json.length()) {
            throw r.error("invalid character '" + json.charAt(r.pos) + "' after top-level value");
        }
        return v;
    }

    /** Parses, returning {@code null} instead of throwing on invalid input. */
    public static Object tryParse(String json) {
        try {
            return parse(json);
        } catch (JsonException e) {
            return null;
        }
    }

    public static boolean isValid(String json) {
        try {
            parse(json);
            return true;
        } catch (JsonException e) {
            return false;
        }
    }

    /** Escapes a string as a JSON string literal, with quotes. */
    public static String quote(String s, boolean escapeHtml) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        appendString(sb, s, escapeHtml);
        return sb.toString();
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static void appendString(StringBuilder sb, String s, boolean escapeHtml) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20 || (escapeHtml && (c == '<' || c == '>' || c == '&'))) {
                        sb.append("\\u00").append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private record Writer(Appendable out, Options options, UnaryOperator<String> strings) {

        private void append(CharSequence s) {
            try {
                out.append(s);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void append(char c) {
            try {
                out.append(c);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void string(String s, boolean escapeHtml) {
            String v = strings.apply(s);
            int max = options.maxString();
            if (max > 0 && v.length() > max) {
                v = v.substring(0, max) + "… " + (v.length() - max) + " more characters";
            }
            StringBuilder sb = new StringBuilder(v.length() + 2);
            appendString(sb, v, escapeHtml);
            append(sb);
        }

        void value(Object v, int depth) {
            switch (v) {
                case null -> append("null");
                case String s -> string(s, options.escapeHtml());
                case Character c -> string(c.toString(), options.escapeHtml());
                case Boolean b -> append(b.toString());
                case Double d -> append(GoFormat.formatFloatJson(d));
                case Float f -> append(GoFormat.formatFloatJson(f.doubleValue()));
                case BigDecimal bd -> append(bd.toPlainString());
                case BigInteger bi -> append(bi.toString());
                case Number n -> append(Long.toString(n.longValue()));
                case byte[] bytes -> string(Base64.getEncoder().encodeToString(bytes), false);
                case LazyJson j -> value(j.value(), depth);
                case Struct s -> object(s.fields(), false, depth);
                case Map<?, ?> m -> object(m, options.sortKeys(), depth);
                case Collection<?> c -> array(c, depth);
                case Object[] a -> array(java.util.Arrays.asList(a), depth);
                case TemporalAccessor t -> string(t.toString(), false);
                default -> string(v.toString(), options.escapeHtml());
            }
        }

        private void object(Map<?, ?> m, boolean sort, int depth) {
            if (m.isEmpty()) {
                append("{}");
                return;
            }
            Iterable<? extends Map.Entry<?, ?>> entries = sort ? GoFormat.sortedEntries(m).entrySet() : m.entrySet();
            append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : entries) {
                if (!first) {
                    append(',');
                }
                first = false;
                newline(depth + 1);
                StringBuilder key = new StringBuilder();
                appendString(key, String.valueOf(e.getKey()), options.escapeHtml());
                append(key);
                append(':');
                if (!options.indent().isEmpty()) {
                    append(' ');
                }
                value(e.getValue(), depth + 1);
            }
            newline(depth);
            append('}');
        }

        private void array(Collection<?> c, int depth) {
            if (c.isEmpty()) {
                append("[]");
                return;
            }
            append('[');
            boolean first = true;
            for (Object e : c) {
                if (!first) {
                    append(',');
                }
                first = false;
                newline(depth + 1);
                value(e, depth + 1);
            }
            newline(depth);
            append(']');
        }

        private void newline(int depth) {
            if (options.indent().isEmpty()) {
                return;
            }
            append('\n');
            append(options.indent().repeat(depth));
        }
    }

    /** Deeper documents are rejected rather than overflowing the stack. */
    static final int MAX_DEPTH = 1000;

    private static final class Reader {
        private final String s;
        private int pos;
        private int depth;

        Reader(String s) {
            this.s = s;
        }

        JsonException error(String msg) {
            return new JsonException(msg + " (offset " + pos + ")");
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("unexpected end of JSON input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{', '[' -> {
                    if (++depth > MAX_DEPTH) {
                        throw error("exceeded max depth");
                    }
                    Object v = c == '{' ? object() : array();
                    depth--;
                    yield v;
                }
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("invalid character '" + c + "' looking for beginning of value");
                }
            };
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("invalid literal");
            }
            pos += word.length();
            return value;
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw error("invalid character looking for beginning of object key string");
                }
                String key = string();
                skipWs();
                if (peek() != ':') {
                    throw error("invalid character after object key");
                }
                pos++;
                skipWs();
                m.put(key, value());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("invalid character after object key:value pair");
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw error("invalid character after array element");
                }
            }
        }

        private char peek() {
            if (pos >= s.length()) {
                throw error("unexpected end of JSON input");
            }
            return s.charAt(pos);
        }

        private String string() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("invalid character in string literal");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = peek();
                pos++;
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        int cp = hex4();
                        if (Character.isHighSurrogate((char) cp) && s.startsWith("\\u", pos)) {
                            int save = pos;
                            pos += 2;
                            int low = hex4();
                            if (Character.isLowSurrogate((char) low)) {
                                sb.append((char) cp).append((char) low);
                                continue;
                            }
                            pos = save;
                        }
                        // a lone surrogate becomes U+FFFD, as in Go
                        sb.append(Character.isSurrogate((char) cp) ? '\uFFFD' : (char) cp);
                    }
                    default -> throw error("invalid escape in string literal");
                }
            }
        }

        private int hex4() {
            if (pos + 4 > s.length()) {
                throw error("invalid unicode escape");
            }
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos + i), 16);
                if (d < 0) {
                    throw error("invalid unicode escape");
                }
                v = v * 16 + d;
            }
            pos += 4;
            return v;
        }

        /** The JSON number grammar: -?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)? */
        private static final java.util.regex.Pattern NUMBER =
                java.util.regex.Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

        private Object number() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            boolean integral = true;
            int digitsStart = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            String text = s.substring(start, pos);
            if (pos == digitsStart || !NUMBER.matcher(text).matches()) {
                throw error("invalid number " + text);
            }
            try {
                if (integral) {
                    BigInteger bi = new BigInteger(text);
                    return bi.bitLength() < 64 ? (Object) bi.longValue() : bi.doubleValue();
                }
                double d = Double.parseDouble(text);
                if (Double.isInfinite(d)) {
                    throw error("number " + text + " out of range");
                }
                return d;
            } catch (NumberFormatException ex) {
                throw error("invalid number " + text);
            }
        }
    }
}
