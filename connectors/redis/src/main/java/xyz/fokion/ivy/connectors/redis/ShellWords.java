package xyz.fokion.ivy.connectors.redis;

import java.util.ArrayList;
import java.util.List;

import xyz.fokion.ivy.spi.ConnectorException;

/** Splits a command line into words: spaces separate, quotes group, backslash escapes. */
final class ShellWords {

    private ShellWords() {
    }

    static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && quote != '\'' && i + 1 < line.length()) {
                current.append(line.charAt(++i));
                inWord = true;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(current.toString());
                    current.setLength(0);
                    inWord = false;
                }
            } else {
                current.append(c);
                inWord = true;
            }
        }
        if (quote != 0) {
            throw new ConnectorException("unterminated quote in: " + line);
        }
        if (inWord) {
            words.add(current.toString());
        }
        if (words.isEmpty()) {
            throw new ConnectorException("empty command");
        }
        return words;
    }
}
