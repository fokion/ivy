package xyz.fokion.ivy.spi;

/**
 * Signals a connector failure; its message ends up in the step failure.
 */
public class ConnectorException extends RuntimeException {

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
