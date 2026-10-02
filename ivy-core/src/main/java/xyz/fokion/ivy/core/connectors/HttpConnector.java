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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.fokion.ivy.core.template.Interpolator;
import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.DefaultAssertionsProvider;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.ZeroValueResultProvider;
import xyz.fokion.ivy.spi.util.Json;

/**
 * HTTP requests, port of venom's {@code http} executor on {@link HttpClient}.
 * <p>
 * Differences: {@code unix_sock} is not supported; with {@code resolve} the request goes to the
 * resolved host with the original {@code Host} header, and certificates are checked against the
 * original host.
 */
@ConnectorClass(type = "http", configurationClass = HttpConfiguration.class)
public final class HttpConnector implements Connector<HttpConfiguration>, DefaultAssertionsProvider,
        ZeroValueResultProvider {

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
        return List.of("result.statuscode ShouldEqual 200");
    }

    @Override
    public Object zeroValueResult() {
        return result(0, 0, request("", "", null, ""), "", null, null, "", "");
    }

    private static Struct request(String method, String url, Map<String, List<String>> header, String body) {
        return Struct.builder("HTTPRequest")
                .put("method", method)
                .put("url", url)
                .put("header", header)
                .put("body", body)
                .put("form", null)
                .put("post_form", null)
                .build();
    }

    private static Struct result(double seconds, int status, Struct request, String body, Object bodyJson,
            Map<String, String> headers, String err, String systemout) {
        return Struct.builder("Result")
                .put("timeseconds", seconds)
                .put("statuscode", (long) status)
                .put("request", request)
                .put("body", body)
                .put("bodyjson", bodyJson)
                .put("headers", headers)
                .put("err", err)
                .put("systemout", systemout)
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
            body = bodyFile(e, workdir, context, url);
        } else if (e.multipartForm() != null) {
            String boundary = UUID.randomUUID().toString().replace("-", "");
            body = multipart(e.multipartForm(), boundary, workdir);
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

        String verifyHost = null;
        URI target = uri;
        for (String r : e.resolve()) {
            String[] tuple = r.split(":");
            if (tuple.length != 3) {
                throw new ConnectorException("invalid value for resolve attribute: " + e.resolve());
            }
            int port = uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            if (tuple[0].equalsIgnoreCase(uri.getHost()) && tuple[1].equals(Integer.toString(port))) {
                verifyHost = uri.getHost();
                target = new URI(uri.getScheme(), uri.getUserInfo(), tuple[2], uri.getPort(), uri.getPath(),
                        uri.getQuery(), uri.getFragment());
                if (!requestHeaders.containsKey("Host")) {
                    requestHeaders.put("Host", List.of(uri.getRawAuthority()));
                }
            }
        }

        HttpClient.Builder builder = HttpClient.newBuilder()
                .cookieHandler(new CookieJar())
                .followRedirects(e.noFollowRedirect() ? HttpClient.Redirect.NEVER : HttpClient.Redirect.ALWAYS)
                .proxy(proxy(e.proxy()));
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            builder.version(HttpClient.Version.HTTP_2);
            try {
                builder.sslContext(Tls.context(e.ignoreVerifySsl(), readOrInline(e.tlsRootCa(), workdir),
                        readOrInline(e.tlsClientCert(), workdir), readOrInline(e.tlsClientKey(), workdir), verifyHost));
            } catch (GeneralSecurityException ex) {
                throw new ConnectorException(ex.getMessage(), ex);
            }
        } else {
            builder.version(HttpClient.Version.HTTP_1_1);
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(target)
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        requestHeaders.forEach((k, values) -> values.forEach(v -> req.header(k, v)));

        String requestBody = new String(body, StandardCharsets.UTF_8);
        String requestContentType = requestHeaders.getOrDefault("Content-Type", List.of("")).getFirst();
        if (e.preserveBodyFile() || !isContentTypeSupported(requestContentType)) {
            requestBody = "";
        }
        Struct request = request(method, uri.toString(), requestHeaders, requestBody);

        long start = System.nanoTime();
        HttpResponse<byte[]> resp;
        try (HttpClient client = builder.build()) {
            resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new ConnectorException(method + " \"" + uri + "\": " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()), ex);
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        String respContentType = resp.headers().firstValue("content-type").orElse("");
        String bodyString = "";
        Object bodyJson = null;
        String systemout = "";
        if (!e.skipBody() && isContentTypeSupported(respContentType)) {
            bodyString = new String(resp.body(), StandardCharsets.UTF_8);
            String mediaType = mediaType(respContentType);
            if (mediaType.contains("application/json") || mediaType.endsWith("+json")) {
                bodyJson = Json.tryParse(bodyString);
            }
            String shown = bodyJson != null ? Json.write(bodyJson, Json.GO) : bodyString;
            systemout = "===== Result Info =====\n\t\tMethod:     " + resp.request().method()
                    + "\n\t\tURL:        " + resp.uri() + "\n\t\tBody:       " + shown + "\n\t\t======================";
        }
        Map<String, String> headers = null;
        if (!e.skipHeaders()) {
            headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> h : new TreeMap<>(resp.headers().map()).entrySet()) {
                if (h.getKey().startsWith(":")) {
                    continue;
                }
                String key = canonicalHeaderKey(h.getKey());
                headers.put(key, String.join(key.equalsIgnoreCase("set-cookie") ? "; " : ", ", h.getValue()));
            }
        }
        return result(seconds, resp.statusCode(), request, bodyString, bodyJson, headers, "", systemout);
    }

    private static byte[] bodyFile(HttpConfiguration e, Path workdir, StepContext context, String url) throws IOException {
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
        Map<String, String> vars = context.vars();
        String str = new String(content, StandardCharsets.UTF_8);
        int upperLimit = vars.size();
        int counter = 0;
        while (str.contains("{{.")) {
            String next;
            try {
                next = Interpolator.interpolate(str, vars);
            } catch (Interpolator.InterpolationException ex) {
                throw new ConnectorException("unable to interpolate file " + url + ": " + ex.getMessage(), ex);
            }
            if (next.equals(str) && counter > upperLimit) {
                Matcher m = Pattern.compile("\\{\\{\\..*}}").matcher(str);
                List<String> unresolved = new ArrayList<>();
                while (m.find()) {
                    unresolved.add(m.group());
                }
                throw new ConnectorException("unable to interpolate file due to unresolved variables " + String.join(",", unresolved));
            }
            str = next;
            counter++;
        }
        return str.getBytes(StandardCharsets.UTF_8);
    }

    /** A form part per value; a value {@code @path[;type=content/type]} attaches a file. */
    private static byte[] multipart(Object form, String boundary, Path workdir) throws IOException {
        if (!(form instanceof Map<?, ?> fields)) {
            throw new ConnectorException("'multipart_form' should be a map");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<?, ?> field : fields.entrySet()) {
            String key = String.valueOf(field.getKey());
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
        String t = (semi < 0 ? contentType : contentType.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
        return t;
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
