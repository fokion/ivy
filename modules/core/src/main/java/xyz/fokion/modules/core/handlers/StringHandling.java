package xyz.fokion.modules.core.handlers;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class StringHandling {
    private static final Random random = new Random();
    private StringHandling() {
    }

    public static String replacePlaceholders(String input, Map<String, String> context, int maxRecursionDepth) {
        if (input == null || input.isEmpty() || context == null || maxRecursionDepth <= 0) {
            return input;
        }

        String result = input;
        boolean replacementMade;

        do {
            replacementMade = false;
            // Match pattern {{someKey}}
            Pattern pattern = Pattern.compile("\\{\\{([^}]+)}}");
            Matcher matcher = pattern.matcher(result);

            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1);
                String replacement = context.getOrDefault(key, matcher.group(0));
                // Only mark as replaced if we actually replaced something
                if (!replacement.equals(matcher.group(0))) {
                    replacementMade = true;
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            result = sb.toString();
            maxRecursionDepth--;
        } while (replacementMade && maxRecursionDepth > 0);

        return result;
    }

    @SafeVarargs
    public static <T> T withDefault(T... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (int i = values.length - 1; i >= 0; i--) {
            if (values[i] != null && !values[i].toString().isEmpty()) {
                return values[i];
            }
        }
        return null;
    }

    /**
     * Checks if a given value is empty
     */
    public static boolean empty(Object given) {
        return switch (given) {
            case null -> true;
            case String s -> s.isEmpty();
            case Collection<?> objects -> objects.isEmpty();
            case Object[] objects -> objects.length == 0;
            case Boolean b -> !b;
            case Number number -> number.doubleValue() == 0;
            default -> false;
        };

    }

    /**
     * Returns the first non-empty value
     */
    @SafeVarargs
    public static <T> T coalesce(T... values) {
        if (values == null) {
            return null;
        }
        for (T val : values) {
            if (!empty(val)) {
                return val;
            }
        }
        return null;
    }

    /**
     * Encodes string to Base64
     */
    public static String base64encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes());
    }

    /**
     * Decodes Base64 string
     */
    public static String base64decode(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return new String(decoded);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * Abbreviates a string to a specified width
     */
    public static String abbrev(int width, String str) {
        if (width < 4 || str == null) {
            return str;
        }
        if (str.length() <= width) {
            return str;
        }
        return str.substring(0, width - 3) + "...";
    }

    /**
     * Abbreviates a string with a left and right margin
     */
    public static String abbrevboth(int left, int right, String str) {
        if (right < 4 || (left > 0 && right < 7) || str == null) {
            return str;
        }
        if (str.length() <= (left + right)) {
            return str;
        }
        return str.substring(0, left) + "..." +
                str.substring(str.length() - right);
    }
    /**
     * Gets the initials from a string
     */
    public static String initials(String str) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        return Arrays.stream(str.split("\\s+"))
                .filter(s -> !s.isEmpty())
                .map(s -> String.valueOf(s.charAt(0)))
                .collect(Collectors.joining());
    }

    /**
     * Generates random alphanumeric string
     */
    public static String randAlphaNumeric(int count) {
        return random.ints(count, 0, 36)
                .mapToObj(i -> Integer.toString(i, 36))
                .collect(Collectors.joining());
    }

    /**
     * Generates random alphabetic string
     */
    public static String randAlpha(int count) {
        return random.ints(count, 'a', 'z' + 1)
                .mapToObj(i -> String.valueOf((char) i))
                .collect(Collectors.joining());
    }

    /**
     * Generates random ASCII string
     */
    public static String randASCII(int count) {
        return random.ints(count, 32, 127)
                .mapToObj(i -> String.valueOf((char) i))
                .collect(Collectors.joining());
    }

    /**
     * Generates random numeric string
     */
    public static String randNumeric(int count) {
        return random.ints(count, 0, 10)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining());
    }

    /**
     * Converts first character to lowercase
     */
    public static String untitle(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) +
                (str.length() > 1 ? str.substring(1) : "");
    }
    /**
     * Wraps strings in double quotes
     */
    public static String quote(Object... values) {
        return Arrays.stream(values)
                .map(s -> "\"" + String.valueOf(s) + "\"")
                .collect(Collectors.joining(" "));
    }

    /**
     * Wraps strings in single quotes
     */
    public static String squote(Object... values) {
        return Arrays.stream(values)
                .map(s -> "'" + s + "'")
                .collect(Collectors.joining(" "));
    }


    /**
     * Function to indent a string with a specified number of spaces
     */
    public static final BiFunction<Integer, String, String> indent = (spaces, text) -> {
        String padding = IntStream.range(0, spaces)
                .mapToObj(i -> " ")
                .collect(Collectors.joining());
        return padding + text.replace("\n", "\n" + padding);
    };

    /**
     * Function to indent a string with a specified number of spaces and add a leading newline
     */
    public static final BiFunction<Integer, String, String> nindent = (spaces, text) ->
            "\n" + indent.apply(spaces, text);

}
