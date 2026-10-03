package xyz.fokion.ivy.core.engine;

import java.time.Duration;
import java.util.List;

import xyz.fokion.ivy.core.connector.ConnectorFacade;

/**
 * A resolved step executor with the step's retry and timeout settings.
 *
 * @param type {@code builtin}, {@code plugin} (a bundle or a connector server) or {@code user}
 * @param retryIf an expression: retries go on while it is true; empty for always
 * @param delay the pause before each retry
 * @param timeout how long one attempt may take; zero for no limit
 * @param until an expression: the step is run again until it is true; empty when the step does not wait
 * @param within how long the step may wait for {@code until}
 * @param every the pause between two attempts while waiting
 * @param facade the connector, or {@code null} for a user executor or a step without type
 * @param user the user executor, or {@code null}
 */
record ExecutorRunner(String name, String type, int retry, String retryIf, Duration delay, Duration timeout,
        String until, Duration within, Duration every, List<String> info, ConnectorFacade facade, UserExecutor user) {

    List<Object> defaultAssertions() {
        return facade == null ? null : facade.info().defaultAssertions();
    }

    /** How long a step waits for {@code until} when it does not say, and how often it tries. */
    static final Duration DEFAULT_WITHIN = Duration.ofSeconds(30);
    static final Duration DEFAULT_EVERY = Duration.ofSeconds(1);

    boolean waits() {
        return !until.isEmpty();
    }

    boolean isUser() {
        return user != null;
    }
}
