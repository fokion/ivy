package xyz.fokion.ivy.core.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Ports of the Go standard library string functions whose exact behaviour suites depend on.
 */
public final class GoStrings {

    private GoStrings() {
    }

    /** {@code strconv.Unquote} for double-quoted and single-quoted literals. */
    public static String unquote(String s) {
        if (s.length() < 2) {
            throw new IllegalArgumentException("invalid syntax");
        }
        char q = s.charAt(0);
        if (q != s.charAt(s.length() - 1) || (q != '"' && q != '\'' && q != '`')) {
            throw new IllegalArgumentException("invalid syntax");
        }
        String body = s.substring(1, s.length() - 1);
        if (q == '`') {
            return body;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                if (c == q || c == '\n') {
                    throw new IllegalArgumentException("invalid syntax");
                }
                sb.append(c);
                continue;
            }
            if (++i >= body.length()) {
                throw new IllegalArgumentException("invalid syntax");
            }
            char e = body.charAt(i);
            switch (e) {
                case 'a' -> sb.append('\u0007');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'v' -> sb.append('\u000b');
                case '\\' -> sb.append('\\');
                case '\'', '"' -> {
                    if (e != q) {
                        throw new IllegalArgumentException("invalid syntax");
                    }
                    sb.append(e);
                }
                case 'x' -> {
                    sb.append((char) hex(body, i + 1, 2));
                    i += 2;
                }
                case 'u' -> {
                    sb.appendCodePoint(hex(body, i + 1, 4));
                    i += 4;
                }
                case 'U' -> {
                    sb.appendCodePoint(hex(body, i + 1, 8));
                    i += 8;
                }
                default -> {
                    if (e >= '0' && e <= '7' && i + 2 < body.length()) {
                        sb.append((char) Integer.parseInt(body.substring(i, i + 3), 8));
                        i += 2;
                    } else {
                        throw new IllegalArgumentException("invalid syntax");
                    }
                }
            }
        }
        if (q == '\'' && sb.codePointCount(0, sb.length()) != 1) {
            throw new IllegalArgumentException("invalid syntax");
        }
        return sb.toString();
    }

    private static int hex(String s, int from, int len) {
        if (from + len > s.length()) {
            throw new IllegalArgumentException("invalid syntax");
        }
        try {
            return Integer.parseInt(s.substring(from, from + len), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid syntax");
        }
    }

    /** {@code strconv.Quote} without the surrounding quotes, to embed a value in a JSON string. */
    public static String quoteInner(String s) {
        String q = xyz.fokion.ivy.spi.util.GoFormat.quote(s);
        return q.substring(1, q.length() - 1);
    }

    /** Go's {@code unicode.IsSpace}. */
    public static boolean isSpace(int c) {
        return switch (c) {
            case '\t', '\n', 0x0b, '\f', '\r', ' ', 0x85, 0xA0 -> true;
            default -> c > 0xff && Character.isWhitespace(c) || Character.getType(c) == Character.SPACE_SEPARATOR;
        };
    }

    /** {@code strings.TrimSpace}. */
    public static String trimSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && isSpace(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    /** {@code strings.Fields}. */
    public static List<String> fields(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && isSpace(s.codePointAt(i))) {
                i += Character.charCount(s.codePointAt(i));
            }
            int start = i;
            while (i < s.length() && !isSpace(s.codePointAt(i))) {
                i += Character.charCount(s.codePointAt(i));
            }
            if (i > start) {
                out.add(s.substring(start, i));
            }
        }
        return out;
    }

    /** {@code strings.ToUpper}, rune by rune. */
    public static String toUpper(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(c -> sb.appendCodePoint(Character.toUpperCase(c)));
        return sb.toString();
    }

    /** {@code strings.ToLower}, rune by rune. */
    public static String toLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(c -> sb.appendCodePoint(Character.toLowerCase(c)));
        return sb.toString();
    }

    /** {@code strings.Title}. */
    public static String title(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int prev = ' ';
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            sb.appendCodePoint(isSeparator(prev) ? Character.toTitleCase(c) : c);
            prev = c;
            i += Character.charCount(c);
        }
        return sb.toString();
    }

    private static boolean isSeparator(int r) {
        if (r <= 0x7F) {
            return !((r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r == '_');
        }
        if (Character.isLetter(r) || Character.isDigit(r)) {
            return false;
        }
        return isSpace(r);
    }

    /** {@code strings.Trim(s, cutset)}. */
    public static String trim(String s, String cutset) {
        int start = 0;
        int end = s.length();
        while (start < end && cutset.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && cutset.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    /** {@code path.Clean}. */
    public static String cleanPath(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        boolean rooted = path.charAt(0) == '/';
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!out.isEmpty() && !out.getLast().equals("..")) {
                    out.removeLast();
                } else if (!rooted) {
                    out.add("..");
                }
                continue;
            }
            out.add(part);
        }
        String joined = String.join("/", out);
        if (rooted) {
            return "/" + joined;
        }
        return joined.isEmpty() ? "." : joined;
    }

    /** {@code path.Dir}. */
    public static String dir(String path) {
        int i = path.lastIndexOf('/');
        return cleanPath(path.substring(0, i + 1));
    }

    /** {@code path.Base}. */
    public static String base(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        while (path.length() > 0 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int i = path.lastIndexOf('/');
        if (i >= 0) {
            path = path.substring(i + 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /** {@code url.QueryEscape}. */
    public static String queryEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /**
     * Replaces runes that are neither printable, spaces
     * nor punctuation with a space.
     */
    public static String removeNotPrintable(String in) {
        StringBuilder sb = new StringBuilder(in.length());
        in.codePoints().forEach(c -> {
            boolean punct = switch (Character.getType(c)) {
                case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                     Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                     Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true;
                default -> false;
            };
            sb.appendCodePoint(xyz.fokion.ivy.spi.util.GoFormat.isPrint(c) || isSpace(c) || punct ? c : ' ');
        });
        return sb.toString();
    }

    /**
     * Compiles a Go (RE2) regular expression: named groups written {@code (?P<name>...)} are
     * accepted. Java-only constructs are not rejected.
     */
    public static java.util.regex.Pattern compileRegex(String re2) {
        return java.util.regex.Pattern.compile(re2.replace("(?P<", "(?<"));
    }
}
