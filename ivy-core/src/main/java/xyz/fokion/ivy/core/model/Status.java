package xyz.fokion.ivy.core.model;

public enum Status {
    RUN, FAIL, SKIP, PASS;

    /** The empty status of a test case that has not run, as Go's zero value. */
    public static String name(Status s) {
        return s == null ? "" : s.name();
    }
}
