package xyz.fokion.ivy.connectors.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits a SQL script into statements on semicolons, outside of quotes, comments and PostgreSQL
 * dollar-quoted bodies, so that every driver can run it one statement at a time.
 */
final class SqlScript {

    private SqlScript() {
    }

    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '-' && script.startsWith("--", i)) {
                int end = script.indexOf('\n', i);
                end = end < 0 ? script.length() : end;
                current.append(script, i, end);
                i = end;
            } else if (c == '/' && script.startsWith("/*", i)) {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? script.length() : end + 2;
                current.append(script, i, end);
                i = end;
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = i + 1;
                while (end < script.length()) {
                    if (script.charAt(end) == c) {
                        if (end + 1 < script.length() && script.charAt(end + 1) == c) {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    if (script.charAt(end) == '\\' && c != '"') {
                        end++;
                    }
                    end++;
                }
                end = Math.min(end + 1, script.length());
                current.append(script, i, end);
                i = end;
            } else if (c == '$' && dollarTag(script, i) != null) {
                String tag = dollarTag(script, i);
                int end = script.indexOf(Objects.requireNonNull(tag), i + tag.length());
                end = end < 0 ? script.length() : end + tag.length();
                current.append(script, i, end);
                i = end;
            } else if (c == ';') {
                add(out, current);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        add(out, current);
        return out;
    }

    /** {@code $$} or {@code $tag$} at a position, or null. */
    private static String dollarTag(String s, int i) {
        int j = i + 1;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        return j < s.length() && s.charAt(j) == '$' ? s.substring(i, j + 1) : null;
    }

    private static void add(List<String> out, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty() && !onlyComments(statement)) {
            out.add(statement);
        }
        current.setLength(0);
    }

    private static boolean onlyComments(String statement) {
        for (String line : statement.split("\n")) {
            String l = line.strip();
            if (!l.isEmpty() && !l.startsWith("--")) {
                return false;
            }
        }
        return true;
    }

    /** The "Up" part of a migration written with {@code -- +migrate Up / Down} markers, else all of it. */
    static String up(String migration) {
        int up = migration.indexOf("-- +migrate Up");
        if (up < 0) {
            return migration;
        }
        int down = migration.indexOf("-- +migrate Down", up);
        return migration.substring(up + "-- +migrate Up".length(), down < 0 ? migration.length() : down);
    }
}
