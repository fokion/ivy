package xyz.fokion.ivy.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Where a suite or test case comes from when it was written in Gherkin. */
public final class GherkinInfo {

    private GherkinInfo() {
    }

    /** The feature of a suite. */
    public record Feature(String uri, String keyword, String name, String description, int line, List<String> tags) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uri", uri);
            m.put("keyword", keyword);
            m.put("name", name);
            m.put("description", description);
            m.put("line", line);
            m.put("tags", tags);
            return m;
        }
    }

    /**
     * A Gherkin step and what it became: the index of the ivy step it created or added
     * assertions to (-1 when undefined), and the assertions it contributed.
     */
    public record Step(String keyword, String text, int line, int stepIndex, boolean createsStep,
            List<Object> assertions) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyword", keyword);
            m.put("text", text);
            m.put("line", line);
            m.put("stepIndex", stepIndex);
            m.put("createsStep", createsStep);
            return m;
        }
    }

    /** The scenario of a test case, with the reasons it cannot run (undefined steps). */
    public record Scenario(String keyword, String name, String description, int line, List<String> tags,
            List<Step> steps, List<String> problems) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyword", keyword);
            m.put("name", name);
            m.put("description", description);
            m.put("line", line);
            m.put("tags", tags);
            List<Object> s = new ArrayList<>();
            steps.forEach(st -> s.add(st.toJson()));
            m.put("steps", s);
            m.put("problems", problems);
            return m;
        }
    }
}
