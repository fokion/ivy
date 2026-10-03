package xyz.fokion.ivy.core.connectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import xyz.fokion.ivy.core.expr.Expression;
import xyz.fokion.ivy.core.expr.Scope;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.LazyJson;

/** The http connector against a local server. */
class HttpConnectorTest {

    private HttpServer server;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    private Struct run(Map<String, Object> step) throws Exception {
        return (Struct) TestContext.run(TestContext.of(step));
    }

    @Test
    void cookiesFollowRedirects() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/set", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "some-cookie=some-value; Path=/; Max-Age=100; HttpOnly");
            ex.getResponseHeaders().add("Location", "/get");
            respond(ex, 303, "");
        });
        server.createContext("/get", ex -> {
            calls.incrementAndGet();
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            respond(ex, cookie != null && cookie.contains("some-cookie=some-value") ? 200 : 400, "");
        });
        Struct result = run(Map.of("method", "GET", "url", url, "path", "/set"));
        assertEquals(200L, result.get("status"));
        assertEquals(1, calls.get());
    }

    @Test
    void joinsMultipleHeaderValues() throws Exception {
        server.createContext("/allow", ex -> {
            ex.getResponseHeaders().add("Allow", "GET");
            ex.getResponseHeaders().add("Allow", "POST");
            ex.getResponseHeaders().add("Allow", "HEAD");
            ex.getResponseHeaders().add("Allow", "OPTIONS");
            respond(ex, 200, "");
        });
        Struct result = run(Map.of("url", url, "path", "/allow"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) result.get("headers");
        assertEquals("GET, POST, HEAD, OPTIONS", headers.get("allow"));
    }

    @Test
    void parsesJsonBodies() throws Exception {
        server.createContext("/json", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            respond(ex, 200, "{\"items\":[{\"name\":\"a\"}],\"count\":1}");
        });
        Struct result = run(Map.of("url", url + "/json", "headers", Map.of("x-custom", "1")));
        LazyJson body = (LazyJson) result.get("body");
        assertFalse(body.isParsed(), "the body is parsed only when read");
        Scope scope = Scope.of(Map.of("result", result));
        assertEquals("a", Expression.compile("result.body.items[0].name").evaluate(scope));
        assertTrue(body.isParsed());
        Object parsed = body.value();
        assertEquals(1L, Expression.compile("result.body.count").evaluate(scope));
        assertSame(parsed, body.value(), "the body is parsed once");
        assertEquals("1", Expression.compile("result.request.headers['x-custom']").evaluate(scope));
        assertEquals("{\"items\":[{\"name\":\"a\"}],\"count\":1}", result.get("bodyText"));
    }

    @Test
    void sendsBodiesAndBasicAuth() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/post", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 201, "created");
        });
        Struct result = run(Map.of("method", "POST", "url", url + "/post", "body", "{\"a\":1}",
                "basic_auth_user", "u", "basic_auth_password", "p",
                "query_parameters", Map.of("b", "2", "a", "x y")));
        assertEquals(201L, result.get("status"));
        assertEquals("{\"a\":1}", received.get());
        assertEquals("Basic dTpw", auth.get());
        assertEquals("created", result.get("body"));
        assertEquals(url + "/post?a=x+y&b=2", ((Map<?, ?>) result.get("request")).get("url"));
    }

    private Path workdir() throws Exception {
        return Path.of(HttpConnectorTest.class.getResource("/http/bodyfile_with_interpolation").toURI()).getParent();
    }

    @Test
    void interpolatesBodyFiles() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "");
        });
        TestContext ctx = TestContext.of(Map.of("method", "POST", "url", url, "bodyfile", "bodyfile_with_interpolation"))
                .withVar("ivy.suite.workdir", workdir().toString())
                .withVar("fullName", "123 test");
        TestContext.run(ctx);
        assertEquals("{\n    \"key\": \"123 test\"\n}", received.get());
    }

    @Test
    void reportsUnresolvedBodyFileVariables() throws Exception {
        TestContext ctx = TestContext.of(Map.of("url", url, "bodyfile", "bodyfile_with_interpolation"))
                .withVar("ivy.suite.workdir", workdir().toString());
        ConnectorException e = assertThrows(ConnectorException.class, () -> TestContext.run(ctx));
        assertTrue(e.getMessage().startsWith("unable to render file "), e.getMessage());
        assertTrue(e.getMessage().contains("unknown variable fullName"), e.getMessage());
    }

    @Test
    void sendsMultipartFiles(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("upload-a.txt"), "content a");
        Path b = Files.writeString(dir.resolve("upload-b.md"), "content b");
        Path pdf = Files.writeString(dir.resolve("upload.pdf"), "fixture content");
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        server.createContext("/upload", ex -> {
            type.set(ex.getRequestHeaders().getFirst("Content-Type"));
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "");
        });
        run(Map.of("method", "POST", "url", url + "/upload", "multipart_form", Map.of(
                "files", List.of("@" + a + ";type=text/plain", "@" + b + ";type=text/markdown"))));
        assertTrue(type.get().startsWith("multipart/form-data; boundary="));
        String body = received.get();
        assertTrue(body.contains("name=\"files\"; filename=\"upload-a.txt\"\r\nContent-Type: text/plain\r\n\r\ncontent a"), body);
        assertTrue(body.contains("name=\"files\"; filename=\"upload-b.md\"\r\nContent-Type: text/markdown\r\n\r\ncontent b"), body);

        run(Map.of("method", "POST", "url", url + "/upload", "multipart_form", Map.of("files", "@" + pdf)));
        assertTrue(received.get().contains("filename=\"upload.pdf\"\r\nContent-Type: application/octet-stream"));
        run(Map.of("method", "POST", "url", url + "/upload", "multipart_form", Map.of("files", "@" + pdf + ";type=application/pdf")));
        assertTrue(received.get().contains("Content-Type: application/pdf"));
    }

    @Test
    void readsPemKeys() throws Exception {
        assertEquals("Content-Type", HttpConnector.canonicalHeaderKey("content-type"));
        assertEquals("X-Ovh-Queryid", HttpConnector.canonicalHeaderKey("x-ovh-queryid"));
        byte[] root = Files.readAllBytes(workdir().resolve("digicert-root-ca.crt"));
        Tls.context(false, root, null, null, null);
    }

    @Test
    void redirectsLikeGo() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        server.createContext("/see-other", ex -> {
            ex.getResponseHeaders().add("Location", "/target");
            respond(ex, 303, "");
        });
        server.createContext("/temporary", ex -> {
            ex.getResponseHeaders().add("Location", "/target");
            respond(ex, 307, "");
        });
        server.createContext("/target", ex -> {
            seen.set(ex.getRequestMethod() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "");
        });
        server.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            respond(ex, 302, "");
        });
        run(Map.of("method", "POST", "url", url + "/see-other", "body", "payload"));
        assertEquals("GET ", seen.get());
        run(Map.of("method", "POST", "url", url + "/temporary", "body", "payload"));
        assertEquals("POST payload", seen.get());
        assertEquals(302L, run(Map.of("url", url + "/loop", "no_follow_redirect", true)).get("status"));
        ConnectorException e = assertThrows(ConnectorException.class, () -> run(Map.of("url", url + "/loop")));
        assertTrue(e.getMessage().endsWith("stopped after 10 redirects"), e.getMessage());
    }

    @Test
    void dropsCredentialsOnRedirectsToOtherHosts() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        String otherHost = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/away", ex -> {
            ex.getResponseHeaders().add("Location", otherHost + "/whoami");
            respond(ex, 302, "");
        });
        server.createContext("/whoami", ex -> {
            auth.set(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            respond(ex, 200, "");
        });
        run(Map.of("url", url + "/away", "basic_auth_user", "u", "basic_auth_password", "p"));
        assertEquals("null", auth.get());
    }

    @Test
    void scopesCookies() {
        assertTrue(CookieJar.domainMatches("api.example.com", "example.com"));
        assertFalse(CookieJar.domainMatches("example.com", "api.example.com"));
        assertFalse(CookieJar.domainMatches("evil.com", "bank.com"));
        assertTrue(CookieJar.pathMatches("/foo/bar", "/foo"));
        assertFalse(CookieJar.pathMatches("/foobar", "/foo"));
    }

    @Test
    void resolveKeepsTheRawUrl() throws Exception {
        java.net.URI uri = new java.net.URI("https://api.example.com/a%2Fb?q=x%26y");
        assertEquals("https://127.0.0.1/a%2Fb?q=x%26y",
                HttpConnector.target(uri, List.of("api.example.com:443:127.0.0.1")).uri().toString());
    }
}
