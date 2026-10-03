package xyz.fokion.ivy.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A step failure. Only {@link #value} is reported; the other fields help callers such as
 * user assertions.
 */
public final class Failure {
    public String testcaseClassname = "";
    public String testcaseName = "";
    public int testcaseLineNumber;
    public int stepNumber;
    public String assertion = "";
    /** The assertion as declared in the step (a string or a map), or null. */
    public Object declared;
    public boolean assertionRequired;
    public String error = "";
    public String value = "";


    public Failure(String value) {
        this.value = value;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        return m;
    }

    @Override
    public String toString() {
        return value.isEmpty() ? error : value;
    }
}
