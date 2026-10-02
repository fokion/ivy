package xyz.fokion.ivy.core.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import xyz.fokion.ivy.core.template.GoTemplate.TemplateException;
import xyz.fokion.ivy.core.template.GoTemplate.Val;
import xyz.fokion.ivy.spi.Struct;

/** Ported from venom's interpolate/interpolate_test.go. */
class InterpolatorTest {

    private static Map<String, String> vars(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("default value with empty default", "aa:{{.cds.app.foo | default \"\"}}end", vars(), "aa:end"),
                Arguments.of("default value", "aa:{{.cds.app.foo | default \"bar\" }}:end", vars(), "aa:bar:end"),
                Arguments.of("default value with variables (easy)", "{{.cds.app.foo | default .val }}", vars("val", "biz"), "biz"),
                Arguments.of("default value with variables (not so easy)", "{{.cds.app.foo | default .cds.app.bar }}", vars("cds.app.bar", "biz"), "biz"),
                Arguments.of("default value with variables (so hard)", "{{.cds.app.foo | default .cds.app.bar .cds.app.biz }}", vars("cds.app.biz", "biz"), "biz"),
                Arguments.of("default value with variables (with pipeline)", "{{.cds.app.foo | default .cds.app.bar | default .cds.app.biz | upper }}", vars("cds.app.biz", "biz"), "BIZ"),
                Arguments.of("default value with pipeline", "{{.cds.app.foo | upper}}", vars(), "{{.cds.app.foo | upper}}"),
                Arguments.of("default value with pipeline", "{{.cds.app.foo | upper | lower}}", vars(), "{{.cds.app.foo | upper | lower}}"),
                Arguments.of("default value with knowned var", "aa:{{.cds.app.foo | default \"bar\"}}end", vars("cds.app.foo", "value"), "aa:valueend"),
                Arguments.of("default empty value with knowned var", "aa:{{.cds.app.foo | default \"\"}}end", vars("cds.app.foo", "value"), "aa:valueend"),
                Arguments.of("unknown function", "echo '{{\"conf\"|uvault}}'", vars(), "echo '{{\"conf\"|uvault}}'"),
                Arguments.of("simple", "a {{.cds.app.value}}", vars("cds.app.value", "value"), "a value"),
                Arguments.of("only unknown", "a value unknown {{.cds.app.foo}}", vars("cds.app.value", "value"), "a value unknown {{.cds.app.foo}}"),
                Arguments.of("simple with unknown", "a {{.cds.app.value}} and another value unknown {{.cds.app.foo}}", vars("cds.app.value", "value"), "a value and another value unknown {{.cds.app.foo}}"),
                Arguments.of("upper", "a {{.cds.app.value | upper}} and another value unknown {{.cds.app.foo}}", vars("cds.app.value", "value"), "a VALUE and another value unknown {{.cds.app.foo}}"),
                Arguments.of("title and filter on unknow", "a {{.cds.app.value | title }} and another value unknown {{.cds.app.foo | lower}}", vars("cds.app.value", "value"), "a Value and another value unknown {{.cds.app.foo | lower}}"),
                Arguments.of("many", "{{.cds.app.bar}} a {{.cds.app.valuea | upper }}, a {{.cds.app.valueb | title}}.{{.cds.app.valuec}}-{{.cds.app.foo}}", vars("cds.app.valuea", "valuea", "cds.app.valueb", "valueb", "cds.app.valuec", "valuec"), "{{.cds.app.bar}} a VALUEA, a Valueb.valuec-{{.cds.app.foo}}"),
                Arguments.of("two same unknown", "A:{{.cds.env.myenvpassword}} B:{{.cds.env.myenvpassword}}", vars(), "A:{{.cds.env.myenvpassword}} B:{{.cds.env.myenvpassword}}"),
                Arguments.of("two same unknown, but one with a filter", "A:{{.cds.env.myenvpassword}} B:{{.cds.env.myenvpassword | upper}}", vars(), "A:{{.cds.env.myenvpassword}} B:{{.cds.env.myenvpassword | upper}}"),
                Arguments.of("empty string", "a {{.cds.app.myKey}} and another key with empty value *{{.cds.app.myKeyAnother}}*", vars("cds.app.myKey", "valueKey", "cds.app.myKeyAnother", ""), "a valueKey and another key with empty value **"),
                Arguments.of("two keys with same first characters", "a {{.cds.app.myKey}} and another key value {{.cds.app.myKeyAnother}}", vars("cds.app.myKey", "valueKey", "cds.app.myKeyAnother", "valueKeyAnother"), "a valueKey and another key value valueKeyAnother"),
                Arguments.of("key with - and a unknown key", "a {{.cds.app.my-key}}.{{.cds.app.foo-key}} and another key value {{.cds.app.my-key}}", vars("cds.app.my-key", "value-key"), "a value-key.{{.cds.app.foo-key}} and another key value value-key"),
                Arguments.of("key with - and a empty key", "a {{.cds.app.my-key}}.{{.cds.app.foo-key}}.and another key value {{.cds.app.my-key}}", vars("cds.app.my-key", "value-key", "cds.app.foo-key", ""), "a value-key..and another key value value-key"),
                Arguments.of("tiret", "\"METRICS_WRITE_TOKEN\": \"{{.cds.env.metrics-exposer.write.token}}\"", vars("cds.env.metrics-exposer.write.token", "valueKey"), "\"METRICS_WRITE_TOKEN\": \"valueKey\""),
                Arguments.of("espace func", "a {{.cds.foo}} here, {{.cds.title | title}}, {{.cds.upper | upper}}, {{.cds.lower | lower}}, {{.cds.escape | escape}}", vars("cds.foo", "valbar", "cds.title", "mytitle-bis", "cds.upper", "toupper", "cds.lower", "TOLOWER", "cds.escape", "a/b.c_d"), "a valbar here, Mytitle-Bis, TOUPPER, tolower, a-b-c-d"),
                Arguments.of("config", "\n\t\t\t\t{\n\t\t\t\t\"env\": {\n\t\t\t\t\"KEYA\":\"{{.cds.env.vAppKey}}\",\n\t\t\t\t\"KEYB\": \"{{.cds.env.vAppKeyHatchery}}\",\n\t\t\t\t\"ADDR\":\"{{.cds.env.addr}}\"\n\t\t\t\t},\n\t\t\t\t\"labels\": {\n\t\t\t\t\"TOKEN\": \"{{.cds.env.token}}\",\n\t\t\t\t\"HOST\": \"cds-hatchery-marathon-{{.cds.env.name}}.{{.cds.env.vHost}}\",\n\t\t\t\t}\n\t\t\t\t}", vars("cds.env.name", "", "cds.env.token", "aValidTokenString", "cds.env.addr", "", "cds.env.vAppKey", "aValue"), "\n\t\t\t\t{\n\t\t\t\t\"env\": {\n\t\t\t\t\"KEYA\":\"aValue\",\n\t\t\t\t\"KEYB\": \"{{.cds.env.vAppKeyHatchery}}\",\n\t\t\t\t\"ADDR\":\"\"\n\t\t\t\t},\n\t\t\t\t\"labels\": {\n\t\t\t\t\"TOKEN\": \"aValidTokenString\",\n\t\t\t\t\"HOST\": \"cds-hatchery-marathon-.{{.cds.env.vHost}}\",\n\t\t\t\t}\n\t\t\t\t}"),
                Arguments.of("same prefix", "{\"HOST\": \"customer{{.cds.env.lb.prefix}}.{{.cds.env.lb}}\"}", vars("cds.env.lb", "lb", "cds.env.lb.prefix", "myprefix"), "{\"HOST\": \"customermyprefix.lb\"}"),
                Arguments.of("git.branch in payload should not be interpolated", "\n\t\tname: \"w{{.cds.pip.docker.image}}-generated\"\n\t\tversion: v1.0\n\t\tworkflow:\n\t\t  build-go:\n\t\t    pipeline: build-go-generated\n\t\t    payload:\n\t\t      git.author: \"\"\n\t\t      git.branch: master", vars("git.branch", "master", "git.author", ""), "\n\t\tname: \"w{{.cds.pip.docker.image}}-generated\"\n\t\tversion: v1.0\n\t\tworkflow:\n\t\t  build-go:\n\t\t    pipeline: build-go-generated\n\t\t    payload:\n\t\t      git.author: \"\"\n\t\t      git.branch: master"),
                Arguments.of("- inside function parameter", "name: \"coucou-{{ .name | default \"0.0.1-dirty\" }}\"", vars("git.branch", "master", "git.author", ""), "name: \"coucou-0.0.1-dirty\""),
                Arguments.of("- inside function parameter but not used", "name: \"coucou-{{ .name | default \"0.0.1-dirty\" }}\"", vars("name", "toi"), "name: \"coucou-toi\""),
                Arguments.of("- substring", "name: coucou-{{ .name | substr 0 5 }}", vars("name", "github"), "name: coucou-githu"),
                Arguments.of("- trunc", "test_{{.cds.workflow}}_{{.git.hash | trunc 8 }}", vars("cds.workflow", "myWorkflow", "git.hash", "863ddke13bfef8043960b19cec790f8b9f5435ab", "git.hash.before", "863ddke13bfef8043960b19cec790f8b9f5435ab"), "test_myWorkflow_863ddke1"),
                Arguments.of("add", "my value {{.cds.app.value | add 3}} {{ add 2 2 }}", vars("cds.app.value", "1"), "my value 4 4"),
                Arguments.of("sub", "my value {{.cds.app.value | sub 1}} {{ sub 5 1 }}", vars("cds.app.value", "5"), "my value -4 4"),
                Arguments.of("mul", "my value {{.cds.app.value | mul 2}} {{ mul 2 2 }}", vars("cds.app.value", "2"), "my value 4 4"),
                Arguments.of("div", "my value {{.cds.app.value | div 10}} {{ div 8 2 }}", vars("cds.app.value", "2"), "my value 5 4"),
                Arguments.of("mod", "my value {{.cds.app.value | mod 6}} {{ mod 10 6 }}", vars("cds.app.value", "10"), "my value 6 4"),
                Arguments.of("dirname", "{{.path | dirname}}", vars("path", "/a/b/c"), "/a/b"),
                Arguments.of("basename", "{{.path | basename}}", vars("path", "/ab/c"), "c"),
                Arguments.of("urlencode word", "{{.query | urlencode}}", vars("query", "Trollhättan"), "Trollh%C3%A4ttan"),
                Arguments.of("urlencode query", "{{.query | urlencode}}", vars("query", "zone:eq=Somewhere over the rainbow&name:like=%mydomain.localhost.local"), "zone%3Aeq%3DSomewhere+over+the+rainbow%26name%3Alike%3D%25mydomain.localhost.local"),
                Arguments.of("urlencode nothing to do", "{{.query | urlencode}}", vars("query", "patrick"), "patrick"),
                Arguments.of("ternary truthy", "{{.assert | ternary .foo .bar}}", vars("assert", "true", "bar", "bar", "foo", "foo"), "foo"),
                Arguments.of("ternary truthy integer", "{{ \"1\" | ternary .foo .bar}}", vars("bar", "bar", "foo", "foo"), "foo"),
                Arguments.of("ternary falsy", "{{.assert | ternary .foo .bar}}", vars("assert", "false", "bar", "bar", "foo", "foo"), "bar"),
                Arguments.of("ternary undef assert", "{{.assert | ternary .foo .bar}}", vars("bar", "bar", "foo", "foo"), "bar"),
                Arguments.of("camelCase variable name", "{\"staticVersionLocator\":\"{{.staticVersionLocator}}\"}", vars("staticVersionLocator", "0000000000Y009R8NV8RMTH9S4"), "{\"staticVersionLocator\":\"0000000000Y009R8NV8RMTH9S4\"}"),
                Arguments.of("camelCase variable name with lowercased key in vars map", "{\"staticVersionLocator\":\"{{.staticVersionLocator}}\"}", vars("staticversionlocator", "0000000000Y009R8NV8RMTH9S4"), "{\"staticVersionLocator\":\"{{.staticVersionLocator}}\"}")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void interpolates(String name, String input, Map<String, String> vars, String want) throws Exception {
        assertEquals(want, Interpolator.interpolate(input, vars));
    }

    private static String render(String input, Object data) throws TemplateException {
        return GoTemplate.parse("input", input, Helpers.FUNCTIONS).execute(data);
    }

    @Test
    void helpersAcceptNativeTypes() throws Exception {
        assertEquals("ex", render("{{\"text\" | substr 1 3 }}", null));
    }

    @Test
    void helpersAcceptNilValues() throws Exception {
        assertEquals("biz", render("{{.one | default .two \"biz\" }}", null));
    }

    @Test
    void helpersAcceptStructs() throws Exception {
        Struct one = Struct.builder("anon").put("name", "myName").put("age", 30L).build();
        assertEquals("{\"name\":\"myName\",\"age\":30}", render("{{.one | toJSON }}", Map.of("one", one)));
    }

    @Test
    void helpersUnwrapVals() throws Exception {
        Val two = new Val();
        two.setLeaf("1234567890");
        Val one = new Val();
        one.children().put("two", two);
        Val root = new Val();
        root.children().put("one", one);
        assertEquals("1234567", render("{{.one.two | trunc 7 }}", root));
    }

    @Test
    void unknownValuePrintsNoValue() throws Exception {
        assertEquals("<no value>", render("{{.one}}", null));
    }

    @Test
    void missingParamsFail() {
        TemplateException e = assertThrows(TemplateException.class, () -> render("{{.one | trunc}}", null));
        assertEquals("template: input:1:9: executing \"input\" at <trunc>: error calling trunc: missing params (expected: int, string)",
                e.getMessage());
    }

    @Test
    void dashesInVariableNames() throws Exception {
        assertEquals("result.headers.x-cache is a",
                Interpolator.interpolate("result.headers.x-cache is {{.result.headers.x-cache}}", vars("result.headers.x-cache", "a")));
    }

    @Test
    void stringQuote() throws Exception {
        assertEquals("content is {\\\"foo\\\": \\\"{\\\\\\\"bar\\\\\\\":\\\\\\\"baz\\\\\\\"}\\\"}",
                Interpolator.interpolate("content is {{.content | stringQuote}}", vars("content", "{\"foo\": \"{\\\"bar\\\":\\\"baz\\\"}\"}")));
    }

    @Test
    void trimMarkersAndComments() throws Exception {
        assertEquals("a|b", render("a {{- \"|\" -}} b{{/* comment */}}", null));
    }

    @Test
    void helpers() throws Exception {
        Map<String, String> v = vars("s", "Hello World", "n", "3", "j", "{\"a\":1}");
        assertEquals("hello_world", Interpolator.interpolate("{{.s | snakecase}}", v));
        assertEquals("helloWorld", Interpolator.interpolate("{{.s | camelcase}}", v));
        assertEquals("\"Hello World\"", Interpolator.interpolate("{{.s | quote}}", v));
        assertEquals("'Hello World'", Interpolator.interpolate("{{.s | squote}}", v));
        assertEquals("SGVsbG8gV29ybGQ=", Interpolator.interpolate("{{.s | b64enc}}", v));
        assertEquals("Hello World", Interpolator.interpolate("{{\"SGVsbG8gV29ybGQ=\" | b64dec}}", v));
        assertEquals("He...", Interpolator.interpolate("{{.s | abbrev 5}}", v));
        assertEquals("HW", Interpolator.interpolate("{{.s | initials}}", v));
        assertEquals("HelloWorld", Interpolator.interpolate("{{.s | nospace}}", v));
        assertEquals("hELLO wORLD", Interpolator.interpolate("{{.s | swapcase}}", v));
        assertEquals("hello world", Interpolator.interpolate("{{.s | untitle}}", v));
        assertEquals("  a\n  b", Interpolator.interpolate("{{\"a\\nb\" | indent 2}}", v));
        assertEquals("xxx", Interpolator.interpolate("{{\"x\" | repeat 3}}", v));
        assertEquals("items", Interpolator.interpolate("{{plural \"item\" \"items\" 3}}", v));
        assertEquals("\"Hello World\"", Interpolator.interpolate("{{.s | toJSON}}", v));
        assertEquals("6", Interpolator.interpolate("{{mul 2 .n}}", v));
        assertEquals("true", Interpolator.interpolate("{{\"\" | empty}}", v));
        assertEquals("b", Interpolator.interpolate("{{coalesce \"\" \"b\"}}", v));
        assertEquals("ello Worl", Interpolator.interpolate("{{.s | trimAll \"Hd\"}}", v));
        assertEquals(8, Interpolator.interpolate("{{randAlphaNum 8}}", v).length());
        assertTrue(Interpolator.interpolate("{{randNumeric 5}}", v).matches("\\d{5}"));
    }

    @Test
    void caseConversionsMatchXstrings() {
        for (List<String> c : List.of(
                List.of("FirstName", "first_name"), List.of("HTTPServer", "http_server"),
                List.of("NoHTTPS", "no_https"), List.of("GO_PATH", "go_path"), List.of("GO PATH", "go_path"),
                List.of("GO-PATH", "go_path"), List.of("http2xx", "http_2xx"), List.of("HTTP20xOK", "http_20x_ok"),
                List.of("Duration2m3s", "duration_2m3s"), List.of("Bld4Floor3rd", "bld4_floor_3rd"))) {
            assertEquals(c.get(1), CaseConversions.toSnakeCase(c.get(0)), c.get(0));
        }
        for (List<String> c : List.of(
                List.of("some_words", "someWords"), List.of("http_server", "httpServer"),
                List.of("no_https", "noHttps"), List.of("_complex__case_", "_complex_Case_"),
                List.of("some words", "someWords"), List.of("GOLANG_IS_GREAT", "golangIsGreat"))) {
            assertEquals(c.get(1), CaseConversions.toCamelCase(c.get(0)), c.get(0));
        }
    }
}
