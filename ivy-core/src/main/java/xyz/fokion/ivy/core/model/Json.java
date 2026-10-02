package xyz.fokion.ivy.core.model;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Helpers for the JSON shape of the model, which follows venom's Go struct tags. */
final class Json {

    private static final OffsetDateTime ZERO = OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private Json() {
    }

    /** Go's RFC 3339 encoding of {@code time.Time}, zero time included. */
    static String time(OffsetDateTime t) {
        return (t == null ? ZERO : t).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** A Go nil slice encodes as null. */
    static <T> List<Object> list(List<T> l, Function<T, Object> f) {
        if (l == null) {
            return null;
        }
        List<Object> out = new ArrayList<>(l.size());
        l.forEach(e -> out.add(f.apply(e)));
        return out;
    }
}
