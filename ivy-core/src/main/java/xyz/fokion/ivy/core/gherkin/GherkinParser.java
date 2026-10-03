package xyz.fokion.ivy.core.gherkin;

import java.util.ArrayList;
import java.util.List;

import xyz.fokion.ivy.core.gherkin.Gherkin.Background;
import xyz.fokion.ivy.core.gherkin.Gherkin.Examples;
import xyz.fokion.ivy.core.gherkin.Gherkin.Feature;
import xyz.fokion.ivy.core.gherkin.Gherkin.Rule;
import xyz.fokion.ivy.core.gherkin.Gherkin.Scenario;
import xyz.fokion.ivy.core.gherkin.Gherkin.Step;
import xyz.fokion.ivy.core.gherkin.Gherkin.Tag;

/**
 * A parser for English Gherkin: {@code Feature}, {@code Background}, {@code Rule},
 * {@code Scenario}/{@code Example}, {@code Scenario Outline}/{@code Scenario Template} with
 * {@code Examples}/{@code Scenarios}, steps ({@code Given}, {@code When}, {@code Then}, {@code And},
 * {@code But}, {@code *}), doc strings, data tables, tags, comments and descriptions.
 */
public final class GherkinParser {

    public static final class GherkinException extends Exception {
        public GherkinException(String message) {
            super(message);
        }
    }

    private static final List<String> STEP_KEYWORDS = List.of("Given ", "When ", "Then ", "And ", "But ", "* ");

    private final String source;
    private final String[] lines;
    private int i;

    private GherkinParser(String source, String text) {
        this.source = source;
        this.lines = text.replace("\r\n", "\n").split("\n", -1);
    }

    public static Feature parse(String source, String text) throws GherkinException {
        return new GherkinParser(source, text).feature();
    }

    private GherkinException error(String message) {
        return new GherkinException(source + ":" + (i + 1) + ": " + message);
    }

    private String line() {
        return lines[i].strip();
    }

    private boolean atEnd() {
        return i >= lines.length;
    }

    private boolean blankOrComment() {
        String l = line();
        return l.isEmpty() || l.startsWith("#");
    }

    private void skipBlank() {
        while (!atEnd() && blankOrComment()) {
            i++;
        }
    }

    /** The keyword a line starts with (without the colon), or null. */
    private static String header(String line, String... keywords) {
        for (String k : keywords) {
            if (line.startsWith(k + ":")) {
                return k;
            }
        }
        return null;
    }

    private static String afterColon(String line) {
        return line.substring(line.indexOf(':') + 1).strip();
    }

    private List<Tag> tags() {
        List<Tag> tags = new ArrayList<>();
        while (!atEnd()) {
            String l = line();
            if (l.startsWith("@")) {
                String withoutComment = l.contains(" #") ? l.substring(0, l.indexOf(" #")) : l;
                for (String t : withoutComment.split("\\s+")) {
                    if (t.startsWith("@")) {
                        tags.add(new Tag(t, i + 1));
                    }
                }
                i++;
            } else if (blankOrComment()) {
                i++;
            } else {
                break;
            }
        }
        return tags;
    }

    /** Free text lines until the next keyword line. */
    private String description() {
        StringBuilder sb = new StringBuilder();
        while (!atEnd()) {
            String l = line();
            if (l.startsWith("@") || l.startsWith("|") || isBlockHeader(l) || isStep(l)) {
                break;
            }
            if (!l.startsWith("#")) {
                if (!sb.isEmpty() || !l.isEmpty()) {
                    sb.append(lines[i].strip()).append('\n');
                }
            }
            i++;
        }
        return sb.toString().strip();
    }

    private static boolean isBlockHeader(String l) {
        return header(l, "Feature", "Background", "Rule", "Scenario Outline", "Scenario Template", "Scenario",
                "Example", "Examples", "Scenarios") != null;
    }

    private static boolean isStep(String l) {
        for (String k : STEP_KEYWORDS) {
            if (l.startsWith(k) || l.equals(k.strip())) {
                return true;
            }
        }
        return false;
    }

    private Feature feature() throws GherkinException {
        skipBlank();
        // a language header ("# language: en") is a comment and skipped above
        List<Tag> tags = tags();
        if (atEnd()) {
            throw error("no Feature found");
        }
        String l = line();
        if (header(l, "Feature") == null) {
            throw error("expected Feature, got \"" + l + "\"");
        }
        int featureLine = i + 1;
        String name = afterColon(l);
        i++;
        String description = description();
        Background background = null;
        List<Scenario> scenarios = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        while (true) {
            skipBlank();
            if (atEnd()) {
                break;
            }
            List<Tag> blockTags = tags();
            if (atEnd()) {
                break;
            }
            String h = line();
            if (header(h, "Background") != null) {
                if (!rules.isEmpty() || !scenarios.isEmpty()) {
                    throw error("Background must come before scenarios");
                }
                background = background();
            } else if (header(h, "Rule") != null) {
                rules.add(rule(blockTags));
            } else if (header(h, "Scenario Outline", "Scenario Template", "Scenario", "Example") != null) {
                if (!rules.isEmpty()) {
                    throw error("scenarios after a Rule belong to it; indent them under the Rule");
                }
                scenarios.add(scenario(blockTags));
            } else {
                throw error("unexpected \"" + h + "\"");
            }
        }
        return new Feature("Feature", name, description, tags, featureLine, background, scenarios, rules);
    }

