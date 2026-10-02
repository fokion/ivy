package xyz.fokion.ivy.core.assertion;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.core.util.Cast.CastException;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.GoFormat;
import xyz.fokion.ivy.spi.util.Json;

/**
 * venom's assertion functions ({@code ShouldEqual}, {@code ShouldContain}...) with their
 * messages. A registry instance also holds user assertions defined by user executors.
 */
public final class Assertions {

    /** An assertion: returns normally when it holds, throws otherwise. */
    @FunctionalInterface
    public interface AssertFunc {
        void check(Object actual, List<Object> expected) throws AssertException;
    }

    public static class AssertException extends Exception {
        public AssertException(String message) {
            super(message);
        }
    }

    /** Wrong usage of an assertion (argument count, types), venom's {@code AssertionError}. */
    public static final class UsageException extends AssertException {
        public UsageException(String message) {
            super(message);
        }
    }

    private static final Map<String, AssertFunc> BUILTIN = new LinkedHashMap<>();

    private final Map<String, AssertFunc> functions = new LinkedHashMap<>(BUILTIN);

    public AssertFunc get(String name) {
        return functions.get(name);
    }

    public void registerUserAssertion(String name, AssertFunc f) throws AssertException {
        if (functions.containsKey(name)) {
            throw new AssertException("cannot redefine existing assertion \"" + name + "\"");
        }
        functions.put(name, f);
    }

    public static boolean isBuiltin(String name) {
        return BUILTIN.containsKey(name);
    }

