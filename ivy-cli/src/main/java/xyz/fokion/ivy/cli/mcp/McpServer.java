package xyz.fokion.ivy.cli.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.fokion.ivy.core.connector.ConnectorInfoManager;
import xyz.fokion.ivy.core.engine.Ivy;
import xyz.fokion.ivy.core.engine.Ivy.IvyException;
import xyz.fokion.ivy.core.util.Cast;
import xyz.fokion.ivy.spi.util.Json;

/**
 * An MCP server over stdio: JSON-RPC 2.0, one message per line. It serves the tools of
 * {@link Tools}, which let a client model write suites, validate and run them.
 * <pre>
 *  stdin ──► read loop ──┬─ initialize, ping, tools/list ──────────► answered at once
 *                        ├─ tools/call ──► virtual thread ──► Tools ─► answer (one lock on stdout)
 *                        │                   └─ progress notifications, when asked for
 *                        └─ notifications/cancelled ──► Ivy.cancel() of that call, no answer
 *  end of stdin ──► running calls are cancelled, the server returns
 * </pre>
 * Stdout carries protocol messages only; diagnostics go to {@code log}.
 */
public final class McpServer {

    /** The protocol versions this server speaks, the latest first. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    /** Creates a run configured from the command line settings, without connectors. */
    public interface RunFactory {
        Ivy create(PrintStream console) throws IvyException;
    }

    private final Tools tools;
    private final PrintStream log;
    private PrintStream out;
    /** The tool calls running, by request id: their run, to cancel. */
    private final Map<Object, Tools.Call> calls = new ConcurrentHashMap<>();

    public McpServer(RunFactory runs, Path workspace, boolean noRun, List<ConnectorInfoManager> connectors,
            PrintStream log) {
        this.tools = new Tools(runs, workspace, noRun, connectors);
        this.log = log;
    }

    /** Serves requests until {@code in} ends. */
    public void serve(InputStream in, PrintStream protocol) throws IOException {
        this.out = protocol;
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    receive(line, threads);
                }
            }
            // the client is gone: nobody reads the answers of running calls
            calls.values().forEach(Tools.Call::cancel);
        }
    }

    private void receive(String line, ExecutorService threads) {
        Map<String, Object> message;
        try {
            message = Cast.toStringMap(Json.parse(line));
        } catch (RuntimeException e) {
            send(error(null, PARSE_ERROR, "invalid JSON: " + e.getMessage()));
            return;
        }
        if (!(message.get("method") instanceof String method)) {
            // an answer to a request of ours: the server sends none
            return;
        }
        Map<String, Object> params = message.get("params") instanceof Map<?, ?> ? Cast.toStringMap(message.get("params"))
                : Map.of();
        if (!message.containsKey("id")) {
            notification(method, params);
            return;
        }
        Object id = message.get("id");
        try {
            switch (method) {
                case "initialize" -> send(result(id, initialize(params)));
                case "ping" -> send(result(id, Map.of()));
                case "tools/list" -> send(result(id, Map.of("tools", tools.list())));
                case "tools/call" -> call(id, params, threads);
                default -> send(error(id, METHOD_NOT_FOUND, "unknown method \"" + method + "\""));
            }
        } catch (RuntimeException e) {
            send(error(id, INTERNAL_ERROR, message(e)));
        }
    }

    private void notification(String method, Map<String, Object> params) {
        if (method.equals("notifications/cancelled")) {
            Tools.Call call = calls.get(params.get("requestId"));
            if (call != null) {
                log.println("mcp: cancelling request " + params.get("requestId")
                        + (params.get("reason") instanceof String r ? ": " + r : ""));
                call.cancel();
            }
        }
        // notifications/initialized and the others need nothing
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        String asked = Cast.toString(params.get("protocolVersion"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.getFirst());
        r.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        r.put("serverInfo", Map.of("name", "ivy", "title", "ivy integration tests", "version", Ivy.VERSION));
        r.put("instructions", Tools.INSTRUCTIONS);
        return r;
    }

    private void call(Object id, Map<String, Object> params, ExecutorService threads) {
        String name = Cast.toString(params.get("name"));
        if (!tools.has(name)) {
            send(error(id, INVALID_PARAMS, "unknown tool \"" + name + "\""));
            return;
        }
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?>
                ? Cast.toStringMap(params.get("arguments")) : Map.of();
        Object progressToken = params.get("_meta") instanceof Map<?, ?> meta ? meta.get("progressToken") : null;
        Tools.Call call = new Tools.Call(progressToken == null ? null
                : (done, total, text) -> send(progress(progressToken, done, total, text)));
        calls.put(id, call);
        threads.submit(() -> {
            Map<String, Object> result;
            try {
                result = tools.call(name, arguments, call);
            } catch (RuntimeException e) {
                log.println("mcp: tool " + name + " failed: " + e);
                result = Tools.failure("internal error: " + message(e));
            } finally {
                calls.remove(id);
            }
            if (!call.isCancelled()) {
                // a cancelled request gets no answer
                send(result(id, result));
            }
        });
    }

    // ------------------------------------------------------------ messages

    private void send(Map<String, Object> message) {
        String json = Json.write(message, Json.COMPACT);
        synchronized (this) {
            out.print(json);
            out.print('\n');
            out.flush();
        }
    }

    private static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    private static Map<String, Object> progress(Object token, long done, long total, String text) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("progressToken", token);
        params.put("progress", done);
        params.put("total", total);
        params.put("message", text);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("method", "notifications/progress");
        m.put("params", params);
        return m;
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
