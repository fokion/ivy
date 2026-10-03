package xyz.fokion.ivy.core.expr;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.Json;
import xyz.fokion.ivy.spi.util.LazyJson;

/**
 * The value model of expressions: {@code null}, {@link Boolean}, {@link Long}, {@link Double},
 * {@link String}, {@link List} and {@link Map}. Connector values are viewed in that model
 * without copying: a {@link Struct} is its field map, {@link LazyJson} its parsed value.
 */
public final class Values {

    private Values() {
    }

    /** The value as seen by expressions; maps and lists are returned as they are. */
    public static Object unwrap(Object v) {
        return switch (v) {
            case LazyJson j -> unwrap(j.value());
            case Struct s -> s.fields();
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case Float f -> f.doubleValue();
            case BigInteger bi -> bi.bitLength() < 64 ? (Object) bi.longValue() : bi.doubleValue();
            case BigDecimal bd -> bd.doubleValue();
            case Character c -> c.toString();
            case Object[] a -> List.of(a);
            case null, default -> v;
        };
    }

    // ------------------------------------------------------------ access

    /** {@code value.name}: a map entry, a list or string length; {@code null} when absent. */
    public static Object member(Object value, String name) {
        Object v = unwrap(value);
        return switch (v) {
            case null -> null;
            case Map<?, ?> m -> {
                if (m.containsKey(name)) {
                    yield m.get(name);
                }
                yield name.equals("length") ? (Object) (long) m.size() : null;
            }
            case List<?> l -> name.equals("length") ? (Object) (long) l.size() : index(l, name);
            case String s -> name.equals("length") ? (Object) (long) s.length() : null;
            default -> null;
        };
    }

    /** {@code value[key]}: a list item (negative indexes count from the end), a map entry or a character. */
    public static Object index(Object value, Object key) {
        Object v = unwrap(value);
        Object k = unwrap(key);
        return switch (v) {
            case null -> null;
            case List<?> l -> {
                Long i = asIndex(k);
                if (i == null) {
                    yield k instanceof String s && s.equals("length") ? (Object) (long) l.size() : null;
                }
                long idx = i < 0 ? l.size() + i : i;
                yield idx >= 0 && idx < l.size() ? l.get((int) idx) : null;
            }
            case Map<?, ?> m -> m.get(k instanceof String s ? s : display(k));
            case String s -> {
                Long i = asIndex(k);
                if (i == null) {
                    yield null;
                }
                long idx = i < 0 ? s.length() + i : i;
                yield idx >= 0 && idx < s.length() ? String.valueOf(s.charAt((int) idx)) : null;
            }
            default -> null;
        };
    }

    private static Long asIndex(Object k) {
        return switch (k) {
            case Long l -> l;
            case Double d when d == Math.rint(d) -> d.longValue();
            case String s -> {
                try {
                    yield Long.parseLong(s.strip());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case null, default -> null;
        };
    }

    // ------------------------------------------------------------ conversions

    public static boolean truthy(Object value) {
        Object v = unwrap(value);
        return switch (v) {
            case null -> false;
            case Boolean b -> b;
            case Long l -> l != 0;
            case Double d -> d != 0 && !d.isNaN();
            case String s -> !s.isEmpty();
            default -> true;
        };
    }

    /** A number, or {@code null} when the value is not one (numeric strings are numbers). */
    public static Number toNumber(Object value) {
        Object v = unwrap(value);
        return switch (v) {
            case Long l -> l;
            case Double d -> d;
            case Boolean b -> b ? 1L : 0L;
            case String s -> parseNumber(s);
            case null, default -> null;
        };
    }

    static Number parseNumber(String s) {
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            // not an integer
        }
        if (!t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
            return null;
        }
        return Double.parseDouble(t);
    }

    /** The text of a value as templates write it: strings as they are, other values as JSON. */
    public static String display(Object value) {
        Object v = unwrap(value);
        return switch (v) {
            case null -> "";
            case String s -> s;
            case Long l -> l.toString();
            case Double d -> formatDouble(d);
            case Boolean b -> b.toString();
            default -> Json.write(v, Json.COMPACT);
        };
    }

    /** A value as shown in failure messages: JSON, cut to {@code max} characters. */
    public static String describe(Object value, int max) {
        Object v = unwrap(value);
        String s = switch (v) {
            case null -> "null";
            case Double d -> formatDouble(d);
            default -> Json.write(v, Json.COMPACT);
        };
        if (s.length() > max) {
            return s.substring(0, max) + "… (" + (s.length() - max) + " more characters)";
        }
        return s;
    }

    static String formatDouble(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e21) {
            return Long.toString((long) d);
        }
        return Double.toString(d).replace("E", "e");
    }

