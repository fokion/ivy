package xyz.fokion.ivy.core.gherkin;

import java.util.List;

/** The parsed form of a {@code .feature} file. */
public final class Gherkin {

    private Gherkin() {
    }

    public record Tag(String name, int line) {
    }

    /** A step; {@code docString} and {@code table} are null when absent. */
    public record Step(String keyword, String text, int line, String docString, List<List<String>> table) {
    }

    public record Examples(String keyword, String name, List<Tag> tags, int line, List<String> header,
            List<List<String>> rows, List<Integer> rowLines) {
    }

    public record Background(String keyword, String name, int line, List<Step> steps) {
    }

    /** A scenario or an outline: an outline has examples. */
    public record Scenario(String keyword, String name, String description, List<Tag> tags, int line,
            List<Step> steps, List<Examples> examples) {

        public boolean isOutline() {
            return !examples.isEmpty();
        }
    }

    public record Rule(String keyword, String name, List<Tag> tags, int line, Background background,
            List<Scenario> scenarios) {
    }

    /** A feature: its own scenarios, plus those grouped in rules. */
    public record Feature(String keyword, String name, String description, List<Tag> tags, int line,
            Background background, List<Scenario> scenarios, List<Rule> rules) {
    }
}
