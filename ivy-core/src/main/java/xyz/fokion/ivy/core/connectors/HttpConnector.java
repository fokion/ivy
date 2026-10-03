package xyz.fokion.ivy.core.connectors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.DefaultAssertionsProvider;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.LazyJson;

/**
 * HTTP requests on {@link HttpClient}, with the semantics of Go's {@code net/http} client.
 * <p>
 * Differences: {@code unix_sock} is not supported; with {@code resolve} the request goes to the
 * resolved host with the original {@code Host} header, and certificates are checked against the
 * original host.
 * <p>
 * Result: {@code status}, {@code headers} (lower-case names), {@code body} (parsed when it is
 * JSON, else the text), {@code bodyText}, {@code request} ({@code method}, {@code url},
 * {@code headers}, {@code body}), {@code durationMs} and {@code error}.
 */
@ConnectorClass(type = "http", configurationClass = HttpConfiguration.class)
public final class HttpConnector implements Connector<HttpConfiguration>, DefaultAssertionsProvider {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "status", "the HTTP status code",
                "headers", "the response headers, by lower-case name: result.headers[\"content-type\"]",
                "body", "the body, parsed when it is JSON, text otherwise",
                "bodyText", "the body as text",
                "request", "the request sent: method, url, headers, body",
                "durationMs", "the duration in milliseconds",
                "error", "why the request failed, empty otherwise");
    }

    /**
     * Lets steps set the Host header, as with Go. The JDK reads the property when its HTTP client
     * is first used, so it is set at run time, before any client is created (a static initializer
     * may run at build time in the native image).
     */
    private static void allowHostHeader() {
        String allowed = System.getProperty("jdk.httpclient.allowRestrictedHeaders", "");
        if (!allowed.contains("host")) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", allowed.isEmpty() ? "host" : allowed + ",host");
        }
    }

    @Override
    public List<Object> defaultAssertions() {
        return List.of("result.status == 200");
    }

    private static Map<String, Object> request(String method, String url, Map<String, List<String>> header, String body) {
        Map<String, Object> headers = new LinkedHashMap<>();
        header.forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("url", url);
        request.put("headers", headers);
        request.put("body", body);
        return request;
    }

    private static Struct result(long durationMs, int status, Map<String, Object> request, Object body, String bodyText,
            Map<String, String> headers) {
        return Struct.builder("Result")
                .put("status", (long) status)
                .put("headers", headers)
                .put("body", body)
                .put("bodyText", bodyText)
                .put("request", request)
                .put("durationMs", durationMs)
                .put("error", "")
                .build();
    }

    @Override
    public Object run(HttpConfiguration e, StepContext context) throws Exception {
        allowHostHeader();
        if (!e.unixSock().isEmpty()) {
            if (!e.resolve().isEmpty()) {
                throw new ConnectorException("you can't use resolve and unix_sock attributes in the same time");
            }
            throw new ConnectorException("unix_sock is not supported by the ivy http connector");
        }
        Path workdir = context.workdir();
        String method = e.method().isEmpty() ? "GET" : e.method();
        String url = e.url() + e.path();
        if ((!e.body().isEmpty() || !e.bodyFile().isEmpty()) && e.multipartForm() != null) {
            throw new ConnectorException("can only use one of 'body', 'body_file' and 'multipart_form'");
        }

        byte[] body = new byte[0];
        String contentType = null;
        if (!e.body().isEmpty()) {
            body = e.body().getBytes(StandardCharsets.UTF_8);
        } else if (!e.bodyFile().isEmpty()) {
            body = bodyFile(e, workdir, context);
        } else if (e.multipartForm() != null) {
            String boundary = UUID.randomUUID().toString().replace("-", "");
            body = multipart(e.multipartForm(), boundary);
            contentType = "multipart/form-data; boundary=" + boundary;
        }

        if (!e.queryParameters().isEmpty()) {
            url = withQuery(url, e.queryParameters());
        }
        URI uri;
        try {
            uri = new URI(escapeIllegal(url));
        } catch (URISyntaxException ex) {
            throw new ConnectorException("parse \"" + url + "\": " + ex.getMessage(), ex);
        }

        // headers as Go's http.Header: canonical names, several values
        Map<String, List<String>> requestHeaders = new TreeMap<>();
        if (!e.basicAuthUser().isEmpty() || !e.basicAuthPassword().isEmpty()) {
            String token = Base64.getEncoder().encodeToString((e.basicAuthUser() + ":" + e.basicAuthPassword())
                    .getBytes(StandardCharsets.UTF_8));
            requestHeaders.put("Authorization", List.of("Basic " + token));
        }
        if (contentType != null) {
            requestHeaders.put("Content-Type", List.of(contentType));
        }
        e.headers().forEach((k, v) -> requestHeaders.put(canonicalHeaderKey(k), List.of(v)));

        for (String r : e.resolve()) {
            if (r.split(":").length != 3) {
                throw new ConnectorException("invalid value for resolve attribute: " + e.resolve());
            }
        }

        String requestBody = new String(body, StandardCharsets.UTF_8);
        String requestContentType = requestHeaders.getOrDefault("Content-Type", List.of("")).getFirst();
        if (e.preserveBodyFile() || !isContentTypeSupported(requestContentType)) {
            requestBody = "";
        }
        Map<String, Object> request = request(method, uri.toString(), requestHeaders, requestBody);

        TlsMaterial tls = new TlsMaterial(readOrInline(e.tlsRootCa(), workdir), readOrInline(e.tlsClientCert(), workdir),
                readOrInline(e.tlsClientKey(), workdir));
        long start = System.nanoTime();
        HttpResponse<byte[]> resp = send(e, uri, method, body, new TreeMap<>(requestHeaders), tls);
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        String respContentType = resp.headers().firstValue("content-type").orElse("");
        String bodyText = "";
        Object parsedBody = "";
        if (!e.skipBody() && isContentTypeSupported(respContentType)) {
            bodyText = new String(resp.body(), StandardCharsets.UTF_8);
            String mediaType = mediaType(respContentType);
            // parsed the first time an expression reads into it
            parsedBody = mediaType.contains("json") ? LazyJson.orText(bodyText) : bodyText;
        }
        Map<String, String> headers = null;
        if (!e.skipHeaders()) {
            headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> h : new TreeMap<>(resp.headers().map()).entrySet()) {
                if (h.getKey().startsWith(":")) {
                    continue;
                }
                String key = h.getKey().toLowerCase(Locale.ROOT);
                headers.put(key, String.join(key.equals("set-cookie") ? "; " : ", ", h.getValue()));
            }
        }
        return result(durationMs, resp.statusCode(), request, parsedBody, bodyText, headers);
    }

    private static byte[] bodyFile(HttpConfiguration e, Path workdir, StepContext context) throws IOException {
        Path file = Path.of(e.bodyFile());
        if (!file.isAbsolute()) {
            file = workdir.resolve(e.bodyFile());
        }
        if (!Files.exists(file)) {
            return new byte[0];
        }
        byte[] content = Files.readAllBytes(file);
        if (e.preserveBodyFile()) {
            return content;
        }
        String str = new String(content, StandardCharsets.UTF_8);
        try {
            str = context.interpolate(str);
        } catch (RuntimeException ex) {
            throw new ConnectorException("unable to render file " + file + ": " + ex.getMessage(), ex);
        }
        return str.getBytes(StandardCharsets.UTF_8);
    }

    private record TlsMaterial(byte[] roots, byte[] clientCert, byte[] clientKey) {
    }

    /** Where a request goes once {@code resolve} is applied. */
    record Target(URI uri, String verifyHost, String hostHeader) {
    }

    /** Applies {@code host:port:address} overrides: the URL host is replaced, the Host header kept. */
    static Target target(URI uri, List<String> resolve) throws URISyntaxException {
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        for (String r : resolve) {
            String[] tuple = r.split(":");
            if (tuple[0].equalsIgnoreCase(uri.getHost()) && tuple[1].equals(Integer.toString(port))) {
                // rebuilt from the raw parts so that escaped characters stay escaped
                String authority = (uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo() + "@") + tuple[2]
                        + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
                String rebuilt = uri.getScheme() + "://" + authority
                        + (uri.getRawPath() == null ? "" : uri.getRawPath())
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                        + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
                return new Target(new URI(rebuilt), uri.getHost(), uri.getRawAuthority());
            }
        }
        return new Target(uri, null, null);
    }

    private static final int MAX_REDIRECTS = 10;

    /**
     * Sends the request, following redirects as Go's {@code http.Client} does: 301/302/303 turn
     * the request into a GET without body, 307/308 keep both; credentials and cookies set by the
     * step are not sent to another domain; at most 10 redirects.
     */
    /**
     * The threads of every client: shared, as a client is built per request and per redirect hop
     * (its cookie jar lasts one step), and closing a client does not shut down an executor it was
     * given.
     */
    private static final java.util.concurrent.ExecutorService CLIENT_THREADS =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private static HttpResponse<byte[]> send(HttpConfiguration e, URI uri, String method, byte[] body,
            Map<String, List<String>> headers, TlsMaterial tls) throws Exception {
        CookieJar jar = new CookieJar();
        URI current = uri;
        String currentMethod = method;
        byte[] currentBody = body;
        for (int hop = 0; ; hop++) {
            Target t = target(current, e.resolve());
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .cookieHandler(jar)
                    .executor(CLIENT_THREADS)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(proxy(e.proxy()));
            if ("https".equalsIgnoreCase(current.getScheme())) {
                builder.version(HttpClient.Version.HTTP_2);
                try {
                    builder.sslContext(Tls.context(e.ignoreVerifySsl(), tls.roots(), tls.clientCert(), tls.clientKey(),
                            t.verifyHost()));
                } catch (GeneralSecurityException ex) {
                    throw new ConnectorException(ex.getMessage(), ex);
                }
            } else {
                builder.version(HttpClient.Version.HTTP_1_1);
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(t.uri()).method(currentMethod,
                    currentBody.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(currentBody));
            headers.forEach((k, values) -> values.forEach(v -> req.header(k, v)));
            if (t.hostHeader() != null && !headers.containsKey("Host")) {
                req.header("Host", t.hostHeader());
            }
            HttpResponse<byte[]> resp;
            try (HttpClient client = builder.build()) {
                resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException ex) {
                throw new ConnectorException(currentMethod + " \"" + current + "\": "
                        + (ex.getMessage() == null ? ex.toString() : ex.getMessage()), ex);
            }
            int status = resp.statusCode();
            String location = resp.headers().firstValue("location").orElse(null);
            boolean redirect = status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
            if (e.noFollowRedirect() || !redirect || location == null) {
                return resp;
            }
            if (hop + 1 > MAX_REDIRECTS) {
                throw new ConnectorException(method + " \"" + location + "\": stopped after 10 redirects");
            }
            URI next = current.resolve(escapeIllegal(location));
            if (status != 307 && status != 308) {
                if (!currentMethod.equals("GET") && !currentMethod.equals("HEAD")) {
                    currentMethod = "GET";
                }
                currentBody = new byte[0];
            }
            if (!sameOrSubdomain(current.getHost(), next.getHost())) {
                for (String sensitive : List.of("Authorization", "Www-Authenticate", "Cookie", "Cookie2")) {
                    headers.remove(sensitive);
                }
            }
            // a Host header set by the step only follows relative redirects
            if (new URI(escapeIllegal(location)).isAbsolute()) {
                headers.remove("Host");
            }
            current = next;
        }
    }

    /** Go's {@code shouldCopyHeaderOnRedirect}: same host, or a subdomain of it. */
    static boolean sameOrSubdomain(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        String f = from.toLowerCase(Locale.ROOT);
        String t = to.toLowerCase(Locale.ROOT);
        return t.equals(f) || t.endsWith("." + f);
    }

    /** A form part per value; a value {@code @path[;type=content/type]} attaches a file. */
    private static byte[] multipart(Object form, String boundary) throws IOException {
        if (!(form instanceof Map<?, ?> fields)) {
            throw new ConnectorException("'multipart_form' should be a map");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<?, ?> field : fields.entrySet()) {
            String key = String.valueOf(field.getKey());
            List<String> values = parseFieldValues(field);
            for (String value : values) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                Path file = null;
                String type = "application/octet-stream";
                if (value.startsWith("@")) {
                    String p = value.substring(1);
                    int idx = p.lastIndexOf(";type=");
                    if (idx >= 0) {
                        type = p.substring(idx + ";type=".length());
                        p = p.substring(0, idx);
                    }
                    if (Files.exists(Path.of(p))) {
                        file = Path.of(p);
                    }
                }
                if (file != null) {
                    out.write(("Content-Disposition: form-data; name=\"" + escapeQuotes(key) + "\"; filename=\""
                            + escapeQuotes(file.getFileName().toString()) + "\"\r\nContent-Type: " + type + "\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write(Files.readAllBytes(file));
                } else {
                    out.write(("Content-Disposition: form-data; name=\"" + escapeQuotes(key) + "\"\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write(value.getBytes(StandardCharsets.UTF_8));
                }
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static List<String> parseFieldValues(Map.Entry<?, ?> field) {
        List<String> values = new ArrayList<>();
        if (field.getValue() instanceof String s) {
            values.add(s);
        } else if (field.getValue() instanceof List<?> l) {
            for (Object item : l) {
                if (!(item instanceof String s)) {
                    throw new ConnectorException("'multipart_form' list values must be strings");
                }
                values.add(s);
            }
        } else {
            throw new ConnectorException("'multipart_form' values must be a string or a list of strings");
        }
        return values;
    }

    /**
     * Percent-encodes the characters Go's URL parser accepts but {@link URI} rejects (spaces,
     * braces, non-ASCII...), so that such URLs are sent as Go sends them.
     */
    static String escapeIllegal(String url) {
        StringBuilder sb = new StringBuilder(url.length());
        for (byte b : url.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c <= 0x20 || c >= 0x7f || "\"<>\\^`{|}".indexOf(c) >= 0) {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private static String escapeQuotes(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Go's {@code url.Values.Encode}: keys sorted. */
    private static String withQuery(String url, Map<String, String> params) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> p : new TreeMap<>(params).entrySet()) {
            if (!q.isEmpty()) {
                q.append('&');
            }
            q.append(GoStrings.queryEscape(p.getKey())).append('=').append(GoStrings.queryEscape(p.getValue()));
        }
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? "" : url.substring(hash);
        String base = hash < 0 ? url : url.substring(0, hash);
        int question = base.indexOf('?');
        if (question >= 0) {
            base = base.substring(0, question);
        }
        return base + "?" + q + fragment;
    }

    /** A file path (relative to the suite) or the PEM content itself. */
    private static byte[] readOrInline(String value, Path workdir) throws IOException {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Path p = Path.of(value).isAbsolute() ? Path.of(value) : workdir.resolve(value);
        try {
            if (Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
        } catch (java.nio.file.InvalidPathException ignored) {
            // inline content
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ProxySelector proxy(String proxy) throws URISyntaxException {
        if (proxy.isEmpty()) {
            return new EnvironmentProxySelector();
        }
        URI p = new URI(proxy);
        int port = p.getPort() != -1 ? p.getPort() : "https".equalsIgnoreCase(p.getScheme()) ? 443 : 80;
        return ProxySelector.of(new InetSocketAddress(p.getHost(), port));
    }

    /** Go's {@code http.ProxyFromEnvironment}. */
    private static final class EnvironmentProxySelector extends ProxySelector {
        @Override
        public List<java.net.Proxy> select(URI uri) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String noProxy = env("NO_PROXY");
            if (!noProxy.isEmpty()) {
                for (String entry : noProxy.split(",")) {
                    String e = entry.strip().toLowerCase(Locale.ROOT);
                    if (e.equals("*") || (!e.isEmpty() && (host.equals(e.startsWith(".") ? e.substring(1) : e)
                            || host.endsWith(e.startsWith(".") ? e : "." + e)))) {
                        return List.of(java.net.Proxy.NO_PROXY);
                    }
                }
            }
            if (host.equals("localhost") || host.startsWith("127.") || host.equals("::1")) {
                return List.of(java.net.Proxy.NO_PROXY);
            }
            String proxy = "https".equalsIgnoreCase(uri.getScheme()) ? env("HTTPS_PROXY") : env("HTTP_PROXY");
            if (proxy.isEmpty()) {
                return List.of(java.net.Proxy.NO_PROXY);
            }
            try {
                URI p = new URI(proxy.contains("://") ? proxy : "http://" + proxy);
                int port = p.getPort() != -1 ? p.getPort() : 80;
                SocketAddress address = InetSocketAddress.createUnresolved(p.getHost(), port);
                return List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP, address));
            } catch (URISyntaxException e) {
                return List.of(java.net.Proxy.NO_PROXY);
            }
        }

        private static String env(String name) {
            String v = System.getenv(name);
            if (v == null || v.isEmpty()) {
                v = System.getenv(name.toLowerCase(Locale.ROOT));
            }
            return v == null ? "" : v;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }

    /** Go's {@code textproto.CanonicalMIMEHeaderKey}. */
    static String canonicalHeaderKey(String key) {
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean token = c > 32 && c < 127 && "\"(),/:;<=>?@[\\]{}".indexOf(c) < 0;
            if (!token) {
                return key;
            }
        }
        StringBuilder sb = new StringBuilder(key.length());
        boolean upper = true;
        for (char c : key.toCharArray()) {
            if (upper && c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            } else if (!upper && c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            sb.append(c);
            upper = c == '-';
        }
        return sb.toString();
    }

    private static String mediaType(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
    }

    /** Whether a body of this type is text that can be reported. */
    static boolean isContentTypeSupported(String contentType) {
        String ct = mediaType(contentType);
        if (ct.endsWith("+json")) {
            return true;
        }
        if (ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/") || ct.startsWith("font/")
                || ct.startsWith("application/vnd.")) {
            return false;
        }
        if (ct.startsWith("application/")) {
            return !List.of("octet-stream", "x-abiword", "vnd.amazon.ebook", "x-bzip", "x-bzip2", "x-csh", "msword",
                    "epub+zip", "java-archive", "ogg", "pdf", "x-rar-compressed", "rtf", "x-sh", "x-shockwave-flash",
                    "x-tar", "zip", "x-7z-compressed").contains(ct.substring("application/".length()));
        }
        return !ct.contains("multipart/form-data");
    }
}
