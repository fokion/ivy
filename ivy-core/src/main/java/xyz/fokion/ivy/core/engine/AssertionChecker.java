package xyz.fokion.ivy.core.engine;

import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.expr.ExprException;
import xyz.fokion.ivy.core.expr.Expression;
import xyz.fokion.ivy.core.expr.Scope;
import xyz.fokion.ivy.core.expr.Template;
import xyz.fokion.ivy.core.expr.Values;
import xyz.fokion.ivy.core.model.AssertionsApplied;
import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.TestCase;

/**
 * Applies the assertions of a step to its result. An assertion is an expression that must be
 * true ({@code result.status == 200}), or a map:
 *
 * <pre>
 * - must: result.exitCode == 0        # stops the test case when false
 * - that: result.body.id == ${id}     # with variables of its own
 *   with: { id: 42 }
 * </pre>
 */
final class AssertionChecker {

    /** Values shown in failure messages are cut to this length. */
    static final int MAX_VALUE_LENGTH = 200;

    /** One assertion of a step. */
    record Assertion(String expression, boolean must, Map<String, Object> with) {

        /** Reads a declared assertion; throws {@link IllegalArgumentException} when malformed. */
        @SuppressWarnings("unchecked")
        static Assertion of(Object declared) {
            if (declared instanceof String s) {
                return new Assertion(s, false, null);
            }
            if (declared instanceof Map<?, ?> m) {
                Object must = m.get("must");
                Object that = m.get("that");
                Object with = m.get("with");
                if (with != null && !(with instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("'with' of an assertion must be a map");
                }
                for (Object k : m.keySet()) {
                    if (!List.of("must", "that", "with").contains(String.valueOf(k))) {
                        throw new IllegalArgumentException("unknown key '" + k + "' in assertion " + m
                                + " (expected 'that', 'must' or 'with')");
                    }
                }
                if (must instanceof String s && that == null) {
                    return new Assertion(s, true, (Map<String, Object>) with);
                }
                if (that instanceof String s) {
                    return new Assertion(s, Boolean.TRUE.equals(must), (Map<String, Object>) with);
                }
            }
            throw new IllegalArgumentException("an assertion is an expression such as 'result.status == 200', "
                    + "or {must: expression}, got " + declared);
        }

        /** The expression with its {@code ${...}} parts replaced by their values, as literals. */
        String source(Scope scope) {
            return Template.has(expression) ? Template.compile(expression).renderLiterals(scope) : expression;
        }
    }

    AssertionsApplied apply(RunContext ctx, Scope scope, Object result, TestCase tc, int stepNumber, int rangedIndex,
            List<?> declared, List<Object> defaultAssertions) {
        AssertionsApplied applied = new AssertionsApplied();
        List<?> list = declared == null || declared.isEmpty() ? defaultAssertions : declared;
        boolean ok = true;
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                Object raw = list.get(i);
                Failure f = check(ctx, scope, tc, stepNumber, rangedIndex, i, raw);
                if (f != null) {
                    applied.errors.add(f);
                    ok = false;
                }
                applied.assertions.add(new AssertionsApplied.Applied(raw, f == null));
            }
        }
        if (Values.member(result, "stderr") instanceof String err) {
            applied.stderr = err;
        }
        if (Values.member(result, "stdout") instanceof String out) {
            applied.stdout = out;
        }
        applied.ok = ok;
        return applied;
    }

    private Failure check(RunContext ctx, Scope scope, TestCase tc, int stepNumber, int rangedIndex, int index,
            Object raw) {
        Assertion a;
        String source = String.valueOf(raw);
        try {
            a = Assertion.of(raw);
            Scope s = a.with() == null ? scope : scope.child(castMap(Template.interpolate(a.with(), scope)));
            source = a.source(s);
            Expression.Traced t = Expression.compile(source).evaluateTraced(s);
            if (Values.truthy(t.value())) {
                return null;
            }
            StringBuilder message = new StringBuilder("assertion failed: ").append(source);
            for (Expression.Part p : t.parts()) {
                message.append("\n  ").append(p.text()).append(" = ").append(Values.describe(p.value(), MAX_VALUE_LENGTH));
            }
            Failure f = Failures.newFailure(ctx, tc, stepNumber, rangedIndex, index, source, message.toString());
            f.declared = raw;
            f.assertionRequired = a.must();
            return f;
        } catch (ExprException | IllegalArgumentException e) {
            Failure f = Failures.newFailure(ctx, tc, stepNumber, rangedIndex, index, source,
                    "cannot evaluate assertion " + source + ": " + e.getMessage());
            f.declared = raw;
            f.assertionRequired = raw instanceof Map<?, ?> m && m.containsKey("must");
            return f;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, ?>) m : Map.of();
    }
}
