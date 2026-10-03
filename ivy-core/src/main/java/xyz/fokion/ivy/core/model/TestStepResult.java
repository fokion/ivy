package xyz.fokion.ivy.core.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.util.GoStrings;

/** The result of one step (or of one iteration of a ranged step). */
public final class TestStepResult {
    public String name = "";
    public List<Failure> errors;
    public List<Skipped> skipped;
    public Status status;
    /** The step as written, as YAML. */
    public String raw = "";
    /** The step once its templates are rendered, as YAML. */
    public String interpolated = "";
    public int number;
    public int rangedIndex;
    public boolean rangedEnable;
    /** The variables of the test case when the step ran. */
    public Map<String, Object> inputVars;
    /** {@code result} and the values the step set. */
    public Map<String, Object> computedVars = new LinkedHashMap<>();
    public List<String> computedInfo;
    public AssertionsApplied assertionsApplied = new AssertionsApplied();
    public int retries;
    public String stdout = "";
    public String stderr = "";
    public double duration;
    public OffsetDateTime start;
    public OffsetDateTime end;

    public void appendError(String message) {
        appendFailure(new Failure(GoStrings.removeNotPrintable(message)));
        status = Status.FAIL;
    }

    public void appendFailure(Failure f) {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(f);
    }

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    public List<Failure> errorList() {
        return errors == null ? List.of() : errors;
    }

    public void addSkipped(String value) {
        if (skipped == null) {
            skipped = new ArrayList<>();
        }
        skipped.add(new Skipped(value));
    }

    public void addInfo(String info) {
        if (computedInfo == null) {
            computedInfo = new ArrayList<>();
        }
        computedInfo.add(info);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("errors", Json.list(errors, Failure::toJson));
        m.put("skipped", Json.list(skipped, Skipped::toJson));
        m.put("status", Status.name(status));
        m.put("raw", raw);
        m.put("interpolated", interpolated);
        m.put("number", number);
        m.put("rangedIndex", rangedIndex);
        m.put("rangedEnable", rangedEnable);
        m.put("inputVars", inputVars);
        m.put("computedVars", computedVars);
        m.put("computedInfos", computedInfo);
        m.put("assertionsApplied", assertionsApplied.toJson());
        m.put("retries", retries);
        m.put("stdout", stdout);
        m.put("stderr", stderr);
        m.put("duration", duration);
        m.put("start", Json.time(start));
        m.put("end", Json.time(end));
        return m;
    }
}
