package xyz.fokion.ivy.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLServerSocketFactory;

import xyz.fokion.ivy.core.connector.ConnectorFacade;
import xyz.fokion.ivy.core.connector.ConnectorInfo;
import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.connector.remote.Frames;
import xyz.fokion.ivy.core.connector.remote.RemoteConnectorInfoManager;
import xyz.fokion.ivy.core.connector.remote.WireCodec;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.spi.Framework;
import xyz.fokion.ivy.spi.StepContext;

/**
 * Serves the connectors of a {@link ConnectorInfoManager} (usually a bundles directory) to
 * remote ivy processes, such as the native binary, which cannot load bundles itself.
 * <p>
 * Each connection is handled by a virtual thread; its sessions are closed when it ends.
 */
public final class ConnectorServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ConnectorServer.class.getName());

    private final ConnectorInfoManager connectors;
    private final byte[] key;
    private final ServerSocket serverSocket;
    private final List<Socket> clients = Collections.synchronizedList(new ArrayList<>());

    private ConnectorServer(ConnectorInfoManager connectors, String key, ServerSocket serverSocket) {
        this.connectors = connectors;
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.serverSocket = serverSocket;
    }

    /** Starts listening; {@code port} 0 picks a free port. */
    public static ConnectorServer start(ConnectorInfoManager connectors, String host, int port, String key, boolean tls)
            throws IOException {
        ServerSocket ss = tls ? SSLServerSocketFactory.getDefault().createServerSocket() : new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new java.net.InetSocketAddress(InetAddress.getByName(host), port));
        ConnectorServer server = new ConnectorServer(connectors, key, ss);
        Thread.ofVirtual().name("ivy-connector-server").start(server::acceptLoop);
        return server;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                clients.add(s);
                Thread.ofVirtual().name("ivy-connector-client").start(() -> serve(s));
            } catch (SocketException e) {
                return;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "accept failed", e);
            }
        }
    }

    private void serve(Socket socket) {
        Map<String, ConnectorFacade.Session> sessions = new ConcurrentHashMap<>();
        boolean authenticated = false;
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            while (true) {
                Map<String, Object> req = Frames.read(in);
                if (req == null) {
                    return;
                }
                Map<String, Object> resp;
                String op = Cast.toString(req.get("op"));
                if (!authenticated && !op.equals("HELLO")) {
                    resp = error("HELLO expected");
                } else {
                    resp = switch (op) {
                        case "HELLO" -> {
                            Map<String, Object> r = hello(req);
                            authenticated = Cast.toBool(r.get("ok"));
                            yield r;
                        }
                        case "OPEN" -> open(req, sessions);
                        case "RUN" -> run(req, sessions);
                        case "CLOSE" -> close(req, sessions);
                        default -> error("unknown operation \"" + op + "\"");
                    };
                }
                resp.put("id", req.get("id"));
                Frames.write(out, resp);
                if (!authenticated) {
                    return;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "connection ended", e);
        } finally {
            clients.remove(socket);
            sessions.values().forEach(s -> {
                try {
                    s.close();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "unable to close a session", e);
                }
            });
        }
    }

    private Map<String, Object> hello(Map<String, Object> req) {
        byte[] given = Cast.toString(req.get("key")).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, key)) {
            return error("invalid key");
        }
        if (!Framework.isCompatible(Cast.toString(req.get("framework")))) {
            return error("incompatible framework version " + req.get("framework") + ", server provides " + Framework.VERSION);
        }
        Map<String, Object> r = ok();
        List<Object> infos = new ArrayList<>();
        for (ConnectorInfo info : connectors.connectorInfos()) {
            infos.add(RemoteConnectorInfoManager.encodeInfo(info));
        }
        r.put("connectors", infos);
        r.put("framework", Framework.VERSION);
        return r;
    }

    private Map<String, Object> open(Map<String, Object> req, Map<String, ConnectorFacade.Session> sessions) {
        String type = Cast.toString(req.get("type"));
        ConnectorFacade facade = connectors.find(type).orElse(null);
        if (facade == null) {
            return error("connector \"" + type + "\" not found");
        }
        RecordingContext ctx = context(req);
        try {
            ConnectorFacade.Session session = facade.openSession(ctx);
            String id = UUID.randomUUID().toString();
            sessions.put(id, session);
            Map<String, Object> r = ok();
            r.put("session", id);
            r.put("logs", ctx.logs);
            return r;
        } catch (Exception e) {
            return error(message(e));
        }
    }

    private Map<String, Object> run(Map<String, Object> req, Map<String, ConnectorFacade.Session> sessions) {
        ConnectorFacade.Session session = sessions.get(Cast.toString(req.get("session")));
        if (session == null) {
            return error("unknown session");
        }
        RecordingContext ctx = context(req);
        Map<String, Object> r = ok();
        try {
            r.put("result", WireCodec.encode(session.run(ctx)));
        } catch (Exception e) {
            r.put("error", message(e));
        }
        r.put("logs", ctx.logs);
        return r;
    }

    private Map<String, Object> close(Map<String, Object> req, Map<String, ConnectorFacade.Session> sessions) {
        ConnectorFacade.Session session = sessions.remove(Cast.toString(req.get("session")));
        if (session == null) {
            return error("unknown session");
        }
        try {
            session.close();
            return ok();
        } catch (Exception e) {
            return error(message(e));
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }

    private static RecordingContext context(Map<String, Object> req) {
        Map<String, String> vars = new LinkedHashMap<>();
        Cast.toStringMap(req.get("vars")).forEach((k, v) -> vars.put(k, Cast.toString(v)));
        @SuppressWarnings("unchecked")
        Map<String, Object> step = WireCodec.decode(req.get("step")) instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return new RecordingContext(vars, step);
    }

    /** A step context whose log lines are sent back to the client. */
    private static final class RecordingContext implements StepContext {
        private final Map<String, String> vars;
        private final Map<String, Object> step;
        final List<Object> logs = new ArrayList<>();

        RecordingContext(Map<String, String> vars, Map<String, Object> step) {
            this.vars = Collections.unmodifiableMap(vars);
            this.step = Collections.unmodifiableMap(step);
        }

        @Override
        public Map<String, String> vars() {
            return vars;
        }

        @Override
        public Map<String, Object> step() {
            return step;
        }

        @Override
        public void log(Level level, String message) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("level", level.name());
            entry.put("message", message);
            synchronized (logs) {
                logs.add(entry);
            }
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        synchronized (clients) {
            for (Socket s : new ArrayList<>(clients)) {
                s.close();
            }
        }
    }
}
