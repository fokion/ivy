package xyz.fokion.ivy.core.connector.remote;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLSocketFactory;

import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.connector.ConnectorInfo;
import xyz.fokion.ivy.core.connector.ConnectorInfo.PropertyInfo;
import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.connector.ConnectorKey;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.Framework;
import xyz.fokion.ivy.spi.StepContext;

/**
 * Connectors served by an {@code ivy-connector-server}.
 * <p>
 * Each session has a connection of its own, borrowed from a pool, so sessions of test cases
 * running at the same time do not wait for each other. The server closes the sessions of a
 * connection when it ends.
 * <pre>
 *  openSession ──► borrow: an idle connection, or a new one (connect + HELLO)
 *                    │
 *                    ▼
 *               in use by one session: OPEN, RUN..., CLOSE
 *                    ├─ CLOSE answered         ──► back to the idle pool
 *                    ├─ I/O error              ──► closed and dropped (other sessions go on)
 *                    └─ closed while a call is
 *                       running (step timeout) ──► closed and dropped, which ends the call
 * </pre>
 * The idle pool never holds more connections than were in use at the same time.
 */
public final class RemoteConnectorInfoManager implements ConnectorInfoManager {

    private final String address;
    private final String host;
    private final int port;
    private final String key;
    private final boolean tls;
    private final Map<String, ConnectorFacade> facades = new LinkedHashMap<>();
    private final Deque<Connection> idle = new ArrayDeque<>();
    /** Every open connection, idle or in use, closed with the manager. */
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** How long a response may take; steps have their own timeouts, this catches dead servers. */
    static final int READ_TIMEOUT_MILLIS = 30 * 60 * 1000;

    private RemoteConnectorInfoManager(String host, int port, String key, boolean tls) {
        this.address = host + ":" + port;
        this.host = host;
        this.port = port;
        this.key = key;
        this.tls = tls;
    }

