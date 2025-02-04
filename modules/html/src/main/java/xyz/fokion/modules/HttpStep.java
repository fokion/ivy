package xyz.fokion.modules;

import xyz.fokion.modules.core.models.Result;
import xyz.fokion.modules.core.models.Step;

import java.net.Authenticator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static xyz.fokion.modules.core.handlers.StringHandling.replacePlaceholders;

public class HttpStep implements Step {
    private final String name;
    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final Map<String, String> queryParameters;
    private String body;


    public HttpStep(String name,
                    String method,
                    String url,
                    Map<String, String> headers,
                    Map<String, String> queryParameters,
                    String body) {
        this.name = name;
        this.method = method;
        this.url = url;
        this.headers = headers;
        this.queryParameters = queryParameters;
        this.body = body;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "HTTP";
    }

    @Override
    public CompletableFuture<Result> execute(Map<String, String> context) {

        Map<String, String> processedHeaders = applyContextToMap(headers, context);
        Map<String, String> processedQueryParams = applyContextToMap(queryParameters, context);
        String processedUrl = applyContextToUrl(url, context);
        String processedBody = applyContextToBody(body, context);


        URI uri = URI.create(processedUrl);
        String newQuery = uri.getQuery();
        StringBuilder parts = new StringBuilder();
        for(Map.Entry<String, String> query : processedQueryParams.entrySet()){
            if (newQuery == null) {
                newQuery = "";
                parts.append("?").append(String.format("%s=%s", query.getKey(), query.getValue()));
            }else {
                parts.append("&").append(String.format("%s=%s", query.getKey(), query.getValue()));
            }
        }
        newQuery = parts.toString();
        try {
            uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), newQuery, uri.getFragment());
            //apply context in body

            //do http call
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(getTimeoutLimit())
                    .header("Content-Type", "application/json");

            for (Map.Entry<String, String> header : processedHeaders.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }

            switch (method) {
                case "GET":
                    builder = builder.GET();
                    break;
                case "POST":
                    builder = builder.POST(HttpRequest.BodyPublishers.ofString(processedBody, StandardCharsets.UTF_8));
                    break;
                case "PUT":
                    builder = builder.PUT(HttpRequest.BodyPublishers.ofString(processedBody, StandardCharsets.UTF_8));
                    break;
                case "DELETE":
                    builder = builder.DELETE();
                    break;
                case "HEAD":
                    builder = builder.HEAD();
                    break;
                default:
                    return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            HttpRequest request = builder.build();

            try (HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(getTimeoutLimit())
                    .build()) {

                return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .thenApply(Result::new).exceptionallyAsync(ex -> {
                            System.err.println("Error: " + ex.getMessage());
                            throw new RuntimeException(ex);
                        });
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return CompletableFuture.failedFuture(new RuntimeException(e));
        }
    }

    private Map<String, String> applyContextToMap(Map<String, String> input, Map<String, String> context) {
        return input.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> replacePlaceholders(entry.getKey(), context, 10),
                        entry -> replacePlaceholders(entry.getValue(), context, 10)
                ));
    }

    private String applyContextToUrl(String url, Map<String, String> context) {
        return replacePlaceholders(url, context, 10);
    }

    private String applyContextToBody(String body, Map<String, String> context) {
        return replacePlaceholders(body, context, 10);
    }

}
