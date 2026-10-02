package xyz.fokion.ivy.core.connector;

import xyz.fokion.ivy.spi.StepContext;

/**
 * The application view of a connector, local or remote.
 */
public interface ConnectorFacade {

    ConnectorInfo info();

    /** Starts a session, which lives as long as one test case. */
    Session openSession(StepContext context) throws Exception;

    interface Session extends AutoCloseable {

        /** Runs one step: binds its configuration and calls the connector. */
        Object run(StepContext context) throws Exception;

        @Override
        void close() throws Exception;
    }
}
