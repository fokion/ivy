package xyz.fokion.ivy.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import xyz.fokion.ivy.core.assertion.Assertions;
import xyz.fokion.ivy.core.assertion.Assertions.AssertException;
import xyz.fokion.ivy.core.assertion.Assertions.AssertFunc;
import xyz.fokion.ivy.core.dump.Dump;
import xyz.fokion.ivy.core.model.AssertionsApplied;
import xyz.fokion.ivy.core.model.Failure;
import xyz.fokion.ivy.core.model.TestCase;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.util.GoFormat;

/** Port of venom's {@code assertion.go}: applies the assertions of a step to its result. */
final class AssertionChecker {

    private final Assertions assertions;

    AssertionChecker(Assertions assertions) {
        this.assertions = assertions;
    }

    record Parsed(Object actual, AssertFunc func, List<Object> args, boolean required) {
    }

    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    AssertionsApplied apply(RunContext ctx, Object result, TestCase tc, int stepNumber, int rangedIndex,
            Map<String, Object> step, List<Object> defaultAssertions) {
        AssertionsApplied applied = new AssertionsApplied();
        Object declared = step.get("assertions");
        List<Object> list;
        if (declared == null) {
            list = new ArrayList<>();
        } else if (declared instanceof List<?> l) {
            list = new ArrayList<>(l);
        } else {
            applied.ok = false;
            applied.errors.add(new Failure(GoStrings.removeNotPrintable(
                    "error decoding assertions: 'Assertions': source data must be an array or slice, got "
                            + kind(declared))));
            return applied;
        }
        if (list.isEmpty() && defaultAssertions != null) {
            list = defaultAssertions;
        }
        Map<String, Object> executorResult = Dump.dump(result);
        boolean ok = true;
        for (int i = 0; i < list.size(); i++) {
            Object assertion = list.get(i);
            Failure f = check(ctx, tc, stepNumber, rangedIndex, i, assertion, executorResult);
            if (f != null) {
                applied.errors.add(f);
                ok = false;
            }
            applied.assertions.add(new AssertionsApplied.Applied(assertion, f == null));
        }
        if (executorResult.containsKey("result.systemerr")) {
            applied.systemerr = GoFormat.sprint(executorResult.get("result.systemerr"));
        }
        if (executorResult.containsKey("result.systemout")) {
            applied.systemout = GoFormat.sprint(executorResult.get("result.systemout"));
        }
        applied.ok = ok;
        return applied;
    }

    private static String kind(Object v) {
        return switch (v) {
            case String s -> "string";
            case Map<?, ?> m -> "map";
            case Number n -> "float64";
            case Boolean b -> "bool";
            default -> v.getClass().getSimpleName();
        };
    }

    private Failure check(RunContext ctx, TestCase tc, int stepNumber, int rangedIndex, int assertionIndex,
            Object assertion, Object result) {
        if (assertion instanceof String s) {
            return checkString(ctx, tc, stepNumber, rangedIndex, assertionIndex, s, result);
        }
        if (assertion instanceof Map<?, ?> m) {
            return checkBranch(ctx, tc, stepNumber, rangedIndex, assertionIndex, m, result);
        }
        return Failures.newFailure(ctx, tc, stepNumber, rangedIndex, assertionIndex, "",
                "unsupported assertion format: " + GoFormat.sprint(assertion));
    }

    /** A logical operator ({@code and}, {@code or}, {@code xor}, {@code not}) over assertions. */
    private Failure checkBranch(RunContext ctx, TestCase tc, int stepNumber, int rangedIndex, int assertionIndex,
            Map<?, ?> branch, Object result) {
        if (branch.size() != 1) {
            return Failures.newFailure(ctx, tc, stepNumber, rangedIndex, assertionIndex, "",
                    "expected exactly 1 logical operator but " + branch.size() + " were provided");
        }
        Map.Entry<?, ?> entry = branch.entrySet().iterator().next();
        String operator = String.valueOf(entry.getKey());
        if (!(entry.getValue() instanceof List<?> operands)) {
            return Failures.newFailure(ctx, tc, stepNumber, rangedIndex, assertionIndex, "",
                    "expected " + operator + " operands to be an []interface{}, got " + GoFormat.sprint(entry.getValue()));
        }
        if (operands.isEmpty()) {
            return null;
        }
        List<String> results = new ArrayList<>();
        int success = 0;
        for (Object operand : operands) {
            Failure f = check(ctx, tc, stepNumber, rangedIndex, assertionIndex, operand, result);
            if (f != null) {
                results.add("  - fail: " + GoFormat.sprint(operand));
            } else {
                success++;
                results.add("  - pass: " + GoFormat.sprint(operand));
            }
        }
        int count = operands.size();
        String joined = String.join("\n", results);
        String error = switch (operator) {
            case "and" -> success != count ? success + "/" + count + " assertions succeeded:\n" + joined + "\n" : null;
            case "or" -> success == 0 ? "no assertions succeeded:\n" + joined + "\n" : null;
            case "xor" -> success == 0 ? "no assertions succeeded:\n" + joined + "\n"
                    : success > 1 ? "multiple assertions succeeded but expected only one to succeed:\n" + joined + "\n" : null;
            case "not" -> success > 0 ? "some assertions succeeded but expected none to succeed:\n" + joined + "\n" : null;
            default -> "\0unsupported assertion operator " + operator;
        };
        if (error == null) {
            return null;
        }
        if (error.startsWith("\0")) {
            error = error.substring(1);
        }
        return Failures.newFailure(ctx, tc, stepNumber, rangedIndex, assertionIndex, "", error);
    }

