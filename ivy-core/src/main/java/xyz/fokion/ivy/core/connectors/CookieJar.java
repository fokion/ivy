package xyz.fokion.ivy.core.connectors;

import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A cookie jar for the redirects of one request (RFC 6265): {@code name=value} pairs are sent as
 * Go does ({@link java.net.CookieManager} sends RFC 2965 attributes that many servers reject).
 */
final class CookieJar extends CookieHandler {

    /** A stored cookie; host-only cookies (no Domain attribute) match their exact host only. */
    private record Stored(HttpCookie cookie, String domain, boolean hostOnly) {
    }

    private final List<Stored> cookies = new ArrayList<>();

    @Override
    public synchronized Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
        String host = host(uri);
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        cookies.removeIf(c -> c.cookie().hasExpired());
        List<String> pairs = new ArrayList<>();
        for (Stored s : cookies) {
            HttpCookie c = s.cookie();
            boolean domainOk = s.hostOnly() ? host.equals(s.domain()) : domainMatches(host, s.domain());
            if (domainOk && pathMatches(path, c.getPath()) && (!c.getSecure() || secure)) {
                pairs.add(c.getName() + "=" + c.getValue());
            }
        }
        return pairs.isEmpty() ? Map.of() : Map.of("Cookie", List.of(String.join("; ", pairs)));
    }

    @Override
    public synchronized void put(URI uri, Map<String, List<String>> responseHeaders) {
        String host = host(uri);
        for (Map.Entry<String, List<String>> h : responseHeaders.entrySet()) {
            if (h.getKey() == null || !h.getKey().equalsIgnoreCase("set-cookie")) {
                continue;
            }
            for (String header : h.getValue()) {
                List<HttpCookie> parsed;
                try {
                    parsed = HttpCookie.parse(header);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                for (HttpCookie c : parsed) {
                    boolean hostOnly = c.getDomain() == null || c.getDomain().isEmpty();
                    String domain = hostOnly ? host : c.getDomain().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                    // a server may only set cookies for its own domain or a parent of it
                    if (!hostOnly && !domainMatches(host, domain)) {
                        continue;
                    }
                    if (c.getPath() == null || !c.getPath().startsWith("/")) {
                        c.setPath(defaultPath(uri));
                    }
                    cookies.removeIf(o -> o.cookie().getName().equals(c.getName()) && o.domain().equals(domain)
                            && o.cookie().getPath().equals(c.getPath()));
                    if (!c.hasExpired()) {
                        cookies.add(new Stored(c, domain, hostOnly));
                    }
                }
            }
        }
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** RFC 6265 domain matching: the domain itself or a subdomain of it, never for IP addresses. */
    static boolean domainMatches(String host, String domain) {
        if (host.equals(domain)) {
            return true;
        }
        boolean ip = host.matches("[0-9.]+") || host.contains(":");
        return !ip && host.endsWith("." + domain);
    }

    /** RFC 6265 path matching: a prefix ending at a path segment boundary. */
    static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        if (!requestPath.startsWith(cookiePath)) {
            return false;
        }
        return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/';
    }

    private static String defaultPath(URI uri) {
        String p = uri.getPath() == null ? "" : uri.getPath();
        int slash = p.lastIndexOf('/');
        return slash <= 0 ? "/" : p.substring(0, slash);
    }
}
