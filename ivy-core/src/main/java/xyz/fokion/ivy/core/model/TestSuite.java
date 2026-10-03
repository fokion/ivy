package xyz.fokion.ivy.core.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A test suite file and its results. */
public final class TestSuite {
    public String name = "";
    public String description = "";
    public List<TestCase> testCases = new ArrayList<>();
    public Map<String, Object> vars = new LinkedHashMap<>();
    public List<String> secrets = new ArrayList<>();
    /** False for a suite that must not run at the same time as another one, such as one resetting a database. */
    public boolean parallel = true;

    // computed
    public String shortName = "";
    public String filename = "";
    public String filepath = "";
    public String workDir = "";
    public Status status;
    public double duration;
    public OffsetDateTime start;
    public OffsetDateTime end;
    public int nbTestcasesFail;
    public int nbTestcasesPass;
    public int nbTestcasesSkip;
    /** The Gherkin feature this suite was built from, or null. */
    public GherkinInfo.Feature feature;

    /** A shallow copy, used to report a suite without its unevaluated test cases. */
    public TestSuite copy() {
        TestSuite c = new TestSuite();
        c.name = name;
        c.description = description;
        c.testCases = new ArrayList<>(testCases);
        c.vars = new LinkedHashMap<>(vars);
        c.secrets = secrets;
        c.parallel = parallel;
        c.shortName = shortName;
        c.filename = filename;
        c.filepath = filepath;
        c.workDir = workDir;
        c.status = status;
        c.duration = duration;
        c.start = start;
        c.end = end;
        c.nbTestcasesFail = nbTestcasesFail;
        c.nbTestcasesPass = nbTestcasesPass;
        c.nbTestcasesSkip = nbTestcasesSkip;
        c.feature = feature;
        return c;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        if (!description.isEmpty()) {
            m.put("description", description);
        }
        List<Object> tcs = new ArrayList<>();
        testCases.forEach(tc -> tcs.add(tc.toJson()));
        m.put("testcases", tcs);
        m.put("vars", vars);
        m.put("secrets", secrets);
        m.put("shortname", shortName);
        m.put("filename", filename);
        m.put("filepath", filepath);
        m.put("workdir", workDir);
        m.put("status", Status.name(status));
        m.put("duration", duration);
        m.put("start", Json.time(start));
        m.put("end", Json.time(end));
        m.put("nbTestcasesFail", nbTestcasesFail);
        m.put("nbTestcasesPass", nbTestcasesPass);
        m.put("nbTestcasesSkip", nbTestcasesSkip);
        if (feature != null) {
            m.put("gherkin", feature.toJson());
        }
        return m;
    }
}
