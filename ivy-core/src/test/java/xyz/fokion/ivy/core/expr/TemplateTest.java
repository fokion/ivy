package xyz.fokion.ivy.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TemplateTest {

    private static final List<Object> ITEMS = List.of(1L, 2L);
    private static final Scope SCOPE = Scope.of(Map.of("base", "http://h", "user", new java.util.TreeMap<>(Map.of("id", 7L, "name", "ada")),
            "items", ITEMS, "ratio", 1.5));

    private static Object render(String template) {
        return Template.compile(template).render(SCOPE);
    }

    @Test
    void rendersTextAndValues() {
        assertEquals("http://h/users/7", render("${base}/users/${user.id}"));
        assertEquals("[1,2] {\"id\":7,\"name\":\"ada\"} 1.5", render("${items} ${user} ${ratio}"));
        assertEquals("no template", render("no template"));
        assertEquals("braces {} ${ok}", render("braces {} $${ok}"));
        assertEquals("ADA!", render("${user.name.toUpperCase() + '!'}"));
        assertEquals("{a}", render("${'{a}'}"));
    }

    @Test
    void keepsTheTypeOfASingleExpression() {
        assertSame(ITEMS, render("${items}"));
        assertEquals(7L, render("${user.id}"));
        assertEquals(7L, render(" ${user.id} ".strip()));
    }

    @Test
    void writesValuesAsLiteralsInExpressions() {
        assertEquals("result.name == \"ada\" && result.id == 7", Template.compile("result.name == ${user.name} && result.id == ${user.id}")
                .renderLiterals(SCOPE));
    }

    @Test
    void interpolatesTreesCopyingOnlyWhatChanges() {
        Map<String, Object> unchanged = Map.of("a", List.of("x"));
        Map<String, Object> step = Map.of("url", "${base}/x", "keep", unchanged, "list", List.of("${user.id}", "y"));
        Map<?, ?> out = (Map<?, ?>) Template.interpolate(step, SCOPE);
        assertEquals("http://h/x", out.get("url"));
        assertSame(unchanged, out.get("keep"));
        assertEquals(List.of(7L, "y"), out.get("list"));
        assertSame(unchanged, Template.interpolate(unchanged, SCOPE));
    }

    @Test
    void interpolatesVariablesInOrder() {
        Map<String, Object> with = new java.util.LinkedHashMap<>();
        with.put("n", "${user.id}");
        with.put("next", "${n + 1}");
        with.put("base", "${base}/v2");
        assertEquals(Map.of("n", 7L, "next", 8L, "base", "http://h/v2"), Template.interpolateInOrder(with, SCOPE));
    }

    @Test
    void explainsUnknownShellVariables() {
        ExprException e = assertThrows(ExprException.class, () -> render("echo ${HOME}"));
        assertTrue(e.getMessage().contains("to pass ${HOME} to a shell, write $${HOME}"), e.getMessage());
        assertThrows(ExprException.class, () -> Template.compile("${unclosed"));
    }

    @Test
    void listsTheVariablesRead() {
        Set<String> roots = new HashSet<>();
        Template.collectRoots(Map.of("a", "${x.y} ${z[0]}", "b", List.of("${w}", "$${not}")), roots);
        assertEquals(Set.of("x", "z", "w"), roots);
    }
}
