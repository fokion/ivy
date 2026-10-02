package xyz.fokion.ivy.core.assertion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import xyz.fokion.ivy.core.assertion.Assertions.AssertException;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.spi.util.Json;

/**
 * Replays the cases of venom's assertions/assertions_test.go, exported with their Go types by
 * running an instrumented copy of the Go tests ({@code golden/assertions.jsonl}).
 */
class AssertionsTest {

    static Stream<Arguments> cases() throws IOException {
        List<Arguments> out = new ArrayList<>();
        try (InputStream in = AssertionsTest.class.getResourceAsStream("/golden/assertions.jsonl")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> c = Cast.toStringMap(Json.parse(line));
                out.add(Arguments.of(c.get("func") + ": " + c.get("name"), c));
            }
        }
        return out.stream();
    }

    private static final ZonedDateTime NOW = ZonedDateTime.now();

    /**
     * venom only compares JSON numbers decoded as {@code json.Number}; Go {@code int} and
     * {@code float64} values make its JSON assertions fail with "unexpected type". ivy has a
     * single representation for numbers, so these comparisons succeed.
     */
    private static final java.util.Set<String> NUMBER_TYPE_DIFFERENCES = java.util.Set.of(
            "ShouldJSONContain: bad type [1]",
            "ShouldNotJSONContain: bad type [1]",
            "ShouldJSONContainWithKey: bad type [map[a:1]]",
            "ShouldJSONContainAllWithKey: bad type [map[a:1]]",
            "ShouldJSONContainAllWithKey: missing key in second element of the array [map[a:1 b:2 c:3] map[a:1 b:2]]",
            "ShouldNotJSONContainWithKey: bad type [map[a:1]]",
            "ShouldJSONEqual: bad type 1");

    /** Rebuilds a Go value: json.Number becomes Long or Double like JSON numbers in ivy. */
    static Object decode(Object encoded) {
        Map<String, Object> e = Cast.toStringMap(encoded);
        String t = (String) e.get("t");
        Object v = e.get("v");
        return switch (t) {
            case "nil" -> null;
            case "string", "other" -> v;
            case "bool" -> v;
            case "int" -> Long.parseLong((String) v);
            case "float" -> Double.parseDouble((String) v);
            case "number" -> ((String) v).matches("-?\\d+") ? (Object) Long.parseLong((String) v) : Double.parseDouble((String) v);
            case "time" -> NOW.plus(Duration.ofNanos(((Number) e.get("offset")).longValue()));
            case "list" -> {
                List<Object> l = new ArrayList<>();
                ((List<?>) v).forEach(x -> l.add(decode(x)));
                yield l;
            }
            case "map" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                Cast.toStringMap(v).forEach((k, x) -> m.put(k, decode(x)));
                yield m;
            }
            default -> throw new IllegalArgumentException("unknown type " + t);
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void behavesLikeVenom(String name, Map<String, Object> c) {
        Assertions assertions = new Assertions();
        Object actual = decode(c.get("actual"));
        List<Object> expected = new ArrayList<>();
        ((List<?>) c.get("expected")).forEach(x -> expected.add(decode(x)));
        boolean wantErr = (Boolean) c.get("wantErr");
        if (NUMBER_TYPE_DIFFERENCES.contains(name + " " + xyz.fokion.ivy.spi.util.GoFormat.sprint(actual))) {
            wantErr = !wantErr;
        }
        String goErr = (String) c.get("goErr");
        try {
            assertions.get((String) c.get("func")).check(actual, expected);
            if (wantErr) {
                fail("expected an error like venom's: " + goErr);
            }
        } catch (AssertException e) {
            if (!wantErr) {
                fail("unexpected error: " + e.getMessage());
            }
        }
    }

    @Test
    void userAssertionsCannotRedefineBuiltins() throws AssertException {
        Assertions assertions = new Assertions();
        assertThrows(AssertException.class, () -> assertions.registerUserAssertion("ShouldEqual", (a, e) -> { }));
        assertions.registerUserAssertion("ShouldBeCustom", (a, e) -> { });
        assertThrows(AssertException.class, () -> assertions.registerUserAssertion("ShouldBeCustom", (a, e) -> { }));
    }

    @Test
    void parsesNaturalDates() throws Exception {
        ZonedDateTime ref = ZonedDateTime.parse("2026-10-02T15:30:00+02:00");
        assertEquals(ref, NaturalDate.parse("now", ref));
        assertEquals(ref.minusMinutes(5), NaturalDate.parse("5 minutes ago", ref));
        assertEquals(ref.plusMinutes(5), NaturalDate.parse("5 minutes from now", ref));
        assertEquals(ref.plusHours(2), NaturalDate.parse("in 2 hours", ref));
        assertEquals(ZonedDateTime.parse("2026-10-03T00:00:00+02:00"), NaturalDate.parse("tomorrow", ref));
        assertEquals(ZonedDateTime.parse("2026-10-01T00:00:00+02:00"), NaturalDate.parse("yesterday", ref));
        assertEquals(ZonedDateTime.parse("2026-09-25T00:00:00+02:00"), NaturalDate.parse("last friday", ref));
        assertEquals(ZonedDateTime.parse("2026-10-09T00:00:00+02:00"), NaturalDate.parse("next friday", ref));
        assertThrows(NaturalDate.ParseException.class, () -> NaturalDate.parse("2026-13-45", ref));
    }
}
