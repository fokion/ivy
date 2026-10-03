package xyz.fokion.ivy.spi.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import xyz.fokion.ivy.spi.Struct;

/**
 * Formats values the way Go's {@code fmt} and {@code strconv} packages do, so that reports and
 * interpolated values stay identical to Go programs'.
 */
public final class GoFormat {

    private GoFormat() {
    }

    /** Go's {@code fmt.Sprint(v)} / {@code %v}. */
    public static String sprint(Object v) {
        StringBuilder sb = new StringBuilder();
        appendV(sb, v, true);
        return sb.toString();
    }

    private static void appendV(StringBuilder sb, Object v, boolean top) {
        switch (v) {
            case null -> sb.append("<nil>");
            case String s -> sb.append(s);
            case Double d -> sb.append(formatFloatG(d));
            case Float f -> sb.append(formatFloatG(f.doubleValue()));
            case BigDecimal bd -> sb.append(formatFloatG(bd.doubleValue()));
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case byte[] bytes -> {
                sb.append('[');
                for (int i = 0; i < bytes.length; i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(bytes[i] & 0xff);
                }
                sb.append(']');
            }
            case Struct s -> {
                sb.append('{');
                boolean first = true;
                for (Object fv : s.fields().values()) {
                    if (!first) {
                        sb.append(' ');
                    }
                    first = false;
                    appendV(sb, fv, false);
                }
                sb.append('}');
            }
            case Map<?, ?> m -> {
                sb.append("map[");
                boolean first = true;
                for (Map.Entry<String, Object> e : sortedEntries(m).entrySet()) {
                    if (!first) {
                        sb.append(' ');
                    }
                    first = false;
                    sb.append(e.getKey()).append(':');
                    appendV(sb, e.getValue(), false);
                }
                sb.append(']');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (Object e : c) {
                    if (!first) {
                        sb.append(' ');
                    }
                    first = false;
                    appendV(sb, e, false);
                }
                sb.append(']');
            }
            case Object[] arr -> appendV(sb, java.util.Arrays.asList(arr), top);
            default -> sb.append(v);
        }
    }

    static TreeMap<String, Object> sortedEntries(Map<?, ?> m) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            sorted.put(String.valueOf(e.getKey()), e.getValue());
        }
        return sorted;
    }

    /** {@code strconv.FormatFloat(f, 'g', -1, 64)}, used by {@code %v}. */
    public static String formatFloatG(double f) {
        if (Double.isNaN(f)) {
            return "NaN";
        }
        if (Double.isInfinite(f)) {
            return f > 0 ? "+Inf" : "-Inf";
        }
        if (f == 0) {
            return (1 / f < 0) ? "-0" : "0";
        }
        Decimal d = Decimal.of(f);
        int exp = d.pointPos - 1;
        // shortest precision: Go uses an exponent threshold of 6
        if (exp < -4 || exp >= 6) {
            return formatE(d);
        }
        return formatF(d);
    }

    /**
     * Float formatting of Go's {@code encoding/json}: plain notation unless the magnitude is below
     * 1e-6 or at least 1e21. NaN and infinities, which JSON cannot represent, are written as
     * {@code null}.
     */
    public static String formatFloatJson(double f) {
        if (Double.isNaN(f) || Double.isInfinite(f)) {
            return "null";
        }
        if (f == 0) {
            return "0";
        }
        double abs = Math.abs(f);
        Decimal d = Decimal.of(f);
        if (abs < 1e-6 || abs >= 1e21) {
            String s = formatE(d);
            // clean up e-09 to e-9
            int n = s.length();
            if (n >= 4 && s.charAt(n - 4) == 'e' && s.charAt(n - 3) == '-' && s.charAt(n - 2) == '0') {
                s = s.substring(0, n - 2) + s.charAt(n - 1);
            }
            return s;
        }
        return formatF(d);
    }

    private static String formatE(Decimal d) {
        StringBuilder sb = new StringBuilder();
        if (d.negative) {
            sb.append('-');
        }
        sb.append(d.digits.charAt(0));
        if (d.digits.length() > 1) {
            sb.append('.').append(d.digits, 1, d.digits.length());
        }
        int exp = d.pointPos - 1;
        sb.append('e').append(exp < 0 ? '-' : '+');
        int abs = Math.abs(exp);
        if (abs < 10) {
            sb.append('0');
        }
        sb.append(abs);
        return sb.toString();
    }

    private static String formatF(Decimal d) {
        StringBuilder sb = new StringBuilder();
        if (d.negative) {
            sb.append('-');
        }
        String digits = d.digits;
        int point = d.pointPos;
        if (point <= 0) {
            sb.append("0.");
            sb.append("0".repeat(-point));
            sb.append(digits);
        } else if (point >= digits.length()) {
            sb.append(digits);
            sb.append("0".repeat(point - digits.length()));
        } else {
            sb.append(digits, 0, point).append('.').append(digits, point, digits.length());
        }
        return sb.toString();
    }

    /** Shortest decimal digits of a double, with the decimal point position. */
    private record Decimal(boolean negative, String digits, int pointPos) {
        static Decimal of(double f) {
            BigDecimal bd = new BigDecimal(Double.toString(Math.abs(f))).stripTrailingZeros();
            String unscaled = bd.unscaledValue().toString();
            int pointPos = unscaled.length() - bd.scale();
            return new Decimal(f < 0, unscaled, pointPos);
        }
    }

    /** {@code strconv.Quote}. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        s.codePoints().forEach(cp -> appendEscaped(sb, cp, '"'));
        sb.append('"');
        return sb.toString();
    }

    private static void appendEscaped(StringBuilder sb, int r, char quote) {
        if (r == quote || r == '\\') {
            sb.append('\\').appendCodePoint(r);
            return;
        }
        if (isPrint(r)) {
            sb.appendCodePoint(r);
            return;
        }
        switch (r) {
            case 7 -> sb.append("\\a");
            case '\b' -> sb.append("\\b");
            case '\f' -> sb.append("\\f");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            case 11 -> sb.append("\\v");
            default -> {
                if (r < ' ' || r == 0x7f) {
                    sb.append("\\x").append(hex(r, 2));
                } else if (r < 0x10000) {
                    sb.append("\\u").append(hex(r, 4));
                } else {
                    sb.append("\\U").append(hex(r, 8));
                }
            }
        }
    }

    private static String hex(int v, int width) {
        String h = Integer.toHexString(v);
        return "0".repeat(Math.max(0, width - h.length())) + h;
    }

    /** Go's {@code unicode.IsPrint}. */
    public static boolean isPrint(int r) {
        if (r == ' ') {
            return true;
        }
        return switch (Character.getType(r)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /** Converts integral values to {@code Long}, keeping other numbers as they are. */
    public static Object normalizeNumber(Number n) {
        if (n instanceof BigInteger bi) {
            return bi.bitLength() < 64 ? (Object) bi.longValue() : bi.doubleValue();
        }
        if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return n.longValue();
        }
        return n;
    }

    /** Copies a list, for callers that need a mutable one. */
    public static List<Object> mutableList(Collection<?> c) {
        return new ArrayList<>(c);
    }
}
