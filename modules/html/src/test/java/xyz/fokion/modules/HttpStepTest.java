package xyz.fokion.modules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.fokion.modules.core.models.Result;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
class HttpStepTest {

    private static final String WEBHOOK_URL = "https://webhook.site/b8c1747f-8be7-4bb8-8d68-0d37c835b833";
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private String body;
    private Map<String, String> context;

    @BeforeEach
    void setUp() {
        // Initialize common test data
        headers = new HashMap<>();
        queryParams = new HashMap<>();
        body = "";
        context = new HashMap<>();

        // Add common headers
        headers.put("Accept", "application/json");
        headers.put("X-Test-Header", "{{headerValue}}");

        // Add common query parameters
        queryParams.put("testParam", "{{paramValue}}");

        // Add context values
        context.put("headerValue", "test-header-value");
        context.put("paramValue", "test-param-value");
        context.put("bodyValue", "test-body-value");
    }

    @Test
    void testGetRequest() throws ExecutionException, InterruptedException {
        HttpStep step = new HttpStep(
                "test-get",
                "GET",
                WEBHOOK_URL,
                headers,
                queryParams,
                null
        );

        CompletableFuture<Result> future = step.execute(context);
        Result result = future.get();

        assertNotNull(result);
        assertNotNull(result.output());
    }

    @Test
    void testPostRequest() throws ExecutionException, InterruptedException {
        body = "{\"test\": \"{{bodyValue}}\"}";

        HttpStep step = new HttpStep(
                "test-post",
                "POST",
                WEBHOOK_URL,
                headers,
                queryParams,
                body
        );

        CompletableFuture<Result> future = step.execute(context);
        Result result = future.get();

        assertNotNull(result);
        assertNotNull(result.output());
    }

    @Test
    void testPutRequest() throws ExecutionException, InterruptedException {
        body = "{\"test\": \"{{bodyValue}}\"}";

        HttpStep step = new HttpStep(
                "test-put",
                "PUT",
                WEBHOOK_URL,
                headers,
                queryParams,
                body
        );

        CompletableFuture<Result> future = step.execute(context);
        Result result = future.get();

        assertNotNull(result);
        assertNotNull(result.output());
    }

    @Test
    void testDeleteRequest() throws ExecutionException, InterruptedException {
        HttpStep step = new HttpStep(
                "test-delete",
                "DELETE",
                WEBHOOK_URL,
                headers,
                queryParams,
                null
        );

        CompletableFuture<Result> future = step.execute(context);
        Result result = future.get();

        assertNotNull(result);
        assertNotNull(result.output());
    }

    @Test
    void testHeadRequest() throws ExecutionException, InterruptedException {
        HttpStep step = new HttpStep(
                "test-head",
                "HEAD",
                WEBHOOK_URL,
                headers,
                queryParams,
                null
        );

        CompletableFuture<Result> future = step.execute(context);
        Result result = future.get();

        assertNotNull(result);
    }

    @Test
    void testNestedPlaceholders() throws ExecutionException, InterruptedException {
        // Setup nested placeholders
        context.put("nested", "{{bodyValue}}");
        context.put("bodyValue", "final-value");

        body = "{\"test\": \"{{nested}}\"}";

        HttpStep step = new HttpStep(
                "test-nested",
                "POST",
                WEBHOOK_URL,
                headers,
                queryParams,
                body
        );

        CompletableFuture<Result> future = step.execute(context);
        Result result = future.get();

        assertNotNull(result);
        assertNotNull(result.output());
    }

    @Test
    void testUrlWithPlaceholders() throws ExecutionException, InterruptedException {
        context.put("webhookId", "b8c1747f-8be7-4bb8-8d68-0d37c835b833");

        HttpStep step = new HttpStep(
                "test-url-placeholder",
                "GET",
                "https://webhook.site/{{webhookId}}",
                headers,
                queryParams,
                null
        );

        CompletableFuture<Result> future = step.execute(context);
        Result result = future.get();

        assertNotNull(result);
        assertNotNull(result.output());
    }

    @Test
    void testMissingPlaceholderValue() throws ExecutionException, InterruptedException {
        // Remove a context value to test missing placeholder handling
        context.remove("headerValue");

        HttpStep step = new HttpStep(
                "test-missing-placeholder",
                "GET",
                WEBHOOK_URL,
                headers,
                queryParams,
                null
        );

        CompletableFuture<Result> future = step.execute(context);
        Result result = future.get();

        assertNotNull(result);
        assertNotNull(result.output());
    }

    @Test
    void testInvalidUrl() {
        HttpStep step = new HttpStep(
                "test-invalid-url",
                "GET",
                "invalid-url",
                headers,
                queryParams,
                null
        );

        CompletableFuture<Result> future = step.execute(context);

        assertThrows(ExecutionException.class, future::get);
    }
}