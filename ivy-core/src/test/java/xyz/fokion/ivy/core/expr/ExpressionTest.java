package xyz.fokion.ivy.core.expr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.LazyJson;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionTest {

    private static final Scope SCOPE = Scope.of(Map.of(
            "result", Struct.builder("Result")
                    .put("status", 200L)
                    .put("stdout", "token=abc123 ok")
                    .put("exitCode", 0L)
                    .put("body", LazyJson.orText("{\"items\":[{\"id\":1,\"name\":\"a\"},{\"id\":3,\"name\":\"b\"}],\"x-y\":5}"))
                    .put("headers", Map.of("content-type", "application/json"))
                    .build(),
            "name", "ivy",
            "count", "42",
            "empty", List.of()));

    private static Object eval(String expression) {
        return Expression.compile(expression).evaluate(SCOPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "result.status == 200",
        "result.status == '200'",
        "result.status !== '200'",
        "result.status === 200",
        "count == 42",
        "count > 41.5",
        "result.stdout contains 'ok'",
        "result.stdout !contains 'ko'",
        "result.stdout matches '^token=\\w+'",
        "'ok' in result.stdout",
        "3 in result.body.items.map(i => i.id)",
        "result.body.items[0].id == 1",
        "result.body.items[-1].name == 'b'",
        "result.body.items.length == 2",
        "len(result.body.items) == 2",
        "result.body['x-y'] * 2 == 10",
        "result.headers['content-type'].startsWith('application/')",
        "result.body.items.some(i => i.id == 3)",
        "result.body.items.every(i => i.id > 0)",
        "result.body.items.find(i => i.name == 'b').id == 3",
        "result.body.items.filter(i => i.id > 1).length == 1",
        "result.stdout.match('token=(\\w+)')[1] == 'abc123'",
        "result.missing.deeper == null",
        "result.missing?.x ?? 'none' == 'none'",
        "isEmpty(empty) && !isEmpty(name)",
        "[1, 2] == [1, '2']",
        "{a: 1, b: [true]} == {b: [true], a: 1}",
        "7 / 2 == 3.5 && 6 / 2 === 3 && 7 % 2 == 1",
        "'a' + 1 == 'a1'",
        "between(result.status, 200, 299)",
        "approx(0.1 + 0.2, 0.3)",
        "date('2024-01-01T00:00:00Z') < date('2024-01-02')",
        "name.toUpperCase() == 'IVY' && name.length == 3",
        "keys(result.headers) == ['content-type']",
        "typeOf(result.body.items) == 'array' && typeOf(result) == 'object'",
        "unbase64(base64('hé')) == 'hé'",
        "json('{\"a\": [1]}').a[0] == 1",
        "result.exitCode == 0 ? true : false",
        "!(result.status >= 300 || result.status < 200)",
    })
    void evaluatesToTrue(String expression) {
        assertTrue(Values.truthy(eval(expression)), expression + " => " + eval(expression));
    }

    @Test
    void reportsSyntaxErrorsWithTheirPosition() {
        ExprException e = assertThrows(ExprException.class, () -> Expression.compile("result.status =="));
        assertEquals("unexpected end of expression at position 17 in `result.status ==`", e.getMessage());
        assertThrows(ExprException.class, () -> Expression.compile("'open"));
        assertThrows(ExprException.class, () -> Expression.compile("a b"));
        assertThrows(ExprException.class, () -> Expression.compile(""));
    }

    @Test
    void failsOnUnknownRootsOnly() {
        ExprException e = assertThrows(ExprException.class, () -> eval("nope.x == 1"));
        assertTrue(e.getMessage().startsWith("unknown variable nope"), e.getMessage());
        assertNull(eval("result.nope.x"));
        assertThrows(ExprException.class, () -> eval("unknownFunction(1)"));
        assertThrows(ExprException.class, () -> eval("name < 1"));
    }

    @Test
    void tracesTheValuesOfPathsAndCalls() {
        Expression.Traced t = Expression.compile("result.status == 201 && len(result.body.items) > 1").evaluateTraced(SCOPE);
        assertEquals(false, t.value());
        assertEquals(List.of(new Expression.Part("result.status", 200L)), t.parts());

        t = Expression.compile("result.body.items.some(i => i.id == 9)").evaluateTraced(SCOPE);
        assertEquals(1, t.parts().size(), "the parts of a call are not traced: " + t.parts());
    }

    @Test
    void listsTheVariablesRead() {
        assertEquals(Set.of("result", "limit"),
                Expression.compile("result.items.filter(i => i.id > limit).length > 0 && len(result.x) > 1").roots());
    }

    @Test
    void parsesLargeBodiesOnceAndOnlyWhenRead() {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 200_000; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"item-").append(i).append("\"}");
        }
        json.append("]}");
        LazyJson body = LazyJson.orText(json.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("body", body);
        result.put("bodyText", body.text());
        Scope scope = Scope.of(Map.of("result", result)).child(Map.of("n", 3L)).with("other", "x");

        assertEquals("x", Expression.compile("other").evaluate(scope));
        assertFalse(body.isParsed(), "nothing read the body yet");
        assertEquals(1L, Expression.compile("result.body.items[1].id").evaluate(scope));
        Object parsed = body.value();
        assertEquals("item-199999", Expression.compile("result.body.items[-1].name").evaluate(scope));
        assertEquals(200_000L, Expression.compile("result.body.items.length").evaluate(scope));
        assertSame(parsed, body.value(), "parsed once");
        // the scope hands over the values themselves, not copies
        assertSame(result, Expression.compile("result").evaluate(scope));
        assertSame(((Map<?, ?>) parsed).get("items"), Expression.compile("result.body.items").evaluate(scope));
    }
}