    static {
        BUILTIN.put("ShouldEqual", Assertions::shouldEqual);
        BUILTIN.put("ShouldNotEqual", (a, e) -> {
            try {
                shouldEqual(a, e);
            } catch (AssertException ex) {
                return;
            }
            throw fail("not expected: %v got: %v", e.isEmpty() ? null : e.getFirst(), a);
        });
        BUILTIN.put("ShouldAlmostEqual", (a, e) -> {
            need(2, e);
            double af = toFloat(a);
            double ef = toFloat(e.get(0));
            double delta = toFloat(e.get(1));
            double actualDelta = Math.abs(af - ef);
            if (actualDelta <= delta) {
                return;
            }
            throw fail("expected: %v(+/- %v) got: %v (%v)", ef, delta, af, actualDelta);
        });
        BUILTIN.put("ShouldNotAlmostEqual", (a, e) -> {
            need(2, e);
            double af = toFloat(a);
            double ef = toFloat(e.get(0));
            double delta = toFloat(e.get(1));
            if (Math.abs(af - ef) >= delta) {
                return;
            }
            throw fail("not expected: %v(+/- %v) got: %v (%v)", e.get(0), e.get(1), a, Math.abs(af - ef));
        });
        BUILTIN.put("ShouldNotExist", (a, e) -> {
            if (a != null) {
                throw fail("expected not exist but it was");
            }
        });
        BUILTIN.put("ShouldBeNil", (a, e) -> {
            need(0, e);
            if (a != null) {
                throw fail("expected: Nil but is wasn't");
            }
        });
        BUILTIN.put("ShouldNotBeNil", (a, e) -> {
            need(0, e);
            if (a == null) {
                throw fail("expected: Not Nil but is was");
            }
        });
        BUILTIN.put("ShouldBeTrue", (a, e) -> {
            need(0, e);
            if (!toBool(a)) {
                throw fail("expected: True but is wasn't");
            }
        });
        BUILTIN.put("ShouldBeFalse", (a, e) -> {
            need(0, e);
            if (toBool(a)) {
                throw fail("expected: False but is wasn't");
            }
        });
        BUILTIN.put("ShouldBeZeroValue", (a, e) -> {
            need(0, e);
            boolean zero = switch (a) {
                case null -> true;
                case String s -> s.isEmpty();
                case Number n -> n.doubleValue() == 0;
                case Boolean b -> !b;
                default -> false;
            };
            if (!zero) {
                throw fail("expected: Zero Value but is wasn't");
            }
        });
        BUILTIN.put("ShouldBeGreaterThan", (a, e) -> compare(a, e, c -> c > 0, "expected: %v greater than %v but it wasn't"));
        BUILTIN.put("ShouldBeGreaterThanOrEqualTo", (a, e) -> compare(a, e, c -> c >= 0, "expected: %v greater than or equals to %v but it wasn't"));
        BUILTIN.put("ShouldBeLessThan", (a, e) -> compare(a, e, c -> c < 0, "expected: %v less than %v but it wasn't"));
        BUILTIN.put("ShouldBeLessThanOrEqualTo", (a, e) -> compare(a, e, c -> c <= 0, "expected: %v less than or equals to %v but it wasn't",
                "expected '%v' less than or equals to %v but it wasn't"));
        BUILTIN.put("ShouldBeBetween", Assertions::shouldBeBetween);
        BUILTIN.put("ShouldNotBeBetween", (a, e) -> {
            try {
                shouldBeBetween(a, e);
            } catch (UsageException ex) {
                throw ex;
            } catch (AssertException ex) {
                return;
            }
            throw fail("expected '%v' not between %v and %v but it was", a, e.get(0), e.get(1));
        });
        BUILTIN.put("ShouldBeBetweenOrEqual", Assertions::shouldBeBetweenOrEqual);
        BUILTIN.put("ShouldNotBeBetweenOrEqual", (a, e) -> {
            try {
                shouldBeBetweenOrEqual(a, e);
            } catch (UsageException ex) {
                throw ex;
            } catch (AssertException ex) {
                return;
            }
            throw fail("expected '%v' not between or equal to %v and %v but it was", a, e.get(0), e.get(1));
        });
        BUILTIN.put("ShouldContain", (a, e) -> {
            need(1, e);
            if (!(a instanceof Collection<?> c)) {
                shouldEqual(a, List.of(e.getFirst()));
                return;
            }
            for (Object item : c) {
                if (equalsAssertion(item, e.getFirst())) {
                    return;
                }
            }
            throw fail("expected '%v' contain %v but it wasnt", a, e.getFirst());
        });
        BUILTIN.put("ShouldNotContain", (a, e) -> {
            need(1, e);
            for (Object item : toSlice(a)) {
                if (equalsAssertion(item, e.getFirst())) {
                    throw fail("expected '%v' not contain %v but it was", a, e.getFirst());
                }
            }
        });
        BUILTIN.put("ShouldJSONContain", (a, e) -> {
            need(1, e);
            for (Object item : toSlice(a)) {
                if (jsonEquals(item, e.getFirst())) {
                    return;
                }
            }
            throw fail("expected '%v' to contain %v but it wasnt", a, e.getFirst());
        });
        BUILTIN.put("ShouldNotJSONContain", (a, e) -> {
            need(1, e);
            for (Object item : toSlice(a)) {
                if (jsonEquals(item, e.getFirst())) {
                    throw fail("expected '%v' not contain %v but it was", a, e.getFirst());
                }
            }
        });
        BUILTIN.put("ShouldJSONContainWithKey", (a, e) -> {
            need(2, e);
            List<Object> slice = toSlice(a);
            String key = expectKey(e);
            for (Object item : slice) {
                Map<String, Object> elem = mapOrStruct(item, false);
                if (elem.containsKey(key) && jsonEquals(elem.get(key), e.get(1))) {
                    return;
                }
            }
            throw fail("expected '%v' contain a key '%s' with value %v but it wasnt", a, key, e.get(1));
        });
        BUILTIN.put("ShouldJSONContainAllWithKey", (a, e) -> {
            need(2, e);
            List<Object> slice = toSlice(a);
            String key = expectKey(e);
            for (Object item : slice) {
                Map<String, Object> elem = mapOrStruct(item, false);
                if (elem.containsKey(key) && !jsonEquals(elem.get(key), e.get(1))) {
                    throw fail("expected '%v' contain a key '%s' with value %v but it wasnt", a, key, e.get(1));
                }
            }
        });
        BUILTIN.put("ShouldNotJSONContainWithKey", (a, e) -> {
            need(2, e);
            List<Object> slice = toSlice(a);
            String key = expectKey(e);
            for (Object item : slice) {
                Map<String, Object> elem = mapOrStruct(item, true);
                if (elem.containsKey(key) && jsonEquals(elem.get(key), e.get(1))) {
                    throw fail("expected '%v' not contain a key '%s' with value %v but it was", a, key, e.get(1));
                }
            }
        });
        BUILTIN.put("ShouldContainKey", (a, e) -> {
            need(1, e);
            for (String k : toStringMap(a).keySet()) {
                if (equalsAssertion(k, e.getFirst())) {
                    return;
                }
            }
            throw fail("expected '%v' contain key %v but it wasnt", a, e.getFirst());
        });
        BUILTIN.put("ShouldNotContainKey", (a, e) -> {
            need(1, e);
            for (String k : toStringMap(a).keySet()) {
                if (equalsAssertion(k, e.getFirst())) {
                    throw fail("expected '%v' not contain key %v but it was", a, e.getFirst());
                }
            }
        });
        BUILTIN.put("ShouldBeIn", Assertions::shouldBeIn);
        BUILTIN.put("ShouldNotBeIn", (a, e) -> {
            atLeast(1, e);
            try {
                shouldBeIn(a, e);
            } catch (AssertException ex) {
                return;
            }
            throw fail("expected '%v' not in %v but it was", a, e);
        });
        BUILTIN.put("ShouldBeEmpty", Assertions::shouldBeEmpty);
        BUILTIN.put("ShouldNotBeEmpty", (a, e) -> {
            need(0, e);
            try {
                shouldBeEmpty(a, List.of());
            } catch (AssertException ex) {
                return;
            }
            throw fail("expected '%v' not to be empty but it wasn't", a);
        });
        BUILTIN.put("ShouldHaveLength", (a, e) -> {
            need(1, e);
            long length = toLong(e.getFirst());
            int actualLength = switch (a) {
                case String s -> s.getBytes(StandardCharsets.UTF_8).length;
                case Collection<?> c -> c.size();
                case Map<?, ?> m -> m.size();
                case null, default -> 0;
            };
            boolean measurable = a instanceof String || a instanceof Collection || a instanceof Map;
            if (measurable && actualLength == length) {
                return;
            }
            throw fail("expected '%v' have length of %d but it wasn't (%d)", a, length, (long) actualLength);
        });
        BUILTIN.put("ShouldStartWith", (a, e) -> {
            need(1, e);
            String s = toStr(a);
            String prefix = toStr(e.getFirst());
            if (!s.startsWith(prefix)) {
                throw fail("expected '%v' have prefix %q but it wasn't", s, prefix);
            }
        });
        BUILTIN.put("ShouldNotStartWith", (a, e) -> {
            need(1, e);
            String s = toStr(a);
            String prefix = toStr(e.getFirst());
            if (s.startsWith(prefix)) {
                throw fail("expected '%v' not have prefix %q but it was", s, prefix);
            }
        });
        BUILTIN.put("ShouldEndWith", (a, e) -> {
            need(1, e);
            String s = toStr(a);
            String suffix = toStr(e.getFirst());
            if (!s.endsWith(suffix)) {
                throw fail("expected '%v' have suffix %q but it wasn't", s, suffix);
            }
        });
        BUILTIN.put("ShouldNotEndWith", (a, e) -> {
            need(1, e);
            String s = toStr(a);
            String suffix = toStr(e.getFirst());
            if (s.endsWith(suffix)) {
                throw fail("expected '%v' not have suffix %q but it was", s, suffix);
            }
        });
        BUILTIN.put("ShouldBeBlank", (a, e) -> {
            need(0, e);
            String s = toStr(a);
            if (!s.isEmpty()) {
                throw fail("expected '%v' to be blank but it wasn't", s);
            }
        });
        BUILTIN.put("ShouldNotBeBlank", (a, e) -> {
            need(0, e);
            if (toStr(a).isEmpty()) {
                throw fail("expected value to not be blank but it was");
            }
        });
        BUILTIN.put("ShouldContainSubstring", (a, e) -> {
            String s = toStr(a);
            String ss = joinedArgs(e);
            if (!s.contains(ss)) {
                throw fail("expected '%v' to contain '%v' but it wasn't", s, ss);
            }
        });
        BUILTIN.put("ShouldNotContainSubstring", (a, e) -> {
            String s = toStr(a);
            String ss = joinedArgs(e);
            if (s.contains(ss)) {
                throw fail("expected '%v' to not contain '%v' but it was", s, ss);
            }
        });
        BUILTIN.put("ShouldEqualTrimSpace", (a, e) -> shouldEqual(GoStrings.trimSpace(toStr(a)), e));
        BUILTIN.put("ShouldHappenBefore", (a, e) -> {
            need(1, e);
            ZonedDateTime at = time(a);
            ZonedDateTime et = time(e.getFirst());
            if (!at.isBefore(et)) {
                throw fail("expected '%v' to be before '%v'", goTime(at), goTime(et));
            }
        });
        BUILTIN.put("ShouldHappenOnOrBefore", (a, e) -> {
            need(1, e);
            ZonedDateTime at = time(a);
            ZonedDateTime et = time(e.getFirst());
            if (at.isAfter(et)) {
                throw fail("expected '%v' to be before on on '%v'", goTime(at), goTime(et));
            }
        });
        BUILTIN.put("ShouldHappenAfter", (a, e) -> {
            need(1, e);
            ZonedDateTime at = time(a);
            ZonedDateTime et = time(e.getFirst());
            if (!at.isAfter(et)) {
                throw fail("expected '%v' to be after '%v'", goTime(at), goTime(et));
            }
        });
        BUILTIN.put("ShouldHappenOnOrAfter", (a, e) -> {
            need(1, e);
            ZonedDateTime at = time(a);
            ZonedDateTime et = time(e.getFirst());
            if (at.isBefore(et)) {
                throw fail("expected '%v' to be before or on '%v'", goTime(at), goTime(et));
            }
        });
        BUILTIN.put("ShouldHappenBetween", (a, e) -> {
            need(2, e);
            ZonedDateTime at = time(a);
            ZonedDateTime min = time(e.get(0));
            ZonedDateTime max = time(e.get(1));
            if (!(at.isAfter(min) && at.isBefore(max))) {
                throw fail("expected '%v' to be between '%v' and '%v' ", goTime(at), goTime(min), goTime(max));
            }
        });
        BUILTIN.put("ShouldTimeEqual", (a, e) -> {
            need(1, e);
            ZonedDateTime at = time(a);
            ZonedDateTime et = time(e.getFirst());
            if (!at.toInstant().equals(et.toInstant())) {
                throw fail("expected '%v' to be time equals to '%v' ", goTime(at), goTime(et));
            }
        });
        BUILTIN.put("ShouldJSONEqual", Assertions::shouldJSONEqual);
        BUILTIN.put("ShouldNotJSONEqual", (a, e) -> {
            need(1, e);
            try {
                shouldJSONEqual(a, e);
            } catch (AssertException ex) {
                return;
            }
            throw fail("expected %v to not be JSON equals to %v", a, e.getFirst());
        });
        BUILTIN.put("ShouldBeArray", (a, e) -> {
            need(0, e);
            if (!(a instanceof Collection)) {
                throw fail("expected: %v to be an array but was not", a);
            }
        });
        BUILTIN.put("ShouldBeMap", (a, e) -> {
            need(0, e);
            try {
                toStringMap(a);
            } catch (AssertException ex) {
                throw fail("expected: %v to be a map but was not", a);
            }
        });
        BUILTIN.put("ShouldMatchRegex", (a, e) -> {
            if (e.size() != 1) {
                throw fail("expected one regex pattern");
            }
            String regex;
            try {
                regex = Cast.toStringStrict(e.getFirst());
            } catch (CastException ex) {
                throw fail("expected a string for regex pattern");
            }
            Pattern p;
            try {
                p = Pattern.compile(regex);
            } catch (PatternSyntaxException ex) {
                throw new AssertException("error parsing regexp: " + ex.getDescription() + ": `" + regex + "`");
            }
            if (!p.matcher(toStr(a)).find()) {
                throw fail("value %v not matching pattern : %v", a, e.getFirst());
            }
        });
    }

