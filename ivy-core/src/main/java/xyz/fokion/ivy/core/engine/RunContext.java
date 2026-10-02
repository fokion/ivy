package xyz.fokion.ivy.core.engine;

import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.log.IvyLog.Fields;

/**
 * What venom carried in its {@code context.Context}: log fields, secrets and the variables of
 * the current step.
 */
record RunContext(Fields fields, Map<String, String> vars) {

    static RunContext root() {
        return new RunContext(Fields.EMPTY, Map.of());
    }

    String var(String name) {
        String v = vars.get(name);
        return v == null ? "" : v;
    }

    List<String> secrets() {
        return fields.secrets();
    }

    RunContext withTestsuite(String name) {
        return new RunContext(fields.withTestsuite(name), vars);
    }

    RunContext withTestcase(String name) {
        return new RunContext(fields.withTestcase(name), vars);
    }

    RunContext withExecutor(String name) {
        return new RunContext(fields.withExecutor(name), vars);
    }

    RunContext withSecrets(List<String> secrets) {
        return new RunContext(fields.withSecrets(secrets), vars);
    }

    RunContext withVars(Map<String, String> v) {
        return new RunContext(fields, v);
    }
}