    public static String typeOf(Object value) {
        Object v = unwrap(value);
        return switch (v) {
            case null -> "null";
            case Boolean _ -> "boolean";
            case Long _, Double _ -> "number";
            case String _ -> "string";
            case List<?> _ -> "array";
            case Lambda _ -> "function";
            default -> "object";
        };
    }

    // ------------------------------------------------------------ equality and order

    /**
     * {@code ==}: numbers compare numerically, also against numeric strings; booleans compare
     * with {@code "true"} and {@code "false"}; lists and maps compare deeply.
     */
    public static boolean looseEquals(Object left, Object right) {
        Object a = unwrap(left);
        Object b = unwrap(right);
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Number || b instanceof Number) {
            Number x = a instanceof Boolean ? null : toNumber(a);
            Number y = b instanceof Boolean ? null : toNumber(b);
            return x != null && y != null && numericEquals(x, y);
        }
        if (a instanceof Boolean x && b instanceof String y) {
            return x.toString().equalsIgnoreCase(y.strip());
        }
        if (a instanceof String x && b instanceof Boolean y) {
            return y.toString().equalsIgnoreCase(x.strip());
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            Iterator<?> i = x.iterator();
            Iterator<?> j = y.iterator();
            while (i.hasNext()) {
                if (!looseEquals(i.next(), j.next())) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !looseEquals(e.getValue(), y.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /** {@code ===}: same type and value; lists and maps compare deeply. */
    public static boolean strictEquals(Object left, Object right) {
        Object a = unwrap(left);
        Object b = unwrap(right);
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return numericEquals(x, y);
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            Iterator<?> i = x.iterator();
            Iterator<?> j = y.iterator();
            while (i.hasNext()) {
                if (!strictEquals(i.next(), j.next())) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !strictEquals(e.getValue(), y.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.getClass() == b.getClass() && a.equals(b);
    }

    private static boolean numericEquals(Number x, Number y) {
        if (x instanceof Long a && y instanceof Long b) {
            return a.longValue() == b.longValue();
        }
        return x.doubleValue() == y.doubleValue();
    }

    /**
     * Orders two values: numbers (and numeric strings) numerically, other strings
     * alphabetically.
     */
    public static int compare(Object left, Object right) {
        Object a = unwrap(left);
        Object b = unwrap(right);
        Number x = a instanceof Boolean ? null : toNumber(a);
        Number y = b instanceof Boolean ? null : toNumber(b);
        if (x != null && y != null) {
            if (x instanceof Long p && y instanceof Long q) {
                return Long.compare(p, q);
            }
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof String p && b instanceof String q) {
            return p.compareTo(q);
        }
        throw new ExprException("cannot compare " + typeOf(a) + " " + describe(a, 50) + " with " + typeOf(b) + " "
                + describe(b, 50));
    }

    /** {@code container contains item}: a substring, a list item or a map key. */
    public static boolean contains(Object container, Object item) {
        Object c = unwrap(container);
        Object i = unwrap(item);
        return switch (c) {
            case null -> false;
            case String s -> s.contains(display(i));
            case List<?> l -> {
                for (Object e : l) {
                    if (looseEquals(e, i)) {
                        yield true;
                    }
                }
                yield false;
            }
            case Map<?, ?> m -> m.containsKey(display(i));
            default -> false;
        };
    }

    /** A function value: {@code x => body}, evaluated in the scope it was written in. */
    record Lambda(String param, Node body, Scope scope, Evaluator evaluator) {
        Object call(Object arg) {
            return evaluator.evaluateIn(body, scope.with(param, arg));
        }
    }
}
