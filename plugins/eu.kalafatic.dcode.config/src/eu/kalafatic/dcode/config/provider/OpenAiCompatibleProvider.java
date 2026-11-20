package eu.kalafatic.dcode.config.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.runtime.RuntimeAdapter;
import eu.kalafatic.dcode.config.runtime.RuntimeCapabilities;
import eu.kalafatic.dcode.config.runtime.RuntimeType;
import eu.kalafatic.dcode.config.runtime.StreamHandle;
import eu.kalafatic.dcode.config.runtime.StreamListener;
import eu.kalafatic.dcode.config.runtime.StreamResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Generic OpenAI-compatible chat-completions adapter ({@code /chat/completions},
 * blocking and SSE streaming).
 *
 * <p>Serves hosted providers such as DeepSeek and custom OpenAI-compatible endpoints.
 * The API key is fetched at request time from the supplied supplier (typically the
 * credential vault) and is never stored in this instance's visible state. Model
 * discovery is attempted through {@code /models}; endpoints that do not implement it
 * fail with a clear error instead of inventing models.</p>
 */
public final class OpenAiCompatibleProvider implements RuntimeAdapter {

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
    public String id() {
        return providerId;
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.OPENAI_COMPATIBLE;
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
        return false;
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(true, true, true, false, false, false,
                false, false, Set.of("OPENAI_CHAT_COMPLETIONS",
                "OPENAI_STREAMING", "OPENAI_MODEL_DISCOVERY"));
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
        ObjectNode body = writeValue(request);
        body.put("stream", false);

        JsonNode response = http.postJson(baseUrl + "/chat/completions",
                http.write(body), timeout);
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
    public StreamHandle stream(ChatRequest request, StreamListener listener) {
        ObjectNode body = writeValue(request);
        body.put("stream", true);
        StringBuilder content = new StringBuilder();
        return http.streamSse(baseUrl + "/chat/completions", http.write(body), timeout,
                new HttpLlmSupport.SseHandler() {
                    private String finishReason;
                    private int promptTokens;
                    private int completionTokens;

                    @Override
                    public void onEvent(HttpLlmSupport.SseEvent event) {
                        String data = event.data().strip();
                        if (data.equals("[DONE]")) {
                            return;
                        }
                        try {
                            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(data);
                            JsonNode choices = node.path("choices");
                            if (choices.isArray() && !choices.isEmpty()) {
                                JsonNode delta = choices.get(0).path("delta");
                                String chunk = delta.path("content").asText("");
                                if (!chunk.isEmpty()) {
                                    content.append(chunk);
                                    listener.onToken(chunk);
                                }
                                String reason = choices.get(0).path("finish_reason")
                                        .asText(null);
                                if (reason != null && !reason.isBlank()) {
                                    finishReason = reason;
                                }
                            }
                            JsonNode usage = node.path("usage");
                            if (usage.isObject()) {
                                promptTokens = usage.path("prompt_tokens").asInt(0);
                                completionTokens = usage.path("completion_tokens")
                                        .asInt(0);
                            }
                        } catch (Exception ignored) {
                            // non-JSON frame: ignore
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
        if (request.temperature() > 0) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        return body;
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
