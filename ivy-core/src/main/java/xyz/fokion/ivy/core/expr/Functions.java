package xyz.fokion.ivy.core.expr;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.util.Json;

/** The global functions and the methods of strings, arrays and objects. */
final class Functions {

    private Functions() {
    }

    // ------------------------------------------------------------ global functions

    static Object function(String name, List<Object> args, String source, int at) {
        return switch (name) {
            case "len" -> {
                Object v = Values.unwrap(arg(args, 0));
                yield switch (v) {
                    case null -> 0L;
                    case String s -> (long) s.length();
                    case Collection<?> c -> (long) c.size();
                    case Map<?, ?> m -> (long) m.size();
                    default -> throw new ExprException("len() expects a string, an array or an object", source, at);
                };
            }
            case "keys" -> Values.unwrap(arg(args, 0)) instanceof Map<?, ?> m ? keys(m) : List.of();
            case "values" -> Values.unwrap(arg(args, 0)) instanceof Map<?, ?> m ? new ArrayList<>(m.values()) : List.of();
            case "isEmpty" -> isEmpty(arg(args, 0));
            case "json" -> {
                Object v = Values.unwrap(arg(args, 0));
                if (!(v instanceof String s)) {
                    yield v;
                }
                try {
                    yield Json.parse(s.strip());
                } catch (Json.JsonException e) {
                    throw new ExprException("json(): " + e.getMessage(), source, at);
                }
            }
            case "toJson" -> Json.write(Values.unwrap(arg(args, 0)), Json.COMPACT);
            case "number" -> Values.toNumber(arg(args, 0));
            case "string" -> Values.display(arg(args, 0));
            case "typeOf" -> Values.typeOf(arg(args, 0));
            case "between" -> {
                Object x = arg(args, 0);
                yield Values.compare(x, arg(args, 1)) >= 0 && Values.compare(x, arg(args, 2)) <= 0;
            }
            case "approx" -> {
                Number a = requireNumber(arg(args, 0), name, source, at);
                Number b = requireNumber(arg(args, 1), name, source, at);
                Number delta = args.size() > 2 ? requireNumber(arg(args, 2), name, source, at) : 1e-9;
                yield Math.abs(a.doubleValue() - b.doubleValue()) <= delta.doubleValue();
            }
            case "date" -> date(arg(args, 0), source, at);
            case "now" -> System.currentTimeMillis();
            case "base64" -> Base64.getEncoder().encodeToString(Values.display(arg(args, 0)).getBytes(StandardCharsets.UTF_8));
            case "unbase64" -> {
                try {
                    yield new String(Base64.getDecoder().decode(Values.display(arg(args, 0)).strip()), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    throw new ExprException("unbase64(): " + e.getMessage(), source, at);
                }
            }
            case "uuid" -> UUID.randomUUID().toString();
            case "randomInt" -> {
                long lo = requireNumber(arg(args, 0), name, source, at).longValue();
                long hi = requireNumber(arg(args, 1), name, source, at).longValue();
                if (hi <= lo) {
                    throw new ExprException("randomInt(lo, hi) needs lo < hi", source, at);
                }
                yield ThreadLocalRandom.current().nextLong(lo, hi);
            }
            case "abs" -> {
                Number n = requireNumber(arg(args, 0), name, source, at);
                yield n instanceof Long l ? (Object) Math.abs(l) : (Object) Math.abs(n.doubleValue());
            }
            case "round" -> Math.round(requireNumber(arg(args, 0), name, source, at).doubleValue());
            case "floor" -> (long) Math.floor(requireNumber(arg(args, 0), name, source, at).doubleValue());
            case "ceil" -> (long) Math.ceil(requireNumber(arg(args, 0), name, source, at).doubleValue());
            case "min", "max" -> {
                Object best = null;
                for (Object a : flatten(args)) {
                    if (best == null || (name.equals("min") ? Values.compare(a, best) < 0 : Values.compare(a, best) > 0)) {
                        best = a;
                    }
                }
                yield best;
            }
            default -> throw new ExprException("unknown function " + name + "()", source, at);
        };
    }

    private static List<Object> flatten(List<Object> args) {
        if (args.size() == 1 && Values.unwrap(args.getFirst()) instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return args;
    }

    private static Object arg(List<Object> args, int i) {
        return i < args.size() ? args.get(i) : null;
    }

    private static Number requireNumber(Object v, String fn, String source, int at) {
        Number n = Values.toNumber(v);
        if (n == null) {
            throw new ExprException(fn + "() expects a number, got " + Values.typeOf(v) + " " + Values.describe(v, 50),
                    source, at);
        }
        return n;
    }

    static boolean isEmpty(Object value) {
        Object v = Values.unwrap(value);
        return switch (v) {
            case null -> true;
            case String s -> s.isEmpty();
            case Collection<?> c -> c.isEmpty();
            case Map<?, ?> m -> m.isEmpty();
            default -> false;
        };
    }

    private static List<Object> keys(Map<?, ?> m) {
        List<Object> out = new ArrayList<>(m.size());
        m.keySet().forEach(k -> out.add(String.valueOf(k)));
        return out;
    }

    /** Epoch milliseconds of an ISO-8601 date or date-time; numbers are taken as epoch milliseconds. */
    static long date(Object value, String source, int at) {
        Object v = Values.unwrap(value);
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = Values.display(v).strip();
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            // try the next format
        }
        try {
            return ZonedDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            // try the next format
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            // try the next format
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            // try the next format
        }
        try {
            return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new ExprException("date(): cannot read " + Values.describe(v, 50) + " as an ISO-8601 date", source, at);
        }
    }

    // ------------------------------------------------------------ methods

    static Object method(Object target, String name, List<Object> args, String source, int at) {
        Object t = Values.unwrap(target);
        switch (name) {
            case "toString" -> {
                return Values.display(t);
            }
            case "toJson" -> {
                return Json.write(t, Json.COMPACT);
            }
            default -> {
            }
        }
        return switch (t) {
            case String s -> stringMethod(s, name, args, source, at);
            case List<?> l -> listMethod(l, name, args, source, at);
            case Map<?, ?> m -> mapMethod(m, name, args, source, at);
            case null -> throw new ExprException("cannot call " + name + "() on null", source, at);
            default -> throw new ExprException(Values.typeOf(t) + " has no method " + name + "()", source, at);
        };
    }

    private static Object stringMethod(String s, String name, List<Object> args, String source, int at) {
        return switch (name) {
            case "includes", "contains" -> s.contains(Values.display(arg(args, 0)));
            case "startsWith" -> s.startsWith(Values.display(arg(args, 0)));
            case "endsWith" -> s.endsWith(Values.display(arg(args, 0)));
            case "indexOf" -> (long) s.indexOf(Values.display(arg(args, 0)));
            case "toUpperCase", "upper" -> s.toUpperCase(java.util.Locale.ROOT);
            case "toLowerCase", "lower" -> s.toLowerCase(java.util.Locale.ROOT);
            case "trim" -> s.strip();
            case "lines" -> List.of((Object[]) s.split("\r?\n", -1));
            case "split" -> {
                String sep = Values.display(arg(args, 0));
                List<Object> parts = new ArrayList<>();
                if (sep.isEmpty()) {
                    s.codePoints().forEach(c -> parts.add(new String(Character.toChars(c))));
                    yield parts;
                }
                int from = 0;
                for (int i = s.indexOf(sep); i >= 0; i = s.indexOf(sep, from)) {
                    parts.add(s.substring(from, i));
                    from = i + sep.length();
                }
                parts.add(s.substring(from));
                yield parts;
            }
            case "replace", "replaceAll" -> s.replace(Values.display(arg(args, 0)), Values.display(arg(args, 1)));
            case "substring", "slice" -> {
                int len = s.length();
                int from = clampIndex(arg(args, 0), len, 0);
                int to = args.size() > 1 ? clampIndex(arg(args, 1), len, len) : len;
                yield from >= to ? "" : s.substring(from, to);
            }
            case "at" -> Values.index(s, arg(args, 0));
            case "match" -> {
                Matcher m = regex(arg(args, 0), source, at).matcher(s);
                if (!m.find()) {
                    yield null;
                }
                List<Object> groups = new ArrayList<>();
                for (int g = 0; g <= m.groupCount(); g++) {
                    groups.add(m.group(g));
                }
                yield groups;
            }
            case "matches", "test" -> regex(arg(args, 0), source, at).matcher(s).find();
            case "json" -> function("json", List.of(s), source, at);
            default -> throw new ExprException("string has no method " + name + "()", source, at);
        };
    }

    private static java.util.regex.Pattern regex(Object pattern, String source, int at) {
        try {
            return GoStrings.compileRegex(Values.display(pattern));
        } catch (PatternSyntaxException e) {
            throw new ExprException("invalid regular expression: " + e.getDescription(), source, at);
        }
    }

    private static int clampIndex(Object v, int len, int fallback) {
        Number n = Values.toNumber(v);
        if (n == null) {
            return fallback;
        }
        long i = n.longValue();
        if (i < 0) {
            i = Math.max(0, len + i);
        }
        return (int) Math.min(i, len);
    }

    private static Values.Lambda lambda(Object v, String name, String source, int at) {
        if (v instanceof Values.Lambda l) {
            return l;
        }
        throw new ExprException(name + "() expects a function such as x => x.id", source, at);
    }

    private static Object listMethod(List<?> l, String name, List<Object> args, String source, int at) {
        return switch (name) {
            case "includes", "contains" -> Values.contains(l, arg(args, 0));
            case "indexOf" -> {
                for (int i = 0; i < l.size(); i++) {
                    if (Values.looseEquals(l.get(i), arg(args, 0))) {
                        yield (long) i;
                    }
                }
                yield -1L;
            }
            case "join" -> {
                String sep = args.isEmpty() ? "," : Values.display(arg(args, 0));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(sep);
                    }
                    sb.append(Values.display(l.get(i)));
                }
                yield sb.toString();
            }
            case "at" -> Values.index(l, arg(args, 0));
            case "first" -> l.isEmpty() ? null : l.getFirst();
            case "last" -> l.isEmpty() ? null : l.getLast();
            case "slice" -> {
                int len = l.size();
                int from = clampIndex(arg(args, 0), len, 0);
                int to = args.size() > 1 ? clampIndex(arg(args, 1), len, len) : len;
                yield from >= to ? List.of() : new ArrayList<>(l.subList(from, to));
            }
            case "concat" -> {
                List<Object> out = new ArrayList<>(l);
                for (Object a : args) {
                    if (Values.unwrap(a) instanceof List<?> more) {
                        out.addAll(more);
                    } else {
                        out.add(a);
                    }
                }
                yield out;
            }
            case "reverse" -> {
                List<Object> out = new ArrayList<>(l);
                java.util.Collections.reverse(out);
                yield out;
            }
            case "sort" -> {
                List<Object> out = new ArrayList<>(l);
                if (args.isEmpty()) {
                    out.sort(Values::compare);
                } else {
                    Values.Lambda key = lambda(arg(args, 0), name, source, at);
                    out.sort((a, b) -> Values.compare(key.call(a), key.call(b)));
                }
                yield out;
            }
            case "map" -> {
                Values.Lambda f = lambda(arg(args, 0), name, source, at);
                List<Object> out = new ArrayList<>(l.size());
                for (Object e : l) {
                    out.add(f.call(e));
                }
                yield out;
            }
            case "filter" -> {
                Values.Lambda f = lambda(arg(args, 0), name, source, at);
                List<Object> out = new ArrayList<>();
                for (Object e : l) {
                    if (Values.truthy(f.call(e))) {
                        out.add(e);
                    }
                }
                yield out;
            }
            case "find" -> {
                Values.Lambda f = lambda(arg(args, 0), name, source, at);
                for (Object e : l) {
                    if (Values.truthy(f.call(e))) {
                        yield e;
                    }
                }
                yield null;
            }
            case "findIndex" -> {
                Values.Lambda f = lambda(arg(args, 0), name, source, at);
                for (int i = 0; i < l.size(); i++) {
                    if (Values.truthy(f.call(l.get(i)))) {
                        yield (long) i;
                    }
                }
                yield -1L;
            }
            case "some" -> {
                Values.Lambda f = lambda(arg(args, 0), name, source, at);
                for (Object e : l) {
                    if (Values.truthy(f.call(e))) {
                        yield true;
                    }
                }
                yield false;
            }
            case "every" -> {
                Values.Lambda f = lambda(arg(args, 0), name, source, at);
                for (Object e : l) {
                    if (!Values.truthy(f.call(e))) {
                        yield false;
                    }
                }
                yield true;
            }
            case "count" -> {
                if (args.isEmpty()) {
                    yield (long) l.size();
                }
                Values.Lambda f = lambda(arg(args, 0), name, source, at);
                long n = 0;
                for (Object e : l) {
                    if (Values.truthy(f.call(e))) {
                        n++;
                    }
                }
                yield n;
            }
            default -> throw new ExprException("array has no method " + name + "()", source, at);
        };
    }

    private static Object mapMethod(Map<?, ?> m, String name, List<Object> args, String source, int at) {
        return switch (name) {
            case "keys" -> keys(m);
            case "values" -> new ArrayList<>(m.values());
            case "entries" -> {
                List<Object> out = new ArrayList<>(m.size());
                m.forEach((k, v) -> out.add(java.util.Arrays.asList(String.valueOf(k), v)));
                yield out;
            }
            case "has", "contains", "includes" -> m.containsKey(Values.display(arg(args, 0)));
            case "get" -> m.containsKey(Values.display(arg(args, 0))) ? m.get(Values.display(arg(args, 0))) : arg(args, 1);
            default -> throw new ExprException("object has no method " + name + "()", source, at);
        };
    }
}
