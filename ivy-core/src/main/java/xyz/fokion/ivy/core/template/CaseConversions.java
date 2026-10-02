package xyz.fokion.ivy.core.template;

import xyz.fokion.ivy.core.util.GoStrings;

/**
 * Port of {@code huandu/xstrings} {@code ToSnakeCase} and {@code ToCamelCase} (v1.5.0).
 */
final class CaseConversions {

    private CaseConversions() {
    }

    private static boolean isConnector(int r) {
        return r == '-' || r == '_' || GoStrings.isSpace(r);
    }

    private static boolean isAlphabet(int r) {
        if (!Character.isLetter(r)) {
            return false;
        }
        // CJK ideographs are not alphabets
        return !(r >= 0x4E00 && r <= 0x9FCC) && !(r >= 0x3400 && r <= 0x4DB5)
                && !(r >= 0x20000 && r <= 0x2A6D6) && !(r >= 0x2A700 && r <= 0x2B734)
                && !(r >= 0x2B740 && r <= 0x2B81D);
    }

    private static boolean isPunct(int r) {
        return switch (Character.getType(r)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean isNumber(int r) {
        int t = Character.getType(r);
        return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    static String toCamelCase(String str) {
        if (str.isEmpty()) {
            return "";
        }
        int[] runes = str.codePoints().toArray();
        StringBuilder buf = new StringBuilder();
        boolean isFirstRuneUpper = false;
        int r0 = 0;
        int r1;
        int i = 0;
        boolean consumed = false;
        while (i < runes.length) {
            r0 = runes[i++];
            consumed = true;
            if (!isConnector(r0)) {
                isFirstRuneUpper = Character.isUpperCase(r0);
                r0 = Character.toLowerCase(r0);
                break;
            }
            buf.appendCodePoint(r0);
        }
        if (i >= runes.length) {
            // xstrings writes the last rune again, even a connector already written
            if (consumed) {
                buf.appendCodePoint(r0);
            }
            return buf.toString();
        }
        while (i < runes.length) {
            r1 = r0;
            r0 = runes[i++];
            if (isConnector(r0) && isConnector(r1)) {
                buf.appendCodePoint(r1);
                continue;
            }
            if (isConnector(r1)) {
                isFirstRuneUpper = Character.isUpperCase(r0);
                r0 = Character.toUpperCase(r0);
            } else {
                if (isFirstRuneUpper) {
                    if (Character.isUpperCase(r0)) {
                        r0 = Character.toLowerCase(r0);
                    } else {
                        isFirstRuneUpper = false;
                    }
                }
                buf.appendCodePoint(r1);
            }
        }
        if (isFirstRuneUpper) {
            r0 = Character.toLowerCase(r0);
        }
        buf.appendCodePoint(r0);
        return buf.toString();
    }

    private enum WordType {
        INVALID, NUMBER, UPPER_CASE, ALPHABET, CONNECTOR, PUNCT, OTHER
    }

    private record Word(WordType type, String word, String remaining) {
    }

    private static Word nextWord(String str) {
        if (str.isEmpty()) {
            return new Word(WordType.INVALID, "", "");
        }
        int offset = 0;
        String remaining = str;
        int r = remaining.codePointAt(0);
        int size = Character.charCount(r);
        offset += size;
        WordType wt;
        if (isConnector(r)) {
            wt = WordType.CONNECTOR;
            remaining = remaining.substring(size);
            while (!remaining.isEmpty()) {
                r = remaining.codePointAt(0);
                size = Character.charCount(r);
                if (!isConnector(r)) {
                    break;
                }
                offset += size;
                remaining = remaining.substring(size);
            }
        } else if (isPunct(r)) {
            wt = WordType.PUNCT;
            remaining = remaining.substring(size);
            while (!remaining.isEmpty()) {
                r = remaining.codePointAt(0);
                size = Character.charCount(r);
                if (!isPunct(r)) {
                    break;
                }
                offset += size;
                remaining = remaining.substring(size);
            }
        } else if (Character.isUpperCase(r)) {
            wt = WordType.UPPER_CASE;
            remaining = remaining.substring(size);
            if (!remaining.isEmpty()) {
                r = remaining.codePointAt(0);
                size = Character.charCount(r);
                if (Character.isUpperCase(r)) {
                    int prevSize = size;
                    offset += size;
                    remaining = remaining.substring(size);
                    while (!remaining.isEmpty()) {
                        r = remaining.codePointAt(0);
                        size = Character.charCount(r);
                        if (!Character.isUpperCase(r)) {
                            break;
                        }
                        prevSize = size;
                        offset += size;
                        remaining = remaining.substring(size);
                    }
                    // "HTTPStatus" splits into "HTTP" and "Status"
                    if (!remaining.isEmpty() && isAlphabet(r)) {
                        offset -= prevSize;
                        remaining = str.substring(offset);
                    }
                } else if (isAlphabet(r)) {
                    offset += size;
                    remaining = remaining.substring(size);
                    while (!remaining.isEmpty()) {
                        r = remaining.codePointAt(0);
                        size = Character.charCount(r);
                        if (!isAlphabet(r) || Character.isUpperCase(r)) {
                            break;
                        }
                        offset += size;
                        remaining = remaining.substring(size);
                    }
                }
            }
        } else if (isAlphabet(r)) {
            wt = WordType.ALPHABET;
            remaining = remaining.substring(size);
            while (!remaining.isEmpty()) {
                r = remaining.codePointAt(0);
                size = Character.charCount(r);
                if (!isAlphabet(r) || Character.isUpperCase(r)) {
                    break;
                }
                offset += size;
                remaining = remaining.substring(size);
            }
        } else if (isNumber(r)) {
            wt = WordType.NUMBER;
            remaining = remaining.substring(size);
            while (!remaining.isEmpty()) {
                r = remaining.codePointAt(0);
                size = Character.charCount(r);
                if (!isNumber(r)) {
                    break;
                }
                offset += size;
                remaining = remaining.substring(size);
            }
        } else {
            wt = WordType.OTHER;
            remaining = remaining.substring(size);
            while (!remaining.isEmpty()) {
                r = remaining.codePointAt(0);
                size = Character.charCount(r);
                if (isConnector(r) || isAlphabet(r) || isNumber(r) || isPunct(r)) {
                    break;
                }
                offset += size;
                remaining = remaining.substring(size);
            }
        }
        return new Word(wt, str.substring(0, offset), remaining);
    }

    private static void toLower(StringBuilder buf, WordType wt, String str, char connector) {
        if (wt != WordType.UPPER_CASE && wt != WordType.CONNECTOR) {
            buf.append(str);
            return;
        }
        str.codePoints().forEach(r -> {
            if (isConnector(r)) {
                buf.append(connector);
            } else if (Character.isUpperCase(r)) {
                buf.appendCodePoint(Character.toLowerCase(r));
            } else {
                buf.appendCodePoint(r);
            }
        });
    }

    static String toSnakeCase(String str) {
        return camelCaseToLowerCase(str, '_');
    }

    private static String camelCaseToLowerCase(String str, char connector) {
        if (str.isEmpty()) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        Word w = nextWord(str);
        WordType wt = w.type();
        String word = w.word();
        String remaining = w.remaining();

        while (!remaining.isEmpty()) {
            if (wt != WordType.CONNECTOR) {
                toLower(buf, wt, word, connector);
            }
            WordType prev = wt;
            String last = word;
            w = nextWord(remaining);
            wt = w.type();
            word = w.word();
            remaining = w.remaining();

            switch (prev) {
                case NUMBER -> {
                    while (wt == WordType.ALPHABET || wt == WordType.NUMBER) {
                        toLower(buf, wt, word, connector);
                        w = nextWord(remaining);
                        wt = w.type();
                        word = w.word();
                        remaining = w.remaining();
                    }
                    if (wt != WordType.INVALID && wt != WordType.PUNCT && wt != WordType.CONNECTOR) {
                        buf.append(connector);
                    }
                }
                case CONNECTOR -> toLower(buf, prev, last, connector);
                case PUNCT -> {
                }
                default -> {
                    if (wt != WordType.NUMBER) {
                        if (wt != WordType.CONNECTOR && wt != WordType.PUNCT) {
                            buf.append(connector);
                        }
                        break;
                    }
                    if (remaining.isEmpty()) {
                        break;
                    }
                    String lastNumber = word;
                    w = nextWord(remaining);
                    wt = w.type();
                    word = w.word();
                    remaining = w.remaining();
                    // a number is part of the previous word: "Bld4Floor" => "bld4_floor"
                    if (wt != WordType.ALPHABET) {
                        toLower(buf, WordType.NUMBER, lastNumber, connector);
                        if (wt != WordType.CONNECTOR && wt != WordType.PUNCT) {
                            buf.append(connector);
                        }
                        break;
                    }
                    // lower case letters following a number: "HTTP2xx" => "http_2xx"
                    buf.append(connector);
                    toLower(buf, WordType.NUMBER, lastNumber, connector);
                    while (wt == WordType.ALPHABET || wt == WordType.NUMBER) {
                        toLower(buf, wt, word, connector);
                        w = nextWord(remaining);
                        wt = w.type();
                        word = w.word();
                        remaining = w.remaining();
                    }
                    if (wt != WordType.INVALID && wt != WordType.CONNECTOR && wt != WordType.PUNCT) {
                        buf.append(connector);
                    }
                }
            }
        }
        toLower(buf, wt, word, connector);
        return buf.toString();
    }
}
