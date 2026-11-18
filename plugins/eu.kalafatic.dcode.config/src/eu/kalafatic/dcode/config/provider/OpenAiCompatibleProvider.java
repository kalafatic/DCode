package eu.kalafatic.dcode.config.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.model.ModelDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Generic OpenAI-compatible chat-completions provider ({@code /chat/completions}).
 *
 * <p>Serves hosted providers such as DeepSeek and custom OpenAI-compatible endpoints.
 * The API key is fetched at request time from the supplied supplier (typically the
 * credential vault) and is never stored in this instance's visible state.</p>
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

    private final String providerId;
    private final String displayName;
    private final String baseUrl;
    private final Supplier<String> apiKeySupplier;
    private final Duration timeout;
    private final HttpLlmSupport http;
    private final Map<String, String> configuration;

    public OpenAiCompatibleProvider(String providerId, String displayName, String baseUrl,
                                    Supplier<String> apiKeySupplier, Duration timeout) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.displayName = displayName != null ? displayName : providerId;
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.apiKeySupplier = apiKeySupplier;
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.http = new HttpLlmSupport(apiKeySupplier, this.timeout);
        this.configuration = new java.util.concurrent.ConcurrentHashMap<>();
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
        return false;
    }

    @Override
    public List<ModelDefinition> listModels() {
        JsonNode root = http.getJson(baseUrl + "/models", Duration.ofSeconds(30));
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new ProviderException(providerId + " response has no models array");
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (JsonNode entry : data) {
            String id = entry.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            result.add(new ModelDefinition(id, id, providerId, 0, true));
        }
        return result;
    }

    @Override
    public ConnectionTestResult testConnection() {
        try {
            List<ModelDefinition> models = listModels();
            return ConnectionTestResult.ok(displayName + " reachable, " + models.size()
                    + " model(s) published");
        } catch (ProviderException e) {
            return ConnectionTestResult.error(e.getMessage());
        }
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        ObjectNode body = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
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
            body.put("max_tokens", request.maxTokens());
        }
        body.put("stream", false);

        JsonNode response = http.postJson(baseUrl + "/chat/completions", http.write(body), timeout);
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ProviderException(providerId + " response has no choices");
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").asText("");
        String finish = choices.get(0).path("finish_reason").asText(null);
        int promptTokens = response.path("usage").path("prompt_tokens").asInt(0);
        int completionTokens = response.path("usage").path("completion_tokens").asInt(0);
        return new ChatResponse(content, finish, promptTokens, completionTokens);
    }

    @Override
    public void updateConfiguration(Map<String, String> config) {
        configuration.clear();
        if (config != null) {
            config.forEach((k, v) -> {
                if (v != null && !isSecretKey(k)) {
                    configuration.put(k, v);
                }
            });
        }
    }

    @Override
    public Map<String, String> getConfiguration() {
        return Map.copyOf(configuration);
    }

    private static boolean isSecretKey(String key) {
        return key != null && key.toLowerCase().contains("key");
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