    // ------------------------------------------------------------ shared implementations

    private static void shouldEqual(Object actual, List<Object> expected) throws AssertException {
        if (!expected.isEmpty()) {
            // several arguments are considered as words of one string
            StringBuilder args = new StringBuilder();
            for (Object e : expected) {
                args.append(toStr(e)).append(' ');
            }
            if (deepEqual(actual, stripTrailingSpaces(args.toString()))) {
                return;
            }
            throw fail("expected: %v got: %v", args.toString(), actual);
        }
        need(1, expected);
    }

    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean equalsAssertion(Object actual, Object expected) {
        try {
            shouldEqual(actual, List.of(expected));
            return true;
        } catch (AssertException e) {
            return false;
        }
    }

    static boolean deepEqual(Object x, Object y) {
        if (Objects.equals(x, y)) {
            return true;
        }
        return GoFormat.sprint(x).equals(GoFormat.sprint(y));
    }

    private interface Comparison {
        boolean test(int c);
    }

    private static void compare(Object actual, List<Object> expected, Comparison ok, String message) throws AssertException {
        compare(actual, expected, ok, message, message);
    }

    private static void compare(Object actual, List<Object> expected, Comparison ok, String stringMessage,
            String numberMessage) throws AssertException {
        need(1, expected);
        Object exp = expected.getFirst();
        if (!sameTypes(actual, exp)) {
            throw new UsageException("This assertion requires 2 values of same types.");
        }
        double actualF;
        try {
            actualF = Cast.toDoubleStrict(actual);
        } catch (CastException e) {
            String as = toStr(actual);
            String es = toStr(exp);
            if (ok.test(Integer.signum(as.compareTo(es)))) {
                return;
            }
            throw fail(stringMessage, actual, exp);
        }
        double expectedF = toFloat(exp);
        if (ok.test(Double.compare(actualF, expectedF))) {
            return;
        }
        throw fail(numberMessage, actual, exp);
    }

