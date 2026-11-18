package eu.kalafatic.dcode.config.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.model.ModelDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ENR provider over the family ENR 1.0 wire contract (base {@code /enr/v1}):
 * health, model discovery, blocking chat. ENR is the default family inference
 * engine; this provider makes DCode consume it exactly like any other provider
 * through the existing {@link LlmProvider} SPI.
 *
 * <p>Wire-only integration: DCode never binds ENR classes. The endpoint is
 * configurable ({@code dcode.enr.endpoint} system property, then
 * {@link #DEFAULT_BASE_URL}), the optional bearer token is supplied through
 * {@link #updateConfiguration} and never logged.</p>
 */
public final class EnrProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:28780";
    public static final String DEFAULT_PROVIDER_ID = "enr";

    private final String providerId;
    private final String displayName;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpLlmSupport http;
    private volatile String authToken;

    public EnrProvider(String providerId, String displayName, String baseUrl,
                       Duration timeout) {
        this.providerId = providerId != null ? providerId : DEFAULT_PROVIDER_ID;
        this.displayName = displayName != null ? displayName : "ENR - EVO Native Runtime";
        String configured = System.getProperty("dcode.enr.endpoint");
        String resolved = configured != null && !configured.isBlank() ? configured : baseUrl;
        this.baseUrl = resolved != null && !resolved.isBlank() ? resolved : DEFAULT_BASE_URL;
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.http = new HttpLlmSupport(() -> authToken, this.timeout);
    }

    public static EnrProvider localDefault() {
        return new EnrProvider(DEFAULT_PROVIDER_ID, null, DEFAULT_BASE_URL, null);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public List<ModelDefinition> listModels() {
        JsonNode root = http.getJson(baseUrl + "/enr/v1/models", Duration.ofSeconds(20));
        JsonNode models = root.isArray() ? root : root.path("models");
        if (!models.isArray()) {
            throw new ProviderException("ENR response has no models array");
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (JsonNode model : models) {
            String id = model.path("id").asText(model.path("name").asText(""));
            if (id.isBlank()) {
                continue;
            }
            boolean available = model.path("available").asBoolean(true);
            result.add(new ModelDefinition(id, model.path("name").asText(id), providerId,
                    model.path("contextLength").asLong(0), available));
        }
        return result;
    }

    @Override
    public ConnectionTestResult testConnection() {
        try {
            JsonNode health = http.getJson(baseUrl + "/health", Duration.ofSeconds(10));
            String status = health.path("status").asText("");
            if ("UP".equalsIgnoreCase(status) || "healthy".equalsIgnoreCase(status)) {
                return ConnectionTestResult.ok("ENR reachable (" + status + ")");
            }
            return ConnectionTestResult.ok("ENR reachable, runtime status " + status);
        } catch (ProviderException e) {
            return ConnectionTestResult.error(e.getMessage());
        }
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        ObjectNode body = new ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        body.put("model", request.model());
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode entry = messages.addObject();
            entry.put("role", message.role().name().toLowerCase());
            entry.put("content", message.content());
        }
        if (request.temperature() > 0) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() > 0) {
            body.put("maxTokens", request.maxTokens());
        }
        JsonNode response = http.postJson(baseUrl + "/enr/v1/chat", http.write(body), timeout);
        String content = response.path("content").asText("");
        String finish = response.path("finishReason").asText(null);
        if (finish == null || finish.isBlank()) {
            String reason = response.path("reason").asText("");
            finish = reason.isBlank() ? null : reason;
        }
        int promptTokens = response.path("promptTokens").asInt(0);
        int completionTokens = response.path("completionTokens").asInt(0);
        if (promptTokens == 0) {
            promptTokens = response.path("prompt_tokens").asInt(0);
        }
        if (completionTokens == 0) {
            completionTokens = response.path("generated_tokens").asInt(0);
        }
        return new ChatResponse(content, finish, promptTokens, completionTokens);
    }

    @Override
    public void updateConfiguration(Map<String, String> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        for (Map.Entry<String, String> entry : configuration.entrySet()) {
            String value = entry.getValue();
            switch (entry.getKey()) {
                case "endpoint", "baseUrl", "base_url" -> {
                    if (value == null || value.isBlank()) {
                        throw new ProviderException("ENR endpoint must not be blank");
                    }
                }
                case "authToken", "auth_token", "token" -> {
                    if (value != null && !value.isBlank() && value.length() < 8) {
                        throw new ProviderException("ENR auth token is too short");
                    }
                    authToken = (value == null || value.isBlank()) ? null : value;
                }
                default -> {
                }
            }
        }
    }

    @Override
    public Map<String, String> getConfiguration() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("endpoint", baseUrl);
        config.put("authToken", authToken == null ? "" : "****** (configured)");
        return config;
    }
}
