package xyz.fokion.ivy.core.connectors;

import java.util.LinkedHashMap;
import java.util.Map;

import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.connector.LocalConnectorFacade;
import xyz.fokion.ivy.core.expr.Scope;
import xyz.fokion.ivy.core.expr.Template;
import xyz.fokion.ivy.spi.StepContext;

/** A step context for connector tests. */
record TestContext(Map<String, Object> vars, Map<String, Object> step) implements StepContext {

    static TestContext of(Map<String, Object> step) {
        return new TestContext(new LinkedHashMap<>(), step);
    }

    /** Sets a variable; a dotted name such as {@code ivy.suite.workdir} makes nested maps. */
    @SuppressWarnings("unchecked")
    TestContext withVar(String path, Object value) {
        Map<String, Object> target = vars;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            target = (Map<String, Object>) target.computeIfAbsent(parts[i], _ -> new LinkedHashMap<>());
        }
        target.put(parts[parts.length - 1], value);
        return this;
    }

    @Override
    public String interpolate(String text) {
        return Template.has(text) ? Template.compile(text).renderString(Scope.of(vars)) : text;
    }

    @Override
    public void log(Level level, String message) {
    }

    /** Runs one step through the connector framework, as the engine does. */
    @SuppressWarnings("unchecked")
    static Object run(TestContext ctx) throws Exception {
        ConnectorFacade facade = new LocalConnectorFacade("test", "0", HttpConnector.class);
        try (ConnectorFacade.Session session = facade.openSession(ctx)) {
            return session.run(ctx);
        }
    }
}
