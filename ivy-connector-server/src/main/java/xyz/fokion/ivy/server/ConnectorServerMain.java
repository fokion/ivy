package xyz.fokion.ivy.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import xyz.fokion.ivy.core.connector.ConnectorInfo;
import xyz.fokion.ivy.core.connector.LocalConnectorInfoManager;

/**
 * {@code ivy-connector-server --bundles DIR [--host H] [--port P] [--key K] [--tls]}
 * <p>
 * The key may also come from the {@code IVY_CONNECTOR_KEY} environment variable. With
 * {@code --tls}, the keystore is configured by the {@code javax.net.ssl.keyStore*} system
 * properties.
 */
public final class ConnectorServerMain {

    private ConnectorServerMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String bundles = "bundles";
        String host = "0.0.0.0";
        int port = 8759;
        String key = System.getenv().getOrDefault("IVY_CONNECTOR_KEY", "");
        boolean tls = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--bundles" -> bundles = args[++i];
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--key" -> key = args[++i];
                case "--tls" -> tls = true;
                default -> {
                    System.err.println("usage: ivy-connector-server --bundles DIR [--host H] [--port P] [--key K] [--tls]");
                    System.exit(2);
                }
            }
        }
        if (key.isEmpty()) {
            System.err.println("a key is required: --key or IVY_CONNECTOR_KEY");
            System.exit(2);
        }
        LocalConnectorInfoManager connectors = LocalConnectorInfoManager.fromBundles(Path.of(bundles));
        ConnectorServer server = ConnectorServer.start(connectors, host, port, key, tls);
        for (ConnectorInfo info : connectors.connectorInfos()) {
            System.out.println("serving " + info.type() + " (" + info.key() + ")");
        }
        System.out.println("ivy-connector-server listening on " + host + ":" + server.port());
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
                connectors.close();
            } catch (IOException ignored) {
                // exiting anyway
            }
            done.countDown();
        }));
        done.await();
    }
}
