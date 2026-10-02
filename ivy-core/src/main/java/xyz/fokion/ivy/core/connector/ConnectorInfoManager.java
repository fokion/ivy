package xyz.fokion.ivy.core.connector;

import java.util.List;
import java.util.Optional;

/**
 * Lists the connectors available from one source: the classpath, a bundles directory or a
 * connector server.
 */
public interface ConnectorInfoManager extends AutoCloseable {

    List<ConnectorInfo> connectorInfos();

    /** The facade of the connector handling a step type. */
    Optional<ConnectorFacade> find(String type);

    @Override
    default void close() throws Exception {
    }
}
