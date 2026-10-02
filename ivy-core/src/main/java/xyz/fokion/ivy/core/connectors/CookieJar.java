package xyz.fokion.ivy.core.connectors;

import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A cookie jar for the redirects of one request, sending RFC 6265 {@code name=value} pairs as Go
 * does ({@link java.net.CookieManager} sends RFC 2965 attributes that many servers reject).
 */
final class CookieJar extends CookieHandler {

    private final List<HttpCookie> cookies = new ArrayList<>();

    @Override
    public synchronized Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        cookies.removeIf(HttpCookie::hasExpired);
        List<String> pairs = new ArrayList<>();
        for (HttpCookie c : cookies) {
            String domain = c.getDomain().toLowerCase(Locale.ROOT);
            boolean domainOk = host.equals(domain) || (domain.startsWith(".") && host.endsWith(domain))
                    || host.endsWith("." + domain);
            String cookiePath = c.getPath() == null ? "/" : c.getPath();
            if (domainOk && path.startsWith(cookiePath) && (!c.getSecure() || secure)) {
                pairs.add(c.getName() + "=" + c.getValue());
            }
        }
        return pairs.isEmpty() ? Map.of() : Map.of("Cookie", List.of(String.join("; ", pairs)));
    }

    @Override
    public synchronized void put(URI uri, Map<String, List<String>> responseHeaders) {
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
                    if (c.getDomain() == null) {
                        c.setDomain(uri.getHost());
                    }
                    if (c.getPath() == null) {
                        String p = uri.getPath() == null ? "" : uri.getPath();
                        int slash = p.lastIndexOf('/');
                        c.setPath(slash <= 0 ? "/" : p.substring(0, slash));
                    }
                    cookies.removeIf(o -> o.getName().equals(c.getName()) && o.getDomain().equalsIgnoreCase(c.getDomain())
                            && o.getPath().equals(c.getPath()));
                    if (!c.hasExpired()) {
                        cookies.add(c);
                    }
                }
            }
        }
    }
}
