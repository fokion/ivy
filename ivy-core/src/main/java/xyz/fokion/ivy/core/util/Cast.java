package xyz.fokion.ivy.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.GoFormat;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Loose conversions with the semantics of Go's {@code spf13/cast}, which suites rely on.
 */
public final class Cast {

    private static final Pattern STRING_NUMBER = Pattern.compile("^([-+]?\\d*)(\\.\\d*)?$");

    private Cast() {
    }

    public static final class CastException extends RuntimeException {
        public CastException(String message) {
            super(message);
        }
    }

    /** {@code cast.ToString}: never fails, {@code null} becomes "". */
    public static String toString(Object v) {
        return switch (v) {
            case null -> "";
            case String s -> s;
            case Double d -> formatFloatF(d);
            case Float f -> formatFloatF(f.doubleValue());
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            case byte[] bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            default -> GoFormat.sprint(v);
        };
    }

    /** {@code strconv.FormatFloat(f, 'f', -1, 64)}. */
    public static String formatFloatF(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    /** {@code cast.ToStringE}: fails for composite values. */
    public static String toStringStrict(Object v) {
        if (v instanceof Map || v instanceof Collection || v instanceof Struct) {
            throw new CastException("unable to cast " + GoFormat.sprint(v) + " to string");
        }
        return toString(v);
    }

    /** {@code cast.ToInt64}: returns 0 when the value cannot be converted. */
    public static long toLong(Object v) {
        try {
            return toLongStrict(v);
        } catch (CastException e) {
            return 0;
        }
    }

    /** {@code cast.ToInt64E}. */
    public static long toLongStrict(Object v) {
        return switch (v) {
            case null -> 0;
            case Long l -> l;
            case Integer i -> i;
            case Short s -> s;
            case Byte b -> b;
            case BigInteger bi -> bi.longValue();
            case Number n -> (long) n.doubleValue();
            case Boolean b -> b ? 1 : 0;
            case String s -> parseLong(s);
            default -> throw new CastException("unable to cast " + GoFormat.sprint(v) + " to int64");
        };
    }

    private static long parseLong(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        String trimmed = trimDecimal(s);
        try {
            return parseGoInt(trimmed);
        } catch (NumberFormatException e) {
            throw new CastException("unable to cast \"" + s + "\" to int64");
        }
    }

    private static String trimDecimal(String s) {
        if (!s.contains(".")) {
            return s;
        }
        Matcher m = STRING_NUMBER.matcher(s);
        if (m.matches()) {
            String i = m.group(1);
            return switch (i) {
                case "-", "+" -> i + "0";
                case "" -> "0";
                default -> i;
            };
        }
        return s;
    }

    /** {@code strconv.ParseInt(s, 0, 64)}: accepts 0x, 0o, 0b prefixes, a leading 0 for octal and underscores. */
    public static long parseGoInt(String s) {
        String t = s;
        boolean neg = false;
        if (t.startsWith("+") || t.startsWith("-")) {
            neg = t.charAt(0) == '-';
            t = t.substring(1);
        }
        int radix = 10;
        boolean prefixed = false;
        if (t.length() > 1 && t.charAt(0) == '0') {
            char p = Character.toLowerCase(t.charAt(1));
            if (p == 'x') {
                radix = 16;
                t = t.substring(2);
                prefixed = true;
            } else if (p == 'o') {
                radix = 8;
                t = t.substring(2);
                prefixed = true;
            } else if (p == 'b') {
                radix = 2;
                t = t.substring(2);
                prefixed = true;
            } else {
                radix = 8;
                t = t.substring(1);
                prefixed = true;
            }
        }
        if (prefixed) {
            t = t.replace("_", "");
        }
        if (t.isEmpty() || t.startsWith("+") || t.startsWith("-")) {
            throw new NumberFormatException(s);
        }
        long v = Long.parseLong(t, radix);
        return neg ? -v : v;
    }

    /** {@code cast.ToFloat64E}. */
    public static double toDoubleStrict(Object v) {
        return switch (v) {
            case null -> 0;
            case Number n -> n.doubleValue();
            case Boolean b -> b ? 1 : 0;
            case String s -> {
                if (s.isEmpty()) {
                    yield 0;
                }
                try {
                    yield Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw new CastException("unable to cast \"" + s + "\" to float64");
                }
            }
            default -> throw new CastException("unable to cast " + GoFormat.sprint(v) + " to float64");
        };
    }

    /** {@code cast.ToBool}: returns false when the value cannot be converted. */
    public static boolean toBool(Object v) {
        try {
            return toBoolStrict(v);
        } catch (CastException e) {
            return false;
        }
    }

    /** {@code cast.ToBoolE}. */
    public static boolean toBoolStrict(Object v) {
        return switch (v) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.doubleValue() != 0;
            case String s -> parseGoBool(s);
            default -> throw new CastException("unable to cast " + GoFormat.sprint(v) + " to bool");
        };
    }

    /** {@code strconv.ParseBool}. */
    public static boolean parseGoBool(String s) {
        return switch (s) {
            case "1", "t", "T", "TRUE", "true", "True" -> true;
            case "0", "f", "F", "FALSE", "false", "False" -> false;
            default -> throw new CastException("strconv.ParseBool: parsing " + GoFormat.quote(s) + ": invalid syntax");
        };
    }

    /** {@code cast.ToStringSlice}: a string is split on whitespace. */
    public static List<String> toStringList(Object v) {
        List<String> out = new ArrayList<>();
        switch (v) {
            case null -> {
            }
            case String s -> {
                for (String f : s.trim().split("\\s+")) {
                    if (!f.isEmpty()) {
                        out.add(f);
                    }
                }
            }
            case Collection<?> c -> c.forEach(e -> out.add(toString(e)));
            case Object[] a -> {
                for (Object e : a) {
                    out.add(toString(e));
                }
            }
            default -> out.add(toString(v));
        }
        return out;
    }

    /** {@code cast.ToStringMap}: maps keep their entries, JSON strings are parsed, the rest is empty. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toStringMap(Object v) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, val) -> out.put(String.valueOf(k), val));
        } else if (v instanceof Struct s) {
            out.putAll(s.fields());
        } else if (v instanceof String s && Json.tryParse(s) instanceof Map<?, ?> parsed) {
            out.putAll((Map<String, Object>) parsed);
        }
        return out;
    }
}
