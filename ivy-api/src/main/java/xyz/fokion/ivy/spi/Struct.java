package xyz.fokion.ivy.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A named record of fields, the equivalent of a Go struct in venom.
 * <p>
 * When a step returns a struct named {@code Result}, its fields become the {@code result.*}
 * variables. Field order is kept.
 */
public final class Struct {

    private final String typeName;
    private final Map<String, Object> fields;

    private Struct(String typeName, Map<String, Object> fields) {
        this.typeName = Objects.requireNonNull(typeName);
        this.fields = fields;
    }

    public static Struct of(String typeName, Map<String, ?> fields) {
        return new Struct(typeName, Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
    }

    public static Builder builder(String typeName) {
        return new Builder(typeName);
    }

    public String typeName() {
        return typeName;
    }

    public Map<String, Object> fields() {
        return fields;
    }

    public Object get(String field) {
        return fields.get(field);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Struct s && s.typeName.equals(typeName) && s.fields.equals(fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, fields);
    }

    @Override
    public String toString() {
        return typeName + fields;
    }

    public static final class Builder {
        private final String typeName;
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder(String typeName) {
            this.typeName = typeName;
        }

        public Builder put(String field, Object value) {
            fields.put(field, value);
            return this;
        }

        public Struct build() {
            return Struct.of(typeName, fields);
        }
    }
}
