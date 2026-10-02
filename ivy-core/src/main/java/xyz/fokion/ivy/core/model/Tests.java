package xyz.fokion.ivy.core.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All suites of a run. */
public final class Tests {
    public List<TestSuite> testSuites = new ArrayList<>();
    public Status status;
    public int nbTestsuitesFail;
    public int nbTestsuitesPass;
    public int nbTestsuitesSkip;
    public double duration;
    public OffsetDateTime start;
    public OffsetDateTime end;

    /** The same totals with other suites. */
    public Tests withSuites(List<TestSuite> suites) {
        Tests t = new Tests();
        t.testSuites = suites;
        t.status = status;
        t.nbTestsuitesFail = nbTestsuitesFail;
        t.nbTestsuitesPass = nbTestsuitesPass;
        t.nbTestsuitesSkip = nbTestsuitesSkip;
        t.duration = duration;
        t.start = start;
        t.end = end;
        return t;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> suites = new ArrayList<>();
        testSuites.forEach(ts -> suites.add(ts.toJson()));
        m.put("test_suites", suites);
        m.put("status", Status.name(status));
        m.put("nbTestsuitesFail", nbTestsuitesFail);
        m.put("nbTestsuitesPass", nbTestsuitesPass);
        m.put("nbTestsuitesSkip", nbTestsuitesSkip);
        m.put("duration", duration);
        m.put("start", Json.time(start));
        m.put("end", Json.time(end));
        return m;
    }
}
