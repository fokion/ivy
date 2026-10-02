package xyz.fokion.ivy.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record Skipped(String value) {

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        return m;
    }
}
