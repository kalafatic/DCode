package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.ConnectionTestResult;
import eu.kalafatic.dcode.config.provider.LlmProvider;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Deterministic scripted LLM provider for harness tests. Responses are consumed in
 * order; the last response repeats when the queue is exhausted.
 */
public final class FakeLlmProvider implements LlmProvider {

    private final Deque<String> responses = new ArrayDeque<>();
    private final boolean throwing;
    private final String id;

    public FakeLlmProvider(String... scriptedResponses) {
        this("fake", false, scriptedResponses);
    }

    private FakeLlmProvider(String id, boolean throwing, String... scriptedResponses) {
        this.id = id;
        this.throwing = throwing;
        for (String response : scriptedResponses) {
            responses.addLast(response);
        }
    }

    /** A provider whose completion always fails (for failure-path tests). */
    public static FakeLlmProvider throwing() {
        return new FakeLlmProvider("fake-throwing", true);
    }

    @Override
    public String providerId() {
        return id;
    }

    @Override
    public String displayName() {
        return "Fake Test Provider";
    }

    @Override
    public String baseUrl() {
        return "fake://local";
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public List<ModelDefinition> listModels() {
        return List.of(new ModelDefinition("fake-model", "Fake Model", "fake", 4096, true));
    }

    @Override
    public ConnectionTestResult testConnection() {
        return ConnectionTestResult.ok("fake provider");
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        if (throwing) {
            throw new eu.kalafatic.dcode.config.provider.ProviderException(
                    "simulated provider failure");
        }
        String response;
        if (responses.size() > 1) {
            response = responses.removeFirst();
        } else {
            response = responses.peekFirst();
        }
        if (response == null) {
            response = "{\"final\":\"done\"}";
        }
        return new ChatResponse(response, "stop", 1, 1);
    }
}