    private Rule rule(List<Tag> tags) throws GherkinException {
        int ruleLine = i + 1;
        String name = afterColon(line());
        i++;
        description();
        Background background = null;
        List<Scenario> scenarios = new ArrayList<>();
        while (true) {
            skipBlank();
            if (atEnd()) {
                break;
            }
            int save = i;
            List<Tag> blockTags = tags();
            if (atEnd()) {
                break;
            }
            String h = line();
            if (header(h, "Rule") != null) {
                i = save;
                break;
            }
            if (header(h, "Background") != null) {
                background = background();
            } else if (header(h, "Scenario Outline", "Scenario Template", "Scenario", "Example") != null) {
                scenarios.add(scenario(blockTags));
            } else {
                throw error("unexpected \"" + h + "\"");
            }
        }
        return new Rule("Rule", name, tags, ruleLine, background, scenarios);
    }

    private Background background() throws GherkinException {
        int bgLine = i + 1;
        String keyword = header(line(), "Background");
        String name = afterColon(line());
        i++;
        description();
        return new Background(keyword, name, bgLine, steps());
    }

    private Scenario scenario(List<Tag> tags) throws GherkinException {
        int scenarioLine = i + 1;
        String keyword = header(line(), "Scenario Outline", "Scenario Template", "Scenario", "Example");
        String name = afterColon(line());
        i++;
        String description = description();
        List<Step> steps = steps();
        List<Examples> examples = new ArrayList<>();
        while (true) {
            skipBlank();
            if (atEnd()) {
                break;
            }
            int save = i;
            List<Tag> exampleTags = tags();
            if (atEnd() || header(line(), "Examples", "Scenarios") == null) {
                i = save;
                break;
            }
            examples.add(examples(exampleTags));
        }
        boolean outline = keyword.equals("Scenario Outline") || keyword.equals("Scenario Template");
        if (outline && examples.isEmpty()) {
            throw new GherkinException(source + ":" + scenarioLine + ": " + keyword + " without Examples");
        }
        return new Scenario(keyword, name, description, tags, scenarioLine, steps, examples);
    }

    private Examples examples(List<Tag> tags) throws GherkinException {
        int exLine = i + 1;
        String keyword = header(line(), "Examples", "Scenarios");
        String name = afterColon(line());
        i++;
        description();
        skipBlank();
        List<Integer> rowLines = new ArrayList<>();
        int tableStart = i;
        List<List<String>> table = table();
        if (table == null || table.isEmpty()) {
            throw error("Examples need a table");
        }
        for (int r = 0; r < table.size(); r++) {
            rowLines.add(tableStart + 1 + r);
        }
        return new Examples(keyword, name, tags, exLine, table.getFirst(), table.subList(1, table.size()),
                rowLines.subList(1, rowLines.size()));
    }

    private List<Step> steps() throws GherkinException {
        List<Step> steps = new ArrayList<>();
        while (true) {
            skipBlank();
            if (atEnd()) {
                return steps;
            }
            String l = line();
            if (!isStep(l)) {
                return steps;
            }
            String keyword = null;
            for (String k : STEP_KEYWORDS) {
                if (l.startsWith(k) || l.equals(k.strip())) {
                    keyword = k;
                    break;
                }
            }
            int stepLine = i + 1;
            String text = l.length() > keyword.length() ? l.substring(keyword.length()).strip() : "";
            i++;
            String docString = null;
            List<List<String>> table = null;
            skipComments();
            if (!atEnd()) {
                String next = line();
                if (next.startsWith("\"\"\"") || next.startsWith("```")) {
                    docString = docString();
                } else if (next.startsWith("|")) {
                    table = table();
                }
            }
            steps.add(new Step(keyword, text, stepLine, docString, table));
        }
    }

    private void skipComments() {
        while (!atEnd() && line().startsWith("#")) {
            i++;
        }
    }

    private String docString() throws GherkinException {
        String open = lines[i];
        String delimiter = open.strip().startsWith("```") ? "```" : "\"\"\"";
        int indent = open.indexOf(delimiter);
        i++;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (true) {
            if (atEnd()) {
                throw error("unterminated doc string");
            }
            String raw = lines[i];
            if (raw.strip().equals(delimiter)) {
                i++;
                return sb.toString();
            }
            // remove the indentation of the opening delimiter
            int strip = 0;
            while (strip < indent && strip < raw.length() && raw.charAt(strip) == ' ') {
                strip++;
            }
            if (!first) {
                sb.append('\n');
            }
            sb.append(raw.substring(strip).replace("\\\"\\\"\\\"", "\"\"\""));
            first = false;
            i++;
        }
    }

    private List<List<String>> table() {
        List<List<String>> rows = new ArrayList<>();
        while (!atEnd()) {
            String l = line();
            if (l.startsWith("#")) {
                i++;
                continue;
            }
            if (!l.startsWith("|")) {
                break;
            }
            rows.add(cells(l));
            i++;
        }
        return rows;
    }

    /** Splits {@code | a | b \| c |}, unescaping {@code \|}, {@code \\} and {@code \n}. */
    static List<String> cells(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = null;
        for (int k = 0; k < row.length(); k++) {
            char c = row.charAt(k);
            if (c == '\\' && k + 1 < row.length()) {
                char n = row.charAt(++k);
                if (cell != null) {
                    cell.append(n == 'n' ? '\n' : n);
                }
            } else if (c == '|') {
                if (cell != null) {
                    cells.add(cell.toString().strip());
                }
                cell = new StringBuilder();
            } else if (cell != null) {
                cell.append(c);
            }
        }
        return cells;
    }
}
