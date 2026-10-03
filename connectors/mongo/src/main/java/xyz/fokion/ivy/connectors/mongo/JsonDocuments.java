package xyz.fokion.ivy.connectors.mongo;

import java.util.ArrayList;
import java.util.List;

import xyz.fokion.ivy.spi.ConnectorException;

/** Splits a file of concatenated JSON objects (one per line, or spread over lines). */
final class JsonDocuments {

    private JsonDocuments() {
    }

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) {
                    start = i;
                }
            } else if (c == '}') {
                if (--depth == 0) {
                    out.add(text.substring(start, i + 1));
                } else if (depth < 0) {
                    throw new ConnectorException("unbalanced braces at offset " + i);
                }
            } else if (depth == 0 && !Character.isWhitespace(c)) {
                throw new ConnectorException("unexpected '" + c + "' at offset " + i + ", expected a JSON object");
            }
        }
        if (depth != 0) {
            throw new ConnectorException("unterminated JSON object");
        }
        return out;
    }
}
