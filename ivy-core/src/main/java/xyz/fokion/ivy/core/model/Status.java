package xyz.fokion.ivy.core.model;

import java.util.Collection;

public enum Status {
    RUN, FAIL, SKIP, PASS;

    /** The empty status of a test case that has not run, as Go's zero value. */
    public static String name(Status s) {
        return s == null ? "" : s.name();
    }

    /**
     * The status of a run, a suite or a test case from those of its children: FAIL when one
     * failed, SKIP when all were skipped, PASS otherwise; {@code ifEmpty} when there are none.
     */
    public static Status aggregate(Collection<Status> children, Status ifEmpty) {
        if (children.isEmpty()) {
            return ifEmpty;
        }
        if (children.contains(FAIL)) {
            return FAIL;
        }
        return children.stream().allMatch(s -> s == SKIP) ? SKIP : PASS;
    }
}
