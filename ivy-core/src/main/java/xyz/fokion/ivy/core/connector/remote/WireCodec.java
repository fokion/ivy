package xyz.fokion.ivy.core.connector.remote;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.spi.Struct;

/**
 * Encodes connector results for the wire: a {@link Struct} becomes
 * {@code {"@ivy.struct": type, "fields": {...}}}, byte arrays become base64 strings.
 */
public final class WireCodec {

    static final String STRUCT = "@ivy.struct";

    private WireCodec() {
    }

    public static Object encode(Object v) {
        return switch (v) {
            case null -> null;
            case Struct s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(STRUCT, s.typeName());
                Map<String, Object> fields = new LinkedHashMap<>();
                s.fields().forEach((k, val) -> fields.put(k, encode(val)));
                m.put("fields", fields);
                yield m;
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, val) -> out.put(String.valueOf(k), encode(val)));
                yield out;
            }
            case Collection<?> c -> {
                List<Object> out = new ArrayList<>();
                c.forEach(e -> out.add(encode(e)));
                yield out;
            }
            case Object[] a -> encode(List.of(a));
            case byte[] b -> Base64.getEncoder().encodeToString(b);
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            default -> v.toString();
        };
    }

    public static Object decode(Object v) {
        return switch (v) {
            case Map<?, ?> m when m.containsKey(STRUCT) -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                Cast.toStringMap(m.get("fields")).forEach((k, val) -> fields.put(k, decode(val)));
                yield Struct.of(String.valueOf(m.get(STRUCT)), fields);
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, val) -> out.put(String.valueOf(k), decode(val)));
                yield out;
            }
            case List<?> l -> {
                List<Object> out = new ArrayList<>();
                l.forEach(e -> out.add(decode(e)));
                yield out;
            }
            case null, default -> v;
        };
    }
}
