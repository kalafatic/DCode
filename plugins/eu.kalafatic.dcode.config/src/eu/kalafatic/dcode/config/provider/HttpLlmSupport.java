package eu.kalafatic.dcode.config.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared HTTP plumbing for LLM providers: JDK HttpClient, bearer token injection and
 * Jackson JSON handling. Provider implementations keep their wire shapes here, not in the
 * harness.
 */
public final class HttpLlmSupport {

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<String> apiKeySupplier;
    private final Duration defaultTimeout;

    public HttpLlmSupport(Supplier<String> apiKeySupplier, Duration defaultTimeout) {
        this.apiKeySupplier = apiKeySupplier;
        this.defaultTimeout = defaultTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public JsonNode postJson(String url, String requestJson, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout != null ? timeout : defaultTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson));
        applyAuth(builder);
        return send(builder.build());
    }

    public JsonNode getJson(String url, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout != null ? timeout : defaultTimeout)
                .GET();
        applyAuth(builder);
        return send(builder.build());
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new ProviderException("Failed to serialize request", e);
        }
    }

    private void applyAuth(HttpRequest.Builder builder) {
        if (apiKeySupplier != null) {
            String key = apiKeySupplier.get();
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
        }
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException("Provider request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Provider request interrupted");
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = response.body();
            if (body != null && body.length() > 300) {
                body = body.substring(0, 300);
            }
            throw new ProviderException("Provider returned HTTP " + status
                    + (body == null || body.isBlank() ? "" : ": " + body));
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new ProviderException("Failed to parse provider response", e);
        }
    }
}
