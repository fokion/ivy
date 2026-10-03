package xyz.fokion.ivy.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The outcome of applying the assertions of one step. */
public final class AssertionsApplied {
    public boolean ok;
    public final List<Failure> errors = new ArrayList<>();
    public String stdout = "";
    public String stderr = "";
    public final List<Applied> assertions = new ArrayList<>();

    public record Applied(Object assertion, boolean isOK) {
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        List<Object> list = new ArrayList<>();
        for (Applied a : assertions) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("assertion", a.assertion());
            e.put("isOK", a.isOK());
            list.add(e);
        }
        m.put("assertions", list);
        return m;
    }
}
