package xyz.fokion.ivy.core.connector.remote;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * Connectors served by an {@code ivy-connector-server}. One connection carries all sessions;
 * requests are serialized.
 */
public final class RemoteConnectorInfoManager implements ConnectorInfoManager {

    private final String address;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Map<String, ConnectorFacade> facades = new LinkedHashMap<>();
    private long nextId = 1;

    private RemoteConnectorInfoManager(String address, Socket socket) throws IOException {
        this.address = address;
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
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
            throw new IOException("invalid connector server \"" + spec + "\", expected [key@]host:port");
        }
        String host = rest.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(rest.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IOException("invalid port in connector server \"" + spec + "\"");
        }
        return connect(host, port, key, tls);
    }

    public static RemoteConnectorInfoManager connect(String host, int port, String key, boolean tls) throws IOException {
        Socket socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        socket.setTcpNoDelay(true);
        RemoteConnectorInfoManager m = new RemoteConnectorInfoManager(host + ":" + port, socket);
        try {
            m.hello(key);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        return m;
    }

    private void hello(String key) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", "HELLO");
        req.put("key", key);
        req.put("framework", Framework.VERSION);
        Map<String, Object> resp = call(req);
        if (!(resp.get("connectors") instanceof List<?> connectors)) {
            return;
        }
        for (Object c : connectors) {
            ConnectorInfo info = decodeInfo(Cast.toStringMap(c));
            facades.put(info.type(), new RemoteFacade(info));
        }
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
        return new ConnectorInfo(new ConnectorKey(Cast.toString(key.get("bundleName")),
                Cast.toString(key.get("bundleVersion")), Cast.toString(key.get("type"))),
                Cast.toString(m.get("displayName")), properties, defaults, WireCodec.decode(m.get("zeroValueResult")));
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
        m.put("zeroValueResult", WireCodec.encode(info.zeroValueResult()));
        return m;
    }

    private synchronized Map<String, Object> call(Map<String, Object> request) throws IOException {
        request.put("id", nextId++);
        Frames.write(out, request);
        Map<String, Object> resp = Frames.read(in);
        if (resp == null) {
            throw new IOException("connector server " + address + " closed the connection");
        }
        if (!Cast.toBool(resp.get("ok"))) {
            throw new ConnectorException(Cast.toString(resp.get("error")));
        }
        return resp;
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

    private Map<String, Object> request(String op, String session, StepContext context) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", op);
        if (session != null) {
            req.put("session", session);
        }
        if (context != null) {
            req.put("vars", context.vars());
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
            Map<String, Object> resp = call(req);
            replayLogs(resp, context);
            String session = Cast.toString(resp.get("session"));
            return new Session() {
                @Override
                public Object run(StepContext ctx) throws IOException {
                    Map<String, Object> r = call(request("RUN", session, ctx));
                    replayLogs(r, ctx);
                    if (r.get("error") instanceof String error && !error.isEmpty()) {
                        throw new ConnectorException(error);
                    }
                    return WireCodec.decode(r.get("result"));
                }

                @Override
                public void close() throws IOException {
                    call(request("CLOSE", session, null));
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
    public void close() throws IOException {
        socket.close();
    }
}
