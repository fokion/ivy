package xyz.fokion.ivy.core.connectors;

import java.util.LinkedHashMap;
import java.util.Map;

import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.connector.LocalConnectorFacade;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.StepContext;

/** A step context for connector tests. */
record TestContext(Map<String, String> vars, Map<String, Object> step) implements StepContext {

    static TestContext of(Map<String, Object> step) {
        return new TestContext(new LinkedHashMap<>(), step);
    }

    TestContext withVar(String k, String v) {
        vars.put(k, v);
        return this;
    }

    @Override
    public void log(Level level, String message) {
    }

    /** Runs one step through the connector framework, as the engine does. */
    @SuppressWarnings("unchecked")
    static Object run(Class<?> connector, TestContext ctx) throws Exception {
        ConnectorFacade facade = new LocalConnectorFacade("test", "0", (Class<? extends Connector<?>>) connector);
        try (ConnectorFacade.Session session = facade.openSession(ctx)) {
            return session.run(ctx);
        }
    }
}
