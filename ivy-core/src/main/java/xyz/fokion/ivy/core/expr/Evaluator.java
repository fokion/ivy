package xyz.fokion.ivy.core.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

import xyz.fokion.ivy.core.util.GoStrings;

/**
 * Evaluates a syntax tree. With tracing on, it records the value of each variable, path and
 * call written in the expression (not their inner parts), for failure messages.
 */
final class Evaluator {

    /** The value of a part of the expression. */
    record Traced(String text, Object value) {
    }

    private final String source;
    private final List<Traced> trace;
    /** above 0 inside paths, calls and functions: their parts are not traced */
    private int quiet;

    Evaluator(String source, boolean tracing) {
        this.source = source;
        this.trace = tracing ? new ArrayList<>() : null;
    }

    List<Traced> trace() {
        return trace == null ? List.of() : trace;
    }

    Object evaluateIn(Node n, Scope scope) {
        quiet++;
        try {
            return eval(n, scope);
        } finally {
            quiet--;
        }
    }

    Object eval(Node n, Scope scope) {
        return switch (n) {
            case Node.Literal l -> l.value();
            case Node.Ident id -> record(n, variable(id, scope));
            case Node.Member m -> {
                Object object = quietly(m.object(), scope);
                yield record(n, Values.member(object, m.name()));
            }
            case Node.Index ix -> {
                Object object = quietly(ix.object(), scope);
                Object key = quietly(ix.index(), scope);
                yield record(n, Values.index(object, key));
            }
            case Node.Call c -> record(n, call(c, scope));
            case Node.Unary u -> unary(u, scope);
            case Node.Binary b -> binary(b, scope);
            case Node.Conditional c -> Values.truthy(eval(c.test(), scope)) ? eval(c.then(), scope)
                    : eval(c.otherwise(), scope);
            case Node.ArrayLiteral a -> {
                List<Object> out = new ArrayList<>(a.items().size());
                for (Node item : a.items()) {
                    out.add(eval(item, scope));
                }
                yield out;
            }
            case Node.ObjectLiteral o -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (int i = 0; i < o.keys().size(); i++) {
                    out.put(o.keys().get(i), eval(o.values().get(i), scope));
                }
                yield out;
            }
            case Node.Arrow a -> new Values.Lambda(a.param(), a.body(), scope, this);
        };
    }

    private Object quietly(Node n, Scope scope) {
        quiet++;
        try {
            return eval(n, scope);
        } finally {
            quiet--;
        }
    }

    private Object record(Node n, Object value) {
        if (trace != null && quiet == 0) {
            String text = source.substring(n.start(), n.end());
            if (trace.stream().noneMatch(t -> t.text().equals(text))) {
                trace.add(new Traced(text, value));
            }
        }
        return value;
    }

    private Object variable(Node.Ident id, Scope scope) {
        Object v = scope.lookup(id.name());
        if (v == Scope.MISSING) {
            throw new ExprException("unknown variable " + id.name(), source, id.start());
        }
        return v;
    }

    private Object call(Node.Call c, Scope scope) {
        List<Object> args = new ArrayList<>(c.args().size());
        quiet++;
        try {
            for (Node a : c.args()) {
                args.add(eval(a, scope));
            }
            if (c.callee() instanceof Node.Member m) {
                Object target = eval(m.object(), scope);
                return Functions.method(target, m.name(), args, source, m.start());
            }
            Node.Ident id = (Node.Ident) c.callee();
            Object local = scope.lookup(id.name());
            if (local instanceof Values.Lambda l) {
                return l.call(args.isEmpty() ? null : args.getFirst());
            }
            return Functions.function(id.name(), args, source, id.start());
        } finally {
            quiet--;
        }
    }

    private Object unary(Node.Unary u, Scope scope) {
        Object v = eval(u.operand(), scope);
        return switch (u.op()) {
            case "!" -> !Values.truthy(v);
            case "-" -> negate(number(v, u.operand()));
            case "+" -> number(v, u.operand());
            default -> throw new ExprException("unknown operator " + u.op(), source, u.start());
        };
    }

    private static Number negate(Number n) {
        return n instanceof Long l ? (Number) (-l) : (Number) (-n.doubleValue());
    }

    private Number number(Object v, Node at) {
        Number n = Values.toNumber(v);
        if (n == null) {
            throw new ExprException("expected a number but " + source.substring(at.start(), at.end()) + " is "
                    + Values.typeOf(v) + " " + Values.describe(v, 50), source, at.start());
        }
        return n;
    }

    private Object binary(Node.Binary b, Scope scope) {
        switch (b.op()) {
            case "&&" -> {
                Object left = eval(b.left(), scope);
                return Values.truthy(left) ? eval(b.right(), scope) : left;
            }
            case "||" -> {
                Object left = eval(b.left(), scope);
                return Values.truthy(left) ? left : eval(b.right(), scope);
            }
            case "??" -> {
                Object left = eval(b.left(), scope);
                return Values.unwrap(left) != null ? left : eval(b.right(), scope);
            }
            default -> {
            }
        }
        Object left = eval(b.left(), scope);
        Object right = eval(b.right(), scope);
        return switch (b.op()) {
            case "==" -> Values.looseEquals(left, right);
            case "!=" -> !Values.looseEquals(left, right);
            case "===" -> Values.strictEquals(left, right);
            case "!==" -> !Values.strictEquals(left, right);
            case "<" -> compare(left, right, b) < 0;
            case "<=" -> compare(left, right, b) <= 0;
            case ">" -> compare(left, right, b) > 0;
            case ">=" -> compare(left, right, b) >= 0;
            case "contains" -> Values.contains(left, right);
            case "!contains" -> !Values.contains(left, right);
            case "in" -> Values.contains(right, left);
            case "!in" -> !Values.contains(right, left);
            case "matches" -> matches(left, right, b);
            case "!matches" -> !matches(left, right, b);
            case "+" -> plus(left, right, b);
            case "-", "*", "/", "%" -> arithmetic(b.op(), number(left, b.left()), number(right, b.right()), b);
            default -> throw new ExprException("unknown operator " + b.op(), source, b.start());
        };
    }

    private int compare(Object left, Object right, Node.Binary b) {
        try {
            return Values.compare(left, right);
        } catch (ExprException e) {
            throw new ExprException(e.getMessage(), source, b.start());
        }
    }

    private boolean matches(Object left, Object right, Node.Binary b) {
        Object l = Values.unwrap(left);
        if (l == null) {
            return false;
        }
        try {
            return GoStrings.compileRegex(Values.display(right)).matcher(Values.display(l)).find();
        } catch (PatternSyntaxException e) {
            throw new ExprException("invalid regular expression: " + e.getDescription(), source, b.right().start());
        }
    }

    private Object plus(Object left, Object right, Node.Binary b) {
        Object l = Values.unwrap(left);
        Object r = Values.unwrap(right);
        if (l instanceof String || r instanceof String) {
            return Values.display(l) + Values.display(r);
        }
        if (l instanceof List<?> x && r instanceof List<?> y) {
            List<Object> out = new ArrayList<>(x);
            out.addAll(y);
            return out;
        }
        return arithmetic("+", number(l, b.left()), number(r, b.right()), b);
    }

    private Number arithmetic(String op, Number x, Number y, Node.Binary b) {
        if (x instanceof Long a && y instanceof Long c) {
            long p = a;
            long q = c;
            try {
                return longArithmetic(op, p, q, b);
            } catch (ArithmeticException overflow) {
                // continue with doubles
            }
        }
        double p = x.doubleValue();
        double q = y.doubleValue();
        return switch (op) {
            case "+" -> p + q;
            case "-" -> p - q;
            case "*" -> p * q;
            case "/" -> {
                if (q == 0) {
                    throw new ExprException("division by zero", source, b.start());
                }
                yield p / q;
            }
            case "%" -> p % q;
            default -> throw new ExprException("unknown operator " + op, source, b.start());
        };
    }

    private Number longArithmetic(String op, long p, long q, Node.Binary b) {
        {
            switch (op) {
                case "+" -> {
                    return Math.addExact(p, q);
                }
                case "-" -> {
                    return Math.subtractExact(p, q);
                }
                case "*" -> {
                    return Math.multiplyExact(p, q);
                }
                case "/" -> {
                    if (q == 0) {
                        throw new ExprException("division by zero", source, b.start());
                    }
                    return p % q == 0 ? (Number) (p / q) : (Number) ((double) p / q);
                }
                case "%" -> {
                    if (q == 0) {
                        throw new ExprException("division by zero", source, b.start());
                    }
                    return p % q;
                }
                default -> {
                }
            }
        }
        throw new ExprException("unknown operator " + op, source, b.start());
    }
}
