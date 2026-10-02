package xyz.fokion.ivy.spi.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Expected values were produced by Go 1.25. */
class GoFormatTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|0|0",
            "1|1|1",
            "-1|-1|-1",
            "1.5|1.5|1.5",
            "100|100|100",
            "123456|123456|123456",
            "1234567|1.234567e+06|1234567",
            "1e6|1e+06|1000000",
            "1e20|1e+20|100000000000000000000",
            "1e21|1e+21|1e+21",
            "0.0001|0.0001|0.0001",
            "0.00001|1e-05|0.00001",
            "1e-7|1e-07|1e-7",
            "3.14159|3.14159|3.14159",
            "2.5e-5|2.5e-05|0.000025",
            "123456.789|123456.789|123456.789",
            "-0.5|-0.5|-0.5",
    })
    void formatsFloatsLikeGo(double value, String g, String json) {
        assertEquals(g, GoFormat.formatFloatG(value));
        assertEquals(g, GoFormat.sprint(value));
        assertEquals(json, GoFormat.formatFloatJson(value));
        assertEquals(json, Json.write(value));
    }

    @Test
    void quotesLikeStrconv() {
        assertEquals("\"a\\\"b\"", GoFormat.quote("a\"b"));
        assertEquals("\"tab\\tnl\\n\"", GoFormat.quote("tab\tnl\n"));
        assertEquals("\"é ü\"", GoFormat.quote("é ü"));
        assertEquals("\"\\x01\\x7f\"", GoFormat.quote("\u0001\u007f"));
        assertEquals("\"\\u2028\"", GoFormat.quote("\u2028"));
        assertEquals("\"back\\\\slash\"", GoFormat.quote("back\\slash"));
        assertEquals("\"\\u00a0nbsp\"", GoFormat.quote("\u00a0nbsp"));
        assertEquals("\"😀\"", GoFormat.quote("😀"));
    }

    @Test
    void escapesJsonStringsLikeGo() {
        assertEquals("\"\\u003ca\\u0026b\\u003e\"", Json.write("<a&b>"));
        assertEquals("\"\\u2028\"", Json.write("\u2028"));
        assertEquals("\"\\u0001\u007f\"", Json.write("\u0001\u007f"));
        assertEquals("\"\u00a0nbsp\"", Json.write("\u00a0nbsp"));
    }

    @Test
    void printsCompositesLikeGo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1L);
        m.put("a", Arrays.asList("x", 2.5, true, null));
        m.put("c", Map.of("z", "y"));
        assertEquals("map[a:[x 2.5 true <nil>] b:1 c:map[z:y]]", GoFormat.sprint(m));
        assertEquals("{\"a\":[\"x\",2.5,true,null],\"b\":1,\"c\":{\"z\":\"y\"}}", Json.write(m));
        assertEquals("""
                {
                  "a": [
                    "x",
                    2.5,
                    true,
                    null
                  ],
                  "b": 1,
                  "c": {
                    "z": "y"
                  }
                }""", Json.write(m, Json.GO_INDENT));
    }

    @Test
    void parsesJson() {
        Object v = Json.parse(" {\"a\": [1, 2.5, \"s\\u00e9\", true, null, {}], \"b\": -12} ");
        assertEquals(Map.of("a", Arrays.asList(1L, 2.5, "sé", true, null, Map.of()), "b", -12L), v);
        assertEquals(List.of(), Json.parse("[]"));
        assertEquals(false, Json.isValid("{\"a\":}"));
        assertEquals(false, Json.isValid("01"));
        assertEquals(false, Json.isValid("[1] x"));
    }
}
