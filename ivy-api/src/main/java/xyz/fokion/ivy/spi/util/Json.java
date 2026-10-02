package xyz.fokion.ivy.spi.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public record Options(String indent, boolean sortKeys, boolean escapeHtml) {
        public Options withIndent(String indent) {
            return new Options(indent, sortKeys, escapeHtml);
        }
    }

    public static String write(Object value) {
        return write(value, GO);
    }

    public static String write(Object value, Options options) {
        StringBuilder sb = new StringBuilder();
        new Writer(sb, options).value(value, 0);
        return sb.toString();
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

    private record Writer(StringBuilder sb, Options options) {

        void value(Object v, int depth) {
            switch (v) {
                case null -> sb.append("null");
                case String s -> appendString(sb, s, options.escapeHtml());
                case Character c -> appendString(sb, c.toString(), options.escapeHtml());
                case Boolean b -> sb.append(b);
                case Double d -> sb.append(GoFormat.formatFloatJson(d));
                case Float f -> sb.append(GoFormat.formatFloatJson(f.doubleValue()));
                case BigDecimal bd -> sb.append(bd.toPlainString());
                case BigInteger bi -> sb.append(bi);
                case Number n -> sb.append(n.longValue());
                case byte[] bytes -> appendString(sb, Base64.getEncoder().encodeToString(bytes), false);
                case Struct s -> object(s.fields(), false, depth);
                case Map<?, ?> m -> object(m, options.sortKeys(), depth);
                case Collection<?> c -> array(c, depth);
                case Object[] a -> array(List.of(a), depth);
                case TemporalAccessor t -> appendString(sb, t.toString(), false);
                default -> appendString(sb, v.toString(), options.escapeHtml());
            }
        }

        private void object(Map<?, ?> m, boolean sort, int depth) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            Map<String, Object> entries;
            if (sort) {
                entries = GoFormat.sortedEntries(m);
            } else {
                entries = new LinkedHashMap<>();
                m.forEach((k, val) -> entries.put(String.valueOf(k), val));
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(depth + 1);
                appendString(sb, e.getKey(), options.escapeHtml());
                sb.append(':');
                if (!options.indent().isEmpty()) {
                    sb.append(' ');
                }
                value(e.getValue(), depth + 1);
            }
            newline(depth);
            sb.append('}');
        }

        private void array(Collection<?> c, int depth) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object e : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(depth + 1);
                value(e, depth + 1);
            }
            newline(depth);
            sb.append(']');
        }

        private void newline(int depth) {
            if (options.indent().isEmpty()) {
                return;
            }
            sb.append('\n');
            sb.append(options.indent().repeat(depth));
        }
    }

    private static final class Reader {
        private final String s;
        private int pos;

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
                case '{' -> object();
                case '[' -> array();
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
                        if (pos + 4 > s.length()) {
                            throw error("invalid unicode escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("invalid unicode escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("invalid escape in string literal");
                }
            }
        }

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
            if (pos == digitsStart) {
                throw error("invalid number");
            }
            if (text.length() > 1 + (text.startsWith("-") ? 1 : 0)
                    && s.charAt(digitsStart) == '0' && Character.isDigit(s.charAt(digitsStart + 1))) {
                throw error("invalid number " + text);
            }
            try {
                if (integral) {
                    BigInteger bi = new BigInteger(text);
                    return bi.bitLength() < 64 ? (Object) bi.longValue() : bi.doubleValue();
                }
                return Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                throw error("invalid number " + text);
            }
        }
    }
}