    private Failure checkString(RunContext ctx, TestCase tc, int stepNumber, int rangedIndex, int assertionIndex,
            String assertion, Object result) {
        Parsed parsed;
        try {
            parsed = parse(assertion, result);
        } catch (ParseException e) {
            return Failures.newFailure(ctx, tc, stepNumber, rangedIndex, assertionIndex, assertion, e.getMessage());
        }
        try {
            parsed.func().check(parsed.actual(), parsed.args());
            return null;
        } catch (AssertException e) {
            Failure f = Failures.newFailure(ctx, tc, stepNumber, rangedIndex, assertionIndex, assertion, e.getMessage());
            f.assertionRequired = parsed.required();
            return f;
        }
    }

    Parsed parse(String s, Object input) throws ParseException {
        Map<String, Object> dump = Dump.dump(input);
        List<String> parts = splitAssertion(s);
        if (parts.size() < 2) {
            throw new ParseException("assertion syntax error");
        }
        Object actual = dump.get(parts.get(0));
        String name = parts.get(1);
        boolean required = false;
        if (name.startsWith("Must")) {
            required = true;
            name = "Should" + name.substring(4);
        }
        AssertFunc f = assertions.get(name);
        if (f == null) {
            throw new ParseException("assertion not supported");
        }
        List<Object> args = new ArrayList<>();
        for (String v : parts.subList(2, parts.size())) {
            try {
                args.add(stringToType(v, actual));
            } catch (IllegalArgumentException e) {
                throw new ParseException("mismatched type between '" + parts.get(0) + "' and '" + v + "': " + e.getMessage());
            }
        }
        return new Parsed(actual, f, args, required);
    }

    /** Converts an assertion argument to the type of the actual value. */
    static Object stringToType(String val, Object actual) {
        return switch (actual) {
            case Boolean b -> {
                try {
                    yield Cast.parseGoBool(val);
                } catch (Cast.CastException e) {
                    throw new IllegalArgumentException(e.getMessage());
                }
            }
            case Long l -> {
                try {
                    yield Long.parseLong(val);
                } catch (NumberFormatException e) {
                    try {
                        yield Double.parseDouble(val);
                    } catch (NumberFormatException e2) {
                        throw new IllegalArgumentException("strconv.Atoi: parsing " + GoFormat.quote(val) + ": invalid syntax");
                    }
                }
            }
            case Integer i -> {
                try {
                    yield (long) Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("strconv.Atoi: parsing " + GoFormat.quote(val) + ": invalid syntax");
                }
            }
            case Double d -> {
                try {
                    yield Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("strconv.ParseFloat: parsing " + GoFormat.quote(val) + ": invalid syntax");
                }
            }
            case null, default -> val;
        };
    }

    private static final Set<Integer> QUOTATION_MARKS = Set.of(
            0x22, 0x27, 0xAB, 0xBB, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F,
            0x2039, 0x203A, 0x2E42, 0x300C, 0x300D, 0x300E, 0x300F, 0x301D, 0x301E, 0x301F,
            0xFE41, 0xFE42, 0xFE43, 0xFE44, 0xFF02, 0xFF07, 0xFF62, 0xFF63);

    /** Splits an assertion on spaces, keeping quoted arguments together and unquoting them. */
    static List<String> splitAssertion(String a) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = null;
        int lastQuote = 0;
        for (int i = 0; i < a.length(); ) {
            int c = a.codePointAt(i);
            i += Character.charCount(c);
            boolean separator;
            if (c == lastQuote) {
                lastQuote = 0;
                separator = false;
            } else if (lastQuote != 0) {
                separator = false;
            } else if (QUOTATION_MARKS.contains(c)) {
                lastQuote = c;
                separator = false;
            } else {
                separator = GoStrings.isSpace(c);
            }
            if (separator) {
                if (current != null) {
                    fields.add(current.toString());
                    current = null;
                }
            } else {
                if (current == null) {
                    current = new StringBuilder();
                }
                current.appendCodePoint(c);
            }
        }
        if (current != null) {
            fields.add(current.toString());
        }
        List<String> out = new ArrayList<>(fields.size());
        for (String e : fields) {
            int first = e.codePointAt(0);
            int last = e.codePointBefore(e.length());
            int count = e.codePointCount(0, e.length());
            if (QUOTATION_MARKS.contains(first) && first == last && count >= 2) {
                e = e.substring(Character.charCount(first), e.length() - Character.charCount(last));
            }
            out.add(e);
        }
        return out;
    }

    /**
     * Evaluates conditions (skip, retry_if) against variables; returns the failure messages,
     * formatted with the test case name and the error.
     */
    List<String> testConditionalStatement(RunContext ctx, TestCase tc, List<String> conditions,
            Map<String, Object> vars, String text) throws ParseException {
        List<String> failures = new ArrayList<>();
        for (String condition : conditions) {
            Parsed parsed = parse(condition, vars);
            try {
                parsed.func().check(parsed.actual(), parsed.args());
            } catch (AssertException e) {
                failures.add(text.isEmpty() ? e.getMessage()
                        : String.format(text, GoFormat.quote(tc.originalName), e.getMessage()));
            }
        }
        return failures;
    }
}
