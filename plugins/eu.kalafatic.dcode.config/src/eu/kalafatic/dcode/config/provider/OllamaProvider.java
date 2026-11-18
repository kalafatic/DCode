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

/**
 * Ollama provider ({@code /api/chat} chat API, {@code /api/tags} model discovery).
 * Local inference endpoint by default; the endpoint is configurable.
 */
public final class OllamaProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";

    private final String providerId;
    private final String displayName;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpLlmSupport http;

    public OllamaProvider(String providerId, String displayName, String baseUrl,
                          Duration timeout) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.displayName = displayName != null ? displayName : "Ollama";
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL;
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.http = new HttpLlmSupport(null, this.timeout);
    }

    public static OllamaProvider localDefault() {
        return new OllamaProvider("ollama", "Ollama", DEFAULT_BASE_URL, null);
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
        JsonNode root = http.getJson(baseUrl + "/api/tags", Duration.ofSeconds(20));
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new ProviderException("Ollama response has no models array");
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (JsonNode model : models) {
            String name = model.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            boolean chatCapable = isChatCapable(model);
            result.add(new ModelDefinition(name, name, providerId, 0, chatCapable));
        }
        return result;
    }

    /**
     * Capability detection: Ollama publishes per-model capabilities from the set
     * {@code completion, tools, insert, vision, thinking}. A model is chat-capable when
     * {@code completion} is present (or when no capability list is published by older
     * servers). Broken models may still fail at execution time; that is a model defect,
     * not a discovery concern.
     */
    private static boolean isChatCapable(JsonNode model) {
        JsonNode capabilities = model.path("capabilities");
        if (!capabilities.isArray() || capabilities.isEmpty()) {
            return true;
        }
        for (JsonNode capability : capabilities) {
            if ("completion".equals(capability.asText(""))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ConnectionTestResult testConnection() {
        try {
            List<ModelDefinition> models = listModels();
            return ConnectionTestResult.ok("Ollama reachable, " + models.size()
                    + " model(s) published");
        } catch (ProviderException e) {
            return ConnectionTestResult.error(e.getMessage());
        }
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        ObjectNode body = writeValue(request);
        body.put("stream", false);
        ObjectNode options = body.putObject("options");
        if (request.temperature() > 0) {
            options.put("temperature", request.temperature());
        }
        if (request.maxTokens() > 0) {
            options.put("num_predict", request.maxTokens());
        }
        JsonNode response = http.postJson(baseUrl + "/api/chat", http.write(body), timeout);
        String content = response.path("message").path("content").asText("");
        String finish = response.path("done_reason").asText(null);
        int promptTokens = response.path("prompt_eval_count").asInt(0);
        int completionTokens = response.path("eval_count").asInt(0);
        return new ChatResponse(content, finish, promptTokens, completionTokens);
    }

    private ObjectNode writeValue(ChatRequest request) {
        ObjectNode body = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        body.put("model", request.model());
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode entry = messages.addObject();
            entry.put("role", message.role().name().toLowerCase());
            entry.put("content", message.content());
        }
        return body;
    }
}
