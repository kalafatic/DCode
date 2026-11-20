package eu.kalafatic.dcode.config.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.runtime.RuntimeAdapter;
import eu.kalafatic.dcode.config.runtime.RuntimeCapabilities;
import eu.kalafatic.dcode.config.runtime.RuntimeStatus;
import eu.kalafatic.dcode.config.runtime.RuntimeType;
import eu.kalafatic.dcode.config.runtime.StreamHandle;
import eu.kalafatic.dcode.config.runtime.StreamListener;
import eu.kalafatic.dcode.config.runtime.StreamResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ollama adapter ({@code /api/chat} chat API incl. SSE streaming, {@code /api/tags}
 * model discovery, {@code /api/pull} model download, {@code /api/version} runtime
 * status). Local inference endpoint by default; the endpoint is configurable.
 *
 * <p>Ollama loads models lazily; the adapter does not falsely advertise explicit
 * load/unload operations.</p>
 */
public final class OllamaProvider implements RuntimeAdapter {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";

    private final String providerId;
    private final String displayName;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpLlmSupport http;

    public OllamaProvider(String providerId, String displayName, String baseUrl,
                          Duration timeout) {
        this.providerId = java.util.Objects.requireNonNull(providerId, "providerId");
        this.displayName = displayName != null ? displayName : "Ollama";
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL;
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.http = new HttpLlmSupport(null, this.timeout);
    }

    public static OllamaProvider localDefault() {
        return new OllamaProvider("ollama", "Ollama", DEFAULT_BASE_URL, null);
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.OLLAMA;
    }

    @Override
    public String endpoint() {
        return baseUrl;
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
    public RuntimeCapabilities capabilities() {
        // No explicit load/unload API: Ollama loads models lazily on first request.
        return new RuntimeCapabilities(true, true, true, false, false, true, true, true,
                Set.of("OLLAMA_CHAT", "OLLAMA_STREAMING", "OLLAMA_MODEL_DISCOVERY",
                        "OLLAMA_MODEL_PULL"));
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
            long sizeBytes = model.path("size").asLong(0);
            JsonNode details = model.path("details");
            String quantization = details.path("quantization_level").asText("");
            String family = details.path("family").asText("");
            Set<String> capabilities = modelCapabilities(model);
            result.add(new ModelDefinition(name, name, providerId, 0, chatCapable,
                    sizeBytes, quantization, false, capabilities));
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

    private static Set<String> modelCapabilities(JsonNode model) {
        Set<String> result = new LinkedHashSet<>();
        JsonNode capabilities = model.path("capabilities");
        if (capabilities.isArray()) {
            for (JsonNode capability : capabilities) {
                if (!capability.asText("").isBlank()) {
                    result.add(capability.asText(""));
                }
            }
        }
        return result;
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
    public RuntimeStatus runtimeStatus() {
        try {
            JsonNode version = http.getJson(baseUrl + "/api/version", Duration.ofSeconds(10));
            String versionText = version.path("version").asText("");
            return RuntimeStatus.ok(versionText.isBlank() ? "reachable"
                    : "version " + versionText);
        } catch (ProviderException e) {
            return RuntimeStatus.failed(e.getMessage());
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

    @Override
    public StreamHandle stream(ChatRequest request, StreamListener listener) {
        ObjectNode body = writeValue(request);
        body.put("stream", true);
        ObjectNode options = body.putObject("options");
        if (request.temperature() > 0) {
            options.put("temperature", request.temperature());
        }
        if (request.maxTokens() > 0) {
            options.put("num_predict", request.maxTokens());
        }
        StringBuilder content = new StringBuilder();
        return http.streamLines(baseUrl + "/api/chat", http.write(body), timeout,
                new HttpLlmSupport.LineHandler() {
                    private String finishReason;
                    private int promptTokens;
                    private int completionTokens;

                    @Override
                    public void onLine(String line) {
                        try {
                            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(line);
                            JsonNode message = node.path("message");
                            if (message.isMissingNode() || message.isNull()) {
                                return;
                            }
                            String chunk = message.path("content").asText("");
                            if (!chunk.isEmpty()) {
                                content.append(chunk);
                                listener.onToken(chunk);
                            }
                            if (node.path("done").asBoolean(false)) {
                                finishReason = node.path("done_reason").asText(null);
                                promptTokens = node.path("prompt_eval_count").asInt(0);
                                completionTokens = node.path("eval_count").asInt(0);
                            }
                        } catch (Exception ignored) {
                            // non-JSON line: ignore
                        }
                    }

                    @Override
                    public void onFinished() {
                        listener.onComplete(new StreamResult(new ChatResponse(
                                content.toString(), finishReason != null ? finishReason
                                : "stop", promptTokens, completionTokens), false, null));
                    }

                    @Override
                    public void onCancelled() {
                        listener.onComplete(StreamResult.cancelled(content.toString()));
                    }

                    @Override
                    public void onFailure(ProviderException error) {
                        listener.onError(error);
                    }
                });
    }

    @Override
    public ModelDefinition pullModel(String modelId) {
        ObjectNode body = new ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        body.put("name", modelId);
        body.put("stream", false);
        JsonNode response = http.postJson(baseUrl + "/api/pull", http.write(body),
                Duration.ofHours(1));
        String status = response.path("status").asText("");
        if (!"success".equalsIgnoreCase(status) && response.path("error").isValueNode()) {
            throw new ProviderException("Ollama pull failed: "
                    + response.path("error").asText(status));
        }
        return findModel(modelId).orElseGet(() -> new ModelDefinition(modelId, modelId,
                providerId, 0, true));
    }

    private java.util.Optional<ModelDefinition> findModel(String modelId) {
        try {
            return listModels().stream().filter(m -> m.id().equals(modelId)).findFirst();
        } catch (ProviderException e) {
            return java.util.Optional.empty();
        }
    }

    private ObjectNode writeValue(ChatRequest request) {
        ObjectNode body = new ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
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
