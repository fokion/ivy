package xyz.fokion.ivy.core.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Compares with the output of fsamin/go-dump v1.8.0 configured like venom; see
 * {@code golden/dump.go.txt} for the generating program.
 */
class DumpTest {

    private static final Map<String, String> GOLDEN = new HashMap<>();

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = DumpTest.class.getResourceAsStream("/golden/dump.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                GOLDEN.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
    }

    private static Map<String, Object> h() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("Foo Bar", "x");
        h.put("a", Map.of("B", Arrays.asList(1L, "two", null)));
        h.put("empty", "");
        h.put("nil", null);
        h.put("n", 1.5);
        h.put("Up.Per", "y");
        h.put("a/b:c", true);
        return h;
    }

    private static Struct result() {
        return Struct.builder("Result")
                .put("systemout", "out")
                .put("code", 2L)
                .put("items", List.of(Map.of("k", "v"), "x"))
                .put("obj", Map.of("z", 1L))
                .put("inner", Struct.of("Inner", Map.of("name", "n")))
                .put("empty", "")
                .put("timeseconds", 0.25)
                .build();
    }

    private static final List<Object> ARRAY = List.of(Map.of("a", 1L), "x", List.of("y"));

    private static void check(String name, Object actual) {
        assertEquals(GOLDEN.get(name), Json.write(actual), name);
    }

    @Test
    void dumpsMaps() {
        check("dump_h", Dump.dump(h()));
        check("dumpstring_h", Dump.dumpString(h()));
        check("dumppreserve_h", Dump.dumpStringPreserveCase(h()));
    }

    @Test
    void dumpsStructs() {
        check("dump_result", Dump.dump(result()));
        check("dumpstring_result", Dump.dumpString(result()));
        check("dump_map_struct", Dump.dump(Map.of("res", result())));
    }

    @Test
    void dumpsArrays() {
        check("dumpprefix_array", Dump.dumpWithPrefix(ARRAY, "result.bodyjson"));
        check("dumpprefix_map", Dump.dumpWithPrefix(Map.of("a", Map.of("b", "c")), "result.bodyjson"));
        check("dump_array", Dump.dump(ARRAY));
    }

    @Test
    void keepsCaseOfNestedKeys() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("result.systemoutjson", Map.of("FOO", "bar", "foo", "foo"));
        v.put("result.systemoutjson.FOO", "bar");
        v.put("result.systemoutjson.foo", "foo");
        Map<String, Object> got = Dump.dump(v);
        assertEquals("foo", got.get("result.systemoutjson.foo"));
        assertEquals("bar", got.get("result.systemoutjson.FOO"));
    }
}
