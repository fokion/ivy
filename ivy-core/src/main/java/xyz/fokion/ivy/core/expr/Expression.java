package xyz.fokion.ivy.core.expr;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A compiled expression, such as {@code result.status == 200} or
 * {@code result.body.items.some(i => i.id == 3)}. Compiling is cached: the same source is
 * parsed once per run, however many times it is evaluated (ranges, retries).
 */
public final class Expression {

    private static final int CACHE_LIMIT = 10_000;
    private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>();

    private final String source;
    private final Node root;
    private volatile Set<String> roots;

    private Expression(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /** Parses an expression; throws {@link ExprException} on a syntax error. */
    public static Expression compile(String source) {
        Expression e = CACHE.get(source);
        if (e != null) {
            return e;
        }
        e = new Expression(source, Parser.parse(source));
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(source, e);
        return e;
    }

    public String source() {
        return source;
    }

    public Object evaluate(Scope scope) {
        return new Evaluator(source, false).eval(root, scope);
    }

    /** The value of every variable, path and call of the expression, as text and value. */
    public record Part(String text, Object value) {
    }

    /** A value with the parts that were evaluated to get it. */
    public record Traced(Object value, List<Part> parts) {
    }

    /** Evaluates, recording the values of the parts written in the expression. */
    public Traced evaluateTraced(Scope scope) {
        Evaluator ev = new Evaluator(source, true);
        Object value = ev.eval(root, scope);
        return new Traced(value, ev.trace().stream().map(t -> new Part(t.text(), t.value())).toList());
    }

    /** Whether the expression is only a variable or a path, such as {@code result.body.id}. */
    public boolean isPath() {
        return root instanceof Node.Ident || root instanceof Node.Member || root instanceof Node.Index;
    }

    /** The variables the expression reads, without the parameters of its functions. */
    public Set<String> roots() {
        Set<String> r = roots;
        if (r == null) {
            r = new LinkedHashSet<>();
            collect(root, new HashSet<>(), r);
            roots = r;
        }
        return r;
    }

    /**
     * The fields read directly on a variable: {@code root.name} and {@code root["name"]};
     * {@code members("result")} of {@code result.body.id == 1} is {@code [body]}.
     */
    public Set<String> members(String rootName) {
        Set<String> out = new LinkedHashSet<>();
        members(root, rootName, false, out);
        return out;
    }

    private static void members(Node n, String rootName, boolean shadowed, Set<String> out) {
        switch (n) {
            case Node.Member m when !shadowed && m.object() instanceof Node.Ident id && id.name().equals(rootName) ->
                    out.add(m.name());
            case Node.Index ix when !shadowed && ix.object() instanceof Node.Ident id && id.name().equals(rootName)
                    && ix.index() instanceof Node.Literal l && l.value() instanceof String name -> out.add(name);
            case Node.Arrow a -> members(a.body(), rootName, shadowed || a.param().equals(rootName), out);
            default -> children(n).forEach(c -> members(c, rootName, shadowed, out));
        }
    }

    private static List<Node> children(Node n) {
        return switch (n) {
            case Node.Literal _, Node.Ident _ -> List.of();
            case Node.Member m -> List.of(m.object());
            case Node.Index ix -> List.of(ix.object(), ix.index());
            case Node.Call c -> {
                List<Node> l = new java.util.ArrayList<>(c.args());
                l.addFirst(c.callee());
                yield l;
            }
            case Node.Unary u -> List.of(u.operand());
            case Node.Binary b -> List.of(b.left(), b.right());
            case Node.Conditional c -> List.of(c.test(), c.then(), c.otherwise());
            case Node.ArrayLiteral a -> a.items();
            case Node.ObjectLiteral o -> o.values();
            case Node.Arrow a -> List.of(a.body());
        };
    }

    private static void collect(Node n, Set<String> bound, Set<String> out) {
        switch (n) {
            case Node.Literal _ -> {
            }
            case Node.Ident id -> {
                if (!bound.contains(id.name())) {
                    out.add(id.name());
                }
            }
            case Node.Member m -> collect(m.object(), bound, out);
            case Node.Index ix -> {
                collect(ix.object(), bound, out);
                collect(ix.index(), bound, out);
            }
            case Node.Call c -> {
                if (c.callee() instanceof Node.Member m) {
                    collect(m.object(), bound, out);
                } else {
                    Node.Ident id = (Node.Ident) c.callee();// calling a function parameter
                }
                c.args().forEach(a -> collect(a, bound, out));
            }
            case Node.Unary u -> collect(u.operand(), bound, out);
            case Node.Binary b -> {
                collect(b.left(), bound, out);
                collect(b.right(), bound, out);
            }
            case Node.Conditional c -> {
                collect(c.test(), bound, out);
                collect(c.then(), bound, out);
                collect(c.otherwise(), bound, out);
            }
            case Node.ArrayLiteral a -> a.items().forEach(i -> collect(i, bound, out));
            case Node.ObjectLiteral o -> o.values().forEach(v -> collect(v, bound, out));
            case Node.Arrow a -> {
                Set<String> inner = new HashSet<>(bound);
                inner.add(a.param());
                collect(a.body(), inner, out);
            }
        }
    }

    @Override
    public String toString() {
        return source;
    }
}
