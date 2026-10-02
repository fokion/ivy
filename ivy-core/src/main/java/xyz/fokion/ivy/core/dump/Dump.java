package xyz.fokion.ivy.core.dump;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Flattens values into dotted keys exactly like {@code fsamin/go-dump} configured by venom:
 * extra {@code __Len__} / {@code __Type__} keys, detailed maps, arrays and structs, and array
 * items keyed {@code <name><index>}.
 * <p>
 * Structs ({@link Struct}) at the root are prefixed by their type name, which is how an
 * executor {@code Result} becomes {@code result.*}.
 */
public final class Dump {

    private static final String SEP = ".";

    private Dump() {
    }

    private interface KeyFormatter {
        String format(String s, int level);
    }

    private static String defaultFormat(String s) {
        return s.replace(" ", "_").replace("/", "_").replace(":", "_");
    }

    /** venom's {@code WithFormatterLowerFirstKey}. */
    private static final KeyFormatter LOWER_FIRST_KEY = (s, level) -> {
        if (level == 0 && s.contains(".")) {
            int pos = s.indexOf('.');
            return s.substring(0, pos).toLowerCase() + s.substring(pos);
        }
        if (level == 0) {
            return defaultFormat(s).toLowerCase();
        }
        return defaultFormat(s);
    };

    private static final KeyFormatter DEFAULT = (s, level) -> defaultFormat(s);

    /** venom's {@code Dump}. */
    public static Map<String, Object> dump(Object v) {
        return new Encoder(LOWER_FIRST_KEY, "").toMap(v);
    }

    /** venom's {@code DumpWithPrefix}. */
    public static Map<String, Object> dumpWithPrefix(Object v, String prefix) {
        return new Encoder(LOWER_FIRST_KEY, prefix).toMap(v);
    }

    /** venom's {@code DumpString}: values printed, first key segment lowercased. */
    public static Map<String, String> dumpString(Object v) {
        return toStrings(new Encoder(LOWER_FIRST_KEY, "").toMap(v));
    }

    /** venom's {@code DumpStringPreserveCase}. */
    public static Map<String, String> dumpStringPreserveCase(Object v) {
        return toStrings(new Encoder(DEFAULT, "").toMap(v));
    }

    private static Map<String, String> toStrings(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>(m.size() * 2);
        m.forEach((k, v) -> out.put(k, printValue(v)));
        return out;
    }

    /** go-dump's {@code printValue}: strings as is, anything else as compact JSON. */
    public static String printValue(Object v) {
        if (v instanceof String s) {
            return s;
        }
        return Json.write(v, Json.GO);
    }

    private record Encoder(KeyFormatter formatter, String prefix) {

        Map<String, Object> toMap(Object v) {
            Map<String, Object> w = new LinkedHashMap<>();
            dumpInterface(w, v, new ArrayList<>());
            return w;
        }

        private String key(List<String> roots) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < roots.size(); i++) {
                String formatted = formatter.format(roots.get(i), i);
                if (i > 0) {
                    sb.append(SEP);
                }
                sb.append(formatted);
            }
            return sb.toString();
        }

        private static List<String> plus(List<String> roots, String key) {
            List<String> l = new ArrayList<>(roots.size() + 1);
            l.addAll(roots);
            l.add(key);
            return l;
        }

        private String prefixed() {
            return prefix.isEmpty() ? "" : prefix + SEP;
        }

        private void dumpInterface(Map<String, Object> w, Object v, List<String> roots) {
            if (v == null || (v instanceof String s && s.isEmpty())) {
                if (roots.isEmpty()) {
                    return;
                }
                w.put(prefixed() + key(roots), "");
                return;
            }
            switch (v) {
                case Struct s -> {
                    w.put(key(plus(roots, "__Type__")), s.typeName());
                    List<String> croots = roots.isEmpty() ? plus(roots, s.typeName()) : roots;
                    dumpStruct(w, s, croots);
                }
                case byte[] bytes -> dumpInterface(w, new String(bytes, StandardCharsets.UTF_8), roots);
                case Collection<?> c -> dumpArray(w, v, new ArrayList<>(c), roots);
                case Object[] a -> dumpArray(w, v, Arrays.asList(a), roots);
                case Map<?, ?> m -> {
                    w.put(key(plus(roots, "__Type__")), "Map");
                    dumpMap(w, m, roots);
                }
                default -> w.put(prefixed() + key(roots), v);
            }
        }

        private void dumpArray(Map<String, Object> w, Object original, List<?> items, List<String> roots) {
            w.put(key(plus(roots, "__Type__")), "Array");
            w.put(key(plus(roots, "__Len__")), (long) items.size());
            if (!roots.isEmpty()) {
                w.put(key(roots), original);
            }
            for (int i = 0; i < items.size(); i++) {
                List<String> croots;
                if (!roots.isEmpty()) {
                    String last = roots.getLast();
                    croots = plus(roots, last + i);
                } else {
                    croots = plus(roots, prefix + i);
                }
                dumpInterface(w, items.get(i), croots);
            }
        }

        private void dumpMap(Map<String, Object> w, Map<?, ?> m, List<String> roots) {
            long lenKeys = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = e.getKey() instanceof String s ? s : xyz.fokion.ivy.spi.util.GoFormat.sprint(e.getKey());
                if (k.isEmpty()) {
                    continue;
                }
                lenKeys++;
                List<String> croots = plus(roots, k);
                Object value = e.getValue();
                if (value instanceof Struct s) {
                    croots = plus(croots, s.typeName());
                }
                dumpInterface(w, value, croots);
            }
            w.put(key(plus(roots, "__Len__")), lenKeys);
            if (!roots.isEmpty()) {
                w.put(key(roots), m);
            }
        }

        private void dumpStruct(Map<String, Object> w, Struct s, List<String> roots) {
            w.put(key(plus(roots, "__Len__")), (long) s.fields().size());
            if (roots.size() > 1) {
                w.put(key(roots), s);
            }
            for (Map.Entry<String, Object> e : s.fields().entrySet()) {
                dumpInterface(w, e.getValue(), plus(roots, e.getKey()));
            }
        }
    }
}
