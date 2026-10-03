package xyz.fokion.ivy.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.connector.ConnectorInfo;
import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.connector.ConnectorKey;
import xyz.fokion.ivy.core.connector.remote.RemoteConnectorInfoManager;
import xyz.fokion.ivy.spi.StepContext;

/**
 * The connections of a remote connector manager: one per session, borrowed from a pool.
 * <pre>
 *  test ── RemoteConnectorInfoManager ══ socket per session ══► ConnectorServer ── "wait" connector
 *                                                                    step block: true → waits on RELEASE
 * </pre>
 */
class RemoteConnectorPoolTest {

    private static volatile CountDownLatch release;
    private static volatile CountDownLatch blocked;
    private static final AtomicInteger CLOSED = new AtomicInteger();

    @BeforeEach
    void reset() {
        release = new CountDownLatch(1);
        blocked = new CountDownLatch(1);
        CLOSED.set(0);
    }

    /** A connector whose step returns "done", after waiting for {@link #release} when it has {@code block: true}. */
    private static final ConnectorInfoManager WAIT = new ConnectorInfoManager() {
        private final ConnectorInfo info = new ConnectorInfo(new ConnectorKey("test", "1", "wait"), "wait", List.of(), null,
                xyz.fokion.ivy.spi.Connector.fields("done", "always \"done\"", "waited", "whether it waited"));

        @Override
        public List<ConnectorInfo> connectorInfos() {
            return List.of(info);
        }

        @Override
        public Optional<ConnectorFacade> find(String type) {
            return type.equals("wait") ? Optional.of(facade) : Optional.empty();
        }

        private final ConnectorFacade facade = new ConnectorFacade() {
            @Override
            public ConnectorInfo info() {
                return info;
            }

            @Override
            public Session openSession(StepContext context) {
                return new Session() {
                    @Override
                    public Object run(StepContext ctx) throws Exception {
                        if (Boolean.TRUE.equals(ctx.step().get("block"))) {
                            blocked.countDown();
                            if (!release.await(30, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("never released");
                            }
                        }
                        return "done";
                    }

                    @Override
                    public void close() {
                        CLOSED.incrementAndGet();
                    }
                };
            }
        };
    };

    private static StepContext step(Map<String, Object> step) {
        return new StepContext() {
            @Override
            public Map<String, Object> vars() {
                return Map.of();
            }

            @Override
            public Map<String, Object> step() {
                return step;
            }

            @Override
            public void log(Level level, String message) {
            }
        };
    }

    private static final StepContext FAST = step(Map.of());
    private static final StepContext BLOCK = step(Map.of("block", true));

    private static ConnectorFacade waitConnector(RemoteConnectorInfoManager remote) {
        return remote.find("wait").orElseThrow();
    }

    @Test
    void sessionsDoNotWaitForEachOther() throws Exception {
        try (ConnectorServer server = ConnectorServer.start(WAIT, "127.0.0.1", 0, "k", false);
             RemoteConnectorInfoManager remote = RemoteConnectorInfoManager.connect("127.0.0.1", server.port(), "k", false)) {
            ConnectorFacade.Session slow = waitConnector(remote).openSession(FAST);
            CompletableFuture<Object> slowRun = CompletableFuture.supplyAsync(() -> run(slow, BLOCK));
            assertTrue(blocked.await(10, TimeUnit.SECONDS));

            // a second session gets a connection of its own, authenticated with the key
            ConnectorFacade.Session fast = waitConnector(remote).openSession(FAST);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals("done", fast.run(FAST)));
            assertEquals(2, remote.openConnections());

            release.countDown();
            assertEquals("done", slowRun.get(10, TimeUnit.SECONDS));
            slow.close();
            fast.close();
        }
    }

    @Test
    void reusesTheConnectionOfAClosedSession() throws Exception {
        try (ConnectorServer server = ConnectorServer.start(WAIT, "127.0.0.1", 0, "k", false);
             RemoteConnectorInfoManager remote = RemoteConnectorInfoManager.connect("127.0.0.1", server.port(), "k", false)) {
            for (int i = 0; i < 5; i++) {
                ConnectorFacade.Session s = waitConnector(remote).openSession(FAST);
                assertEquals("done", s.run(FAST));
                s.close();
            }
            // the connection of HELLO served every session, one after the other
            assertEquals(1, remote.openConnections());
            assertEquals(5, CLOSED.get());
        }
    }

    @Test
    void closingASessionWhileItRunsDropsOnlyItsConnection() throws Exception {
        try (ConnectorServer server = ConnectorServer.start(WAIT, "127.0.0.1", 0, "k", false);
             RemoteConnectorInfoManager remote = RemoteConnectorInfoManager.connect("127.0.0.1", server.port(), "k", false)) {
            ConnectorFacade.Session stuck = waitConnector(remote).openSession(FAST);
            CompletableFuture<Object> stuckRun = CompletableFuture.supplyAsync(() -> run(stuck, BLOCK));
            assertTrue(blocked.await(10, TimeUnit.SECONDS));

            // what the engine does when a step times out: close the session in the background
            assertTimeoutPreemptively(Duration.ofSeconds(5), stuck::close);
            ExecutionException ended = assertThrows(ExecutionException.class, () -> stuckRun.get(5, TimeUnit.SECONDS));
            assertTrue(ended.getCause() instanceof IOException, String.valueOf(ended.getCause()));

            // other sessions go on, on a new connection
            ConnectorFacade.Session next = waitConnector(remote).openSession(FAST);
            assertEquals("done", next.run(FAST));
            next.close();
            assertEquals(1, remote.openConnections());
            release.countDown();
        }
    }

    @Test
    void reportsALostServerWithoutHanging() throws Exception {
        ConnectorServer server = ConnectorServer.start(WAIT, "127.0.0.1", 0, "k", false);
        try (RemoteConnectorInfoManager remote = RemoteConnectorInfoManager.connect("127.0.0.1", server.port(), "k", false)) {
            ConnectorFacade.Session open = waitConnector(remote).openSession(FAST);
            server.close();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                assertThrows(IOException.class, () -> open.run(FAST));
                assertThrows(IOException.class, () -> waitConnector(remote).openSession(FAST));
            });
        }
    }

    @Test
    void carriesTheResultFieldsOfConnectors() throws Exception {
        try (ConnectorServer server = ConnectorServer.start(WAIT, "127.0.0.1", 0, "k", false);
             RemoteConnectorInfoManager remote = RemoteConnectorInfoManager.connect("127.0.0.1", server.port(), "k", false)) {
            ConnectorInfo info = remote.connectorInfos().getFirst();
            assertEquals(WAIT.connectorInfos().getFirst().resultFields(), info.resultFields());
            assertEquals(List.of("done", "waited"), List.copyOf(info.resultFields().keySet()), "in order");
        }
    }

    @Test
    void refusesAWrongKeyOnTheFirstConnection() throws Exception {
        try (ConnectorServer server = ConnectorServer.start(WAIT, "127.0.0.1", 0, "k", false)) {
            assertThrows(Exception.class, () -> RemoteConnectorInfoManager.connect("127.0.0.1", server.port(), "wrong", false));
        }
    }

    private static Object run(ConnectorFacade.Session s, StepContext ctx) {
        try {
            return s.run(ctx);
        } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }
}
