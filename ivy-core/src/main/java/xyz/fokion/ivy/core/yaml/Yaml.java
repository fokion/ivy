package xyz.fokion.ivy.core.yaml;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.GoFormat;
import xyz.fokion.ivy.spi.util.Json;

/**
 * YAML handling (YAML 1.2 core schema, converted
 * to JSON-compatible values): maps have string keys, numbers are {@code Long} or
 * {@code Double}.
 */
public final class Yaml {

    private static final LoadSettings LOAD = LoadSettings.builder()
            .setSchema(new CoreSchema())
            .setAllowDuplicateKeys(false)
            .setCodePointLimit(Integer.MAX_VALUE)
            .build();

    private static final DumpSettings DUMP = DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setIndicatorIndent(2)
            .setIndentWithIndicator(true)
            .setSplitLines(false)
            .build();

    private Yaml() {
    }

    public static final class YamlException extends Exception {
        public YamlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Loads the first document, or returns {@code null} for an empty input. */
    public static Object load(String yaml) throws YamlException {
        try {
            Iterator<Object> docs = new Load(LOAD).loadAllFromString(yaml).iterator();
            if (!docs.hasNext()) {
                return null;
            }
            return normalize(docs.next());
        } catch (YamlEngineException e) {
            throw new YamlException(e.getMessage(), e);
        }
    }

    /** Loads a document that must be a mapping; an empty document gives an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadMap(String yaml) throws YamlException {
        Object v = load(yaml);
        if (v == null) {
            return new LinkedHashMap<>();
        }
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new YamlException("cannot unmarshal " + kind(v) + " into a mapping", null);
    }

    private static String kind(Object v) {
        return switch (v) {
            case String _ -> "string";
            case List<?> _ -> "array";
            case Number _ -> "number";
            case Boolean _ -> "bool";
            default -> v.getClass().getSimpleName();
        };
    }

    /** Converts loaded YAML to JSON-compatible values. */
    public static Object normalize(Object v) throws YamlException {
        return switch (v) {
            case null -> null;
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(keyString(e.getKey(), e.getValue()), normalize(e.getValue()));
                }
                yield out;
            }
            case Collection<?> c -> {
                List<Object> out = new ArrayList<>(c.size());
                for (Object o : c) {
                    out.add(normalize(o));
                }
                yield out;
            }
            case BigInteger bi -> bi.bitLength() < 64 ? (Object) bi.longValue() : bi.doubleValue();
            case Integer i -> i.longValue();
            case Long l -> l;
            case Float f -> f.doubleValue();
            case Double d -> d;
            case Optional<?> o -> normalize(o.orElse(null));
            default -> v;
        };
    }

    private static String keyString(Object k, Object value) throws YamlException {
        return switch (k) {
            case String s -> s;
            case Integer i -> i.toString();
            case Long l -> l.toString();
            case BigInteger bi -> bi.toString();
            case Double d -> GoFormat.formatFloatG(d);
            case Boolean b -> b.toString();
            case null -> throw new YamlException("unsupported map key of type: <nil>, value: " + GoFormat.sprint(value), null);
            default -> throw new YamlException("unsupported map key of type: " + k.getClass().getSimpleName(), null);
        };
    }

    /** Compact JSON as Go's {@code json.Marshal} writes it. */
    public static String toJson(Object v) {
        return Json.write(v, Json.GO);
    }

    /** YAML text of a JSON-compatible value (map keys sorted, like Go encoders). */
    public static String dump(Object v) {
        return new Dump(DUMP).dumpToString(sorted(v));
    }

    private static Object sorted(Object v) {
        return switch (v) {
            case Struct s -> {
                Map<String, Object> out = new LinkedHashMap<>();
                s.fields().forEach((k, val) -> out.put(k, sorted(val)));
                yield out;
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                new java.util.TreeMap<>(stringKeys(m)).forEach((k, val) -> out.put(k, sorted(val)));
                yield out;
            }
            case Collection<?> c -> {
                List<Object> out = new ArrayList<>();
                c.forEach(o -> out.add(sorted(o)));
                yield out;
            }
            case null -> null;
            default -> v;
        };
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, val) -> out.put(String.valueOf(k), val));
        return out;
    }

    /** Source line numbers (1-based) of a test case, its steps and their assertions. */
    public record TestCaseLines(int testCaseLine, List<Integer> stepLines, List<List<Integer>> assertionLines) {
        public static final TestCaseLines EMPTY = new TestCaseLines(0, List.of(), List.of());
    }

    /** Line numbers of test cases, steps and assertions; returns an empty list when the YAML cannot be parsed. */
    public static List<TestCaseLines> lineNumbers(String content) {
        Optional<Node> root;
        try {
            root = new Compose(LOAD).composeString(content);
        } catch (YamlEngineException e) {
            return List.of();
        }
        if (root.isEmpty() || !(root.get() instanceof MappingNode rootMap)) {
            return List.of();
        }
        Node testcases = value(rootMap, "testcases");
        if (!(testcases instanceof SequenceNode tcs)) {
            return List.of();
        }
        List<TestCaseLines> result = new ArrayList<>();
        for (Node tc : tcs.getValue()) {
            if (!(tc instanceof MappingNode tcMap)) {
                result.add(TestCaseLines.EMPTY);
                continue;
            }
            List<Integer> stepLines = new ArrayList<>();
            List<List<Integer>> assertionLines = new ArrayList<>();
            if (value(tcMap, "steps") instanceof SequenceNode steps) {
                for (Node step : steps.getValue()) {
                    stepLines.add(line(step));
                    List<Integer> lines = new ArrayList<>();
                    if (step instanceof MappingNode stepMap && value(stepMap, "assertions") instanceof SequenceNode as) {
                        for (Node a : as.getValue()) {
                            lines.add(line(a));
                        }
                    }
                    assertionLines.add(lines);
                }
            }
            result.add(new TestCaseLines(line(tc), stepLines, assertionLines));
        }
        return result;
    }

    private static Node value(MappingNode map, String key) {
        for (NodeTuple t : map.getValue()) {
            if (t.getKeyNode() instanceof ScalarNode k && k.getValue().equals(key)) {
                return t.getValueNode();
            }
        }
        return null;
    }

    private static int line(Node n) {
        return n.getStartMark().map(m -> m.getLine() + 1).orElse(0);
    }
}
