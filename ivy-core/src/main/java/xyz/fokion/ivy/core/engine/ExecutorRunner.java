package xyz.fokion.ivy.core.engine;

import java.util.List;

import xyz.fokion.ivy.core.connector.ConnectorFacade;

/**
 * A resolved step executor with the step's retry and timeout settings.
 *
 * @param type {@code builtin}, {@code plugin} (a bundle or a connector server) or {@code user}
 * @param retryIf an expression: retries go on while it is true; empty for always
 * @param facade the connector, or {@code null} for a user executor or a step without type
 * @param user the user executor, or {@code null}
 */
record ExecutorRunner(String name, String type, int retry, String retryIf, int delay, int timeout,
        List<String> info, ConnectorFacade facade, UserExecutor user) {

    List<Object> defaultAssertions() {
        return facade == null ? null : facade.info().defaultAssertions();
    }

    boolean isUser() {
        return user != null;
    }
}
