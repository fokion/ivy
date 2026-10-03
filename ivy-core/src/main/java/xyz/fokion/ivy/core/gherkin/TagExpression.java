package xyz.fokion.ivy.core.gherkin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cucumber tag expressions: {@code @smoke and not (@slow or @flaky)}. Precedence: {@code not},
 * then {@code and}, then {@code or}.
 */
public final class TagExpression {

    private interface Node {
        boolean eval(Set<String> tags);
    }

    private final Node root;
    private final String text;

    private TagExpression(String text, Node root) {
        this.text = text;
        this.root = root;
    }

    /** Matches every tag set. */
    public static final TagExpression ALL = new TagExpression("", tags -> true);

    public static TagExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return ALL;
        }
        List<String> tokens = tokenize(expression);
        int[] pos = {0};
        Node n = or(tokens, pos);
        if (pos[0] != tokens.size()) {
            throw new IllegalArgumentException("invalid tag expression \"" + expression + "\": unexpected \""
                    + tokens.get(pos[0]) + "\"");
        }
        return new TagExpression(expression, n);
    }

    public boolean matches(Set<String> tags) {
        return root.eval(tags);
    }

    @Override
    public String toString() {
        return text;
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                if (c != ' ' && !Character.isWhitespace(c)) {
                    out.add(String.valueOf(c));
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static Node or(List<String> t, int[] pos) {
        Node left = and(t, pos);
        while (pos[0] < t.size() && t.get(pos[0]).equals("or")) {
            pos[0]++;
            Node l = left;
            Node r = and(t, pos);
            left = tags -> l.eval(tags) || r.eval(tags);
        }
        return left;
    }

    private static Node and(List<String> t, int[] pos) {
        Node left = not(t, pos);
        while (pos[0] < t.size() && t.get(pos[0]).equals("and")) {
            pos[0]++;
            Node l = left;
            Node r = not(t, pos);
            left = tags -> l.eval(tags) && r.eval(tags);
        }
        return left;
    }

    private static Node not(List<String> t, int[] pos) {
        if (pos[0] < t.size() && t.get(pos[0]).equals("not")) {
            pos[0]++;
            Node n = not(t, pos);
            return tags -> !n.eval(tags);
        }
        return primary(t, pos);
    }

    private static Node primary(List<String> t, int[] pos) {
        if (pos[0] >= t.size()) {
            throw new IllegalArgumentException("invalid tag expression: unexpected end");
        }
        String tok = t.get(pos[0]++);
        if (tok.equals("(")) {
            Node n = or(t, pos);
            if (pos[0] >= t.size() || !t.get(pos[0]).equals(")")) {
                throw new IllegalArgumentException("invalid tag expression: missing )");
            }
            pos[0]++;
            return n;
        }
        if (!tok.startsWith("@")) {
            throw new IllegalArgumentException("invalid tag expression: expected a tag, got \"" + tok + "\"");
        }
        return tags -> tags.contains(tok);
    }
}