    private static boolean sameTypes(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            return true;
        }
        if (a instanceof Map && b instanceof Map || a instanceof Collection && b instanceof Collection) {
            return true;
        }
        return a.getClass() == b.getClass();
    }

    private static void shouldBeBetween(Object actual, List<Object> expected) throws AssertException {
        need(2, expected);
        if (!sameTypes(expected.get(0), expected.get(1))) {
            throw new UsageException("This assertion requires 2 values of same types.");
        }
        boolean lower = passes("ShouldBeLessThan", actual, expected.get(1));
        boolean upper = passes("ShouldBeGreaterThan", actual, expected.get(0));
        if (!lower || !upper) {
            throw fail("expected '%v' between %v and %v but it wasn't", actual, expected.get(0), expected.get(1));
        }
    }

    private static void shouldBeBetweenOrEqual(Object actual, List<Object> expected) throws AssertException {
        need(2, expected);
        if (!sameTypes(expected.get(0), expected.get(1))) {
            throw new UsageException("This assertion requires 2 values of same types.");
        }
        boolean lower = passes("ShouldBeLessThanOrEqualTo", actual, expected.get(1));
        boolean upper = passes("ShouldBeGreaterThanOrEqualTo", actual, expected.get(0));
        if (!lower || !upper) {
            throw fail("expected '%v' between %v and %v but it wasn't", actual, expected.get(0), expected.get(1));
        }
    }

    private static boolean passes(String name, Object actual, Object expected) {
        try {
            BUILTIN.get(name).check(actual, List.of(expected));
            return true;
        } catch (AssertException e) {
            return false;
        }
    }

    private static void shouldBeIn(Object actual, List<Object> expected) throws AssertException {
        atLeast(1, expected);
        for (Object e : expected) {
            if (equalsAssertion(e, actual)) {
                return;
            }
        }
        throw fail("expected '%v' in %v but it wasnt", actual, expected);
    }

    private static void shouldBeEmpty(Object actual, List<Object> expected) throws AssertException {
        need(0, expected);
        boolean empty = switch (actual) {
            case null -> true;
            case String s -> s.isEmpty();
            case Collection<?> c -> c.isEmpty();
            case Map<?, ?> m -> m.isEmpty();
            default -> false;
        };
        if (!empty) {
            throw fail("expected '%v' to be empty but it wasn't", actual);
        }
    }

    private static void shouldJSONEqual(Object actual, List<Object> expected) throws AssertException {
        need(1, expected);
        switch (actual) {
            case Map<?, ?> m -> {
                Object actualNorm = Json.parse(Json.write(m));
                Object expectedMap = parseJson(toStr(expected.getFirst()), true);
                if (jsonDeepEquals(actualNorm, expectedMap)) {
                    return;
                }
                throw fail("expected '%v' to be JSON equals to '%v' ", actualNorm, expectedMap);
            }
            case Collection<?> c -> {
                Object actualNorm = Json.parse(Json.write(c));
                Object expectedSlice = parseJson(toStr(expected.getFirst()), false);
                if (jsonDeepEquals(actualNorm, expectedSlice)) {
                    return;
                }
                throw fail("expected '%v' to be JSON equals to '%v' ", actualNorm, expectedSlice);
            }
            case String s -> {
                String es = toStr(expected.getFirst());
                if (s.equals(es) || (s.isEmpty() && es.equals("null"))) {
                    return;
                }
                throw fail("expected '%v' to be JSON equals to '%v' ", s, es);
            }
            case Number n -> {
                double af = n.doubleValue();
                double ef = toFloat(expected.getFirst());
                if (af == ef) {
                    return;
                }
                throw fail("expected '%v' to be JSON equals to '%v' ", af, ef);
            }
            case Boolean b -> {
                boolean eb = toBool(expected.getFirst());
                if (b == eb) {
                    return;
                }
                throw fail("expected '%v' to be JSON equals to '%v' ", b, eb);
            }
            case null, default -> throw new AssertException("unexpected type for actual: "
                    + (actual == null ? "<nil>" : actual.getClass().getSimpleName()));
        }
    }

    private static Object parseJson(String s, boolean object) throws AssertException {
        Object v;
        try {
            v = Json.parse(s);
        } catch (Json.JsonException e) {
            throw new AssertException(e.getMessage());
        }
        if (object && !(v instanceof Map)) {
            throw new AssertException("json: cannot unmarshal into Go value of type map[string]interface {}");
        }
        if (!object && !(v instanceof List)) {
            throw new AssertException("json: cannot unmarshal into Go value of type []interface {}");
        }
        return v;
    }

    private static boolean jsonEquals(Object actual, Object expected) {
        try {
            shouldJSONEqual(actual, List.of(expected));
            return true;
        } catch (AssertException e) {
            return false;
        }
    }

    /** Deep equality of decoded JSON values, numbers compared as float64. */
    static boolean jsonDeepEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !jsonDeepEquals(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!jsonDeepEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    // ------------------------------------------------------------ helpers

    private static AssertException fail(String format, Object... args) {
        return new AssertException(GoFmt.sprintf(format, args));
    }

    private static void need(int needed, List<Object> expected) throws UsageException {
        if (expected.size() != needed) {
            throw new UsageException("This assertion requires exactly " + needed
                    + " comparison values (you provided " + expected.size() + ").");
        }
    }

    private static void atLeast(int minimum, List<Object> expected) throws UsageException {
        if (expected.size() < minimum) {
            throw new UsageException("This assertion requires at least 1 comparison value (you provided 0).");
        }
    }

    private static String joinedArgs(List<Object> expected) {
        StringBuilder sb = new StringBuilder();
        for (Object e : expected) {
            sb.append(GoFormat.sprint(e)).append(' ');
        }
        return GoStrings.trimSpace(sb.toString());
    }

    private static String toStr(Object v) throws AssertException {
        try {
            return Cast.toStringStrict(v);
        } catch (CastException e) {
            throw new AssertException(e.getMessage());
        }
    }

    private static double toFloat(Object v) throws AssertException {
        try {
            return Cast.toDoubleStrict(v);
        } catch (CastException e) {
            throw new AssertException(e.getMessage());
        }
    }

    private static long toLong(Object v) throws AssertException {
        try {
            return Cast.toLongStrict(v);
        } catch (CastException e) {
            throw new AssertException(e.getMessage());
        }
    }

    private static boolean toBool(Object v) throws AssertException {
        try {
            return Cast.toBoolStrict(v);
        } catch (CastException e) {
            throw new AssertException(e.getMessage());
        }
    }

    private static List<Object> toSlice(Object v) throws AssertException {
        if (v instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        if (v instanceof Object[] a) {
            return Arrays.asList(a);
        }
        throw new AssertException("unable to cast " + GoFormat.sprint(v) + " to []interface{}");
    }

    private static String expectKey(List<Object> expected) throws AssertException {
        if (!(expected.getFirst() instanceof String key)) {
            throw new AssertException(GoFmt.sprintf("expected '%v' to be a string", expected.getFirst()));
        }
        return key;
    }

    private static Map<String, Object> mapOrStruct(Object item, boolean withKind) throws AssertException {
        if (item instanceof Map || item instanceof Struct) {
            return Cast.toStringMap(item);
        }
        if (withKind) {
            throw new AssertException(GoFmt.sprintf("expected '%v' to be a map or a struct currently %v", item, kindOf(item)));
        }
        throw new AssertException(GoFmt.sprintf("expected '%v' to be a map or a struct", item));
    }

    private static String kindOf(Object v) {
        return switch (v) {
            case null -> "invalid";
            case String s -> "string";
            case Long l -> "int64";
            case Double d -> "float64";
            case Boolean b -> "bool";
            case Collection<?> c -> "slice";
            default -> v.getClass().getSimpleName();
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Object v) throws AssertException {
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (v instanceof Struct s) {
            return s.fields();
        }
        if (v instanceof String s && Json.tryParse(s) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new AssertException("unable to cast " + GoFormat.sprint(v) + " to map[string]interface{}");
    }

    private static ZonedDateTime time(Object in) throws AssertException {
        if (in instanceof ZonedDateTime t) {
            return t;
        }
        if (in instanceof OffsetDateTime t) {
            return t.toZonedDateTime();
        }
        String s;
        try {
            s = Cast.toStringStrict(in);
        } catch (CastException e) {
            throw new AssertException("invalid date provided: " + GoFormat.quote(GoFormat.sprint(in)));
        }
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toZonedDateTime();
        } catch (DateTimeParseException e) {
            try {
                return NaturalDate.parse(s, ZonedDateTime.now());
            } catch (NaturalDate.ParseException ex) {
                throw new AssertException("invalid date provided with " + GoFormat.quote(s)
                        + " not in RFC3339 format or humanize format");
            }
        }
    }

    /** Go's {@code time.Time.String()}. */
    static String goTime(ZonedDateTime t) {
        StringBuilder sb = new StringBuilder(t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        int nanos = t.getNano();
        if (nanos != 0) {
            String frac = String.format("%09d", nanos);
            int end = frac.length();
            while (end > 0 && frac.charAt(end - 1) == '0') {
                end--;
            }
            sb.append('.').append(frac, 0, end);
        }
        String offset = t.format(DateTimeFormatter.ofPattern("xx"));
        sb.append(' ').append(offset).append(' ');
        String zone = t.getZone().getId();
        if (zone.equals("Z") || zone.equals("UTC")) {
            sb.append("UTC");
        } else if (zone.startsWith("+") || zone.startsWith("-")) {
            sb.append(offset);
        } else {
            sb.append(t.format(DateTimeFormatter.ofPattern("zzz")));
        }
        return sb.toString();
    }

    /** The few {@code fmt} verbs used by assertion messages: %v, %s, %q, %d. */
    static final class GoFmt {
        private GoFmt() {
        }

        static String sprintf(String format, Object... args) {
            StringBuilder sb = new StringBuilder();
            int arg = 0;
            for (int i = 0; i < format.length(); i++) {
                char c = format.charAt(i);
                if (c != '%' || i + 1 >= format.length()) {
                    sb.append(c);
                    continue;
                }
                char verb = format.charAt(++i);
                if (verb == '%') {
                    sb.append('%');
                    continue;
                }
                Object v = arg < args.length ? args[arg++] : null;
                switch (verb) {
                    case 'q' -> sb.append(GoFormat.quote(v instanceof String s ? s : GoFormat.sprint(v)));
                    default -> sb.append(GoFormat.sprint(v));
                }
            }
            return sb.toString();
        }
    }
}