    /**
     * Connects to {@code [key@]host:port[?tls]} and lists its connectors.
     */
    public static RemoteConnectorInfoManager connect(String spec) throws IOException {
        String key = "";
        String rest = spec;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            key = rest.substring(0, at);
            rest = rest.substring(at + 1);
        }
        boolean tls = false;
        if (rest.endsWith("?tls")) {
            tls = true;
            rest = rest.substring(0, rest.length() - 4);
        }
        int colon = rest.lastIndexOf(':');
        if (colon < 0) {
            throw new IOException("invalid connector server \"" + rest + "\", expected [key@]host:port");
        }
        String host = rest.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(rest.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IOException("invalid port in connector server \"" + rest + "\"");
        }
        return connect(host, port, key, tls);
    }

    public static RemoteConnectorInfoManager connect(String host, int port, String key, boolean tls) throws IOException {
        RemoteConnectorInfoManager m = new RemoteConnectorInfoManager(host, port, key, tls);
        Connection first = m.newConnection();
        if (first.hello.get("connectors") instanceof List<?> connectors) {
            for (Object c : connectors) {
                ConnectorInfo info = decodeInfo(Cast.toStringMap(c));
                m.facades.put(info.type(), m.new RemoteFacade(info));
            }
        }
        m.release(first);
        return m;
    }

    /** Opens and authenticates a connection. */
    private Connection newConnection() throws IOException {
        if (closed) {
            throw new IOException("connection to connector server " + address + " is closed");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            if (tls) {
                javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, host, port, true);
                javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                socket = ssl;
            }
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        Connection c = new Connection(socket);
        connections.add(c);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", "HELLO");
        req.put("key", key);
        req.put("framework", Framework.VERSION);
        try {
            c.hello = c.call(req);
        } catch (IOException | RuntimeException e) {
            c.abort();
            throw e;
        }
        return c;
    }

    /** An idle connection, or a new one. */
    private Connection borrow() throws IOException {
        synchronized (idle) {
            Connection c = idle.pollFirst();
            if (c != null) {
                return c;
            }
        }
        return newConnection();
    }

    /** Returns a connection to the idle pool, unless it is broken. */
    private void release(Connection c) {
        if (c.broken || closed) {
            c.abort();
            return;
        }
        synchronized (idle) {
            idle.addFirst(c);
        }
    }

    /** The number of open connections, idle or in use. */
    public int openConnections() {
        return connections.size();
    }

    private static ConnectorInfo decodeInfo(Map<String, Object> m) {
        Map<String, Object> key = Cast.toStringMap(m.get("key"));
        List<PropertyInfo> properties = new ArrayList<>();
        if (m.get("properties") instanceof List<?> props) {
            for (Object p : props) {
                Map<String, Object> pm = Cast.toStringMap(p);
                properties.add(new PropertyInfo(Cast.toString(pm.get("name")), Cast.toString(pm.get("type")),
                        Cast.toBool(pm.get("required")), Cast.toBool(pm.get("secret")), Cast.toString(pm.get("help"))));
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> defaults = m.get("defaultAssertions") instanceof List<?> l ? (List<Object>) l : null;
        Map<String, String> resultFields = new LinkedHashMap<>();
        if (m.get("resultFields") instanceof Map<?, ?> fields) {
            fields.forEach((k, v) -> resultFields.put(String.valueOf(k), Cast.toString(v)));
        }
        return new ConnectorInfo(new ConnectorKey(Cast.toString(key.get("bundleName")),
                Cast.toString(key.get("bundleVersion")), Cast.toString(key.get("type"))),
                Cast.toString(m.get("displayName")), properties, defaults, resultFields);
    }

    /** Encodes a connector description, the server side of {@link #decodeInfo}. */
    public static Map<String, Object> encodeInfo(ConnectorInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("bundleName", info.key().bundleName());
        key.put("bundleVersion", info.key().bundleVersion());
        key.put("type", info.key().type());
        m.put("key", key);
        m.put("displayName", info.displayName());
        List<Object> props = new ArrayList<>();
        for (PropertyInfo p : info.properties()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("type", p.type());
            pm.put("required", p.required());
            pm.put("secret", p.secret());
            pm.put("help", p.help());
            props.add(pm);
        }
        m.put("properties", props);
        m.put("defaultAssertions", info.defaultAssertions());
        m.put("resultFields", new LinkedHashMap<>(info.resultFields()));
        return m;
    }

    /** One connection to the server, used by one session at a time. */
    private final class Connection {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private long nextId = 1;
        /** The answer to HELLO. */
        Map<String, Object> hello = Map.of();
        /** Set once the stream may be desynchronized: the connection is never reused. */
        volatile boolean broken;
        /** Set while a request waits for its answer. */
        volatile boolean busy;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        synchronized Map<String, Object> call(Map<String, Object> request) throws IOException {
            if (broken) {
                throw new IOException("connection to connector server " + address + " was lost");
            }
            long id = nextId++;
            request.put("id", id);
            Map<String, Object> resp;
            busy = true;
            try {
                Frames.write(out, request);
                resp = Frames.read(in);
                if (resp == null) {
                    throw new IOException("connector server " + address + " closed the connection");
                }
                if (Cast.toLong(resp.get("id")) != id) {
                    throw new IOException("connector server " + address + " answered out of order");
                }
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            } finally {
                busy = false;
            }
            if (!Cast.toBool(resp.get("ok"))) {
                throw new ConnectorException(Cast.toString(resp.get("error")));
            }
            return resp;
        }

        /** Closes the socket, which also ends a call waiting for its answer. */
        void abort() {
            broken = true;
            connections.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private static void replayLogs(Map<String, Object> resp, StepContext context) {
        if (resp.get("logs") instanceof List<?> logs) {
            for (Object l : logs) {
                Map<String, Object> entry = Cast.toStringMap(l);
                StepContext.Level level;
                try {
                    level = StepContext.Level.valueOf(Cast.toString(entry.get("level")));
                } catch (IllegalArgumentException e) {
                    level = StepContext.Level.INFO;
                }
                context.log(level, Cast.toString(entry.get("message")));
            }
        }
    }

    /**
     * The variables sent with a request: those of the suite and the test case and {@code ivy};
     * the environment, the values of other test cases and results stay here.
     */
    private static Map<String, Object> portableVars(StepContext context) {
        Map<String, Object> vars = new LinkedHashMap<>();
        context.vars().forEach((k, v) -> {
            if (!k.equals("env") && !k.equals("cases") && !k.equals("result")) {
                vars.put(k, v);
            }
        });
        return vars;
    }

    private Map<String, Object> request(String op, String session, StepContext context) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", op);
        if (session != null) {
            req.put("session", session);
        }
        if (context != null) {
            req.put("vars", WireCodec.encode(portableVars(context)));
            req.put("step", WireCodec.encode(context.step()));
        }
        return req;
    }

    private final class RemoteFacade implements ConnectorFacade {
        private final ConnectorInfo info;

        RemoteFacade(ConnectorInfo info) {
            this.info = info;
        }

        @Override
        public ConnectorInfo info() {
            return info;
        }

        @Override
        public Session openSession(StepContext context) throws IOException {
            Map<String, Object> req = request("OPEN", null, context);
            req.put("type", info.type());
            Connection connection = borrow();
            Map<String, Object> resp;
            try {
                resp = connection.call(req);
            } catch (IOException | RuntimeException e) {
                release(connection);
                throw e;
            }
            replayLogs(resp, context);
            String session = Cast.toString(resp.get("session"));
            return new Session() {
                @Override
                public Object run(StepContext ctx) throws IOException {
                    Map<String, Object> r = connection.call(request("RUN", session, ctx));
                    replayLogs(r, ctx);
                    if (r.get("error") instanceof String error && !error.isEmpty()) {
                        throw new ConnectorException(error);
                    }
                    return WireCodec.decode(r.get("result"));
                }

                @Override
                public void close() throws IOException {
                    if (connection.busy) {
                        // a call timed out and still runs: dropping the connection ends it, and
                        // the server closes the session
                        connection.abort();
                        return;
                    }
                    try {
                        connection.call(request("CLOSE", session, null));
                    } finally {
                        release(connection);
                    }
                }
            };
        }
    }

    @Override
    public List<ConnectorInfo> connectorInfos() {
        return facades.values().stream().map(ConnectorFacade::info).toList();
    }

    @Override
    public Optional<ConnectorFacade> find(String type) {
        return Optional.ofNullable(facades.get(type));
    }

    @Override
    public void close() {
        closed = true;
        synchronized (idle) {
            idle.clear();
        }
        connections.forEach(Connection::abort);
    }
}
