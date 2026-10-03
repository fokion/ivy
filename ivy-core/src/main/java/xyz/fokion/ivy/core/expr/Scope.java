package xyz.fokion.ivy.core.expr;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The variables an expression sees: a chain of layers (built-ins, suite, test case, step...),
 * looked up from the innermost one. Adding a layer copies nothing, so a scope per step or per
 * range iteration costs a small object whatever the size of the values in it.
 */
public final class Scope {

    /** Returned by {@link #lookup} for a name no layer defines. */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "<missing>";
        }
    };

    private static final Scope EMPTY = new Scope(null, Map.of());

    private final Scope parent;
    private final Map<String, ?> vars;

    private Scope(Scope parent, Map<String, ?> vars) {
        this.parent = parent;
        this.vars = vars;
    }

    public static Scope empty() {
        return EMPTY;
    }

    /** A scope over a map; the map is used as it is, not copied. */
    public static Scope of(Map<String, ?> vars) {
        return new Scope(null, vars);
    }

    /**
     * A scope whose variables shadow this one's. The map is used as it is, not copied: entries
     * added to it later are visible.
     */
    public Scope child(Map<String, ?> layer) {
        if (layer == null) {
            return this;
        }
        return new Scope(this, layer);
    }

    /** A scope with one more variable. */
    public Scope with(String name, Object value) {
        return new Scope(this, Collections.singletonMap(name, value));
    }

    /** The value of a variable, or {@link #MISSING}. */
    public Object lookup(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.vars.containsKey(name)) {
                return s.vars.get(name);
            }
        }
        return MISSING;
    }

    public boolean has(String name) {
        return lookup(name) != MISSING;
    }

    /** The names of all variables, innermost layers first. */
    public Set<String> names() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Scope s = this; s != null; s = s.parent) {
            names.addAll(s.vars.keySet());
        }
        return names;
    }

    /**
     * A read-only map view of the variables; lookups go through the layers, iterating builds the
     * list of entries (the values themselves are not copied).
     */
    public Map<String, Object> asMap() {
        return new AbstractMap<>() {
            @Override
            public Object get(Object key) {
                Object v = key instanceof String k ? lookup(k) : MISSING;
                return v == MISSING ? null : v;
            }

            @Override
            public boolean containsKey(Object key) {
                return key instanceof String k && has(k);
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                List<Scope> layers = new ArrayList<>();
                for (Scope s = Scope.this; s != null; s = s.parent) {
                    layers.add(s);
                }
                Map<String, Object> merged = new LinkedHashMap<>();
                for (int i = layers.size() - 1; i >= 0; i--) {
                    merged.putAll(layers.get(i).vars);
                }
                return Collections.unmodifiableMap(merged).entrySet();
            }
        };
    }
}
