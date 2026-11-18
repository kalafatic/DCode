package eu.kalafatic.dcode.config.provider;

import eu.kalafatic.dcode.config.model.ModelDefinition;

import java.util.List;

/**
 * LLM provider SPI.
 *
 * <p>DCode is provider-independent: every provider implements this contract and is
 * registered in the provider registry. Provider-specific HTTP shapes, capability
 * detection and credentials stay inside the implementation; the harness never branches
 * on a concrete provider type.</p>
 */
public interface LlmProvider {

    /** Stable provider identity (e.g. {@code ollama}, {@code deepseek}). */
    String providerId();

    /** Human-readable name. */
    String displayName();

    /** API base endpoint (e.g. {@code http://127.0.0.1:11434}). */
    String baseUrl();

    /** True for local inference endpoints (e.g. Ollama on localhost). */
    boolean isLocal();

    /**
     * Discovers the models published by this provider. A provider that does not support
     * discovery returns its statically configured models instead.
     */
    List<ModelDefinition> listModels();

    /** Performs a live connection test against the endpoint. */
    ConnectionTestResult testConnection();

    /** Executes one chat completion. Never returns partial success silently. */
    ChatResponse complete(ChatRequest request);

    /**
     * Updates provider configuration (e.g. API key reference, timeout). Implementations
     * validate what they accept and ignore unknown keys.
     */
    default void updateConfiguration(java.util.Map<String, String> configuration) {
    }

    /** Current configuration without secrets (keys are masked). */
    default java.util.Map<String, String> getConfiguration() {
        return java.util.Map.of();
    }
}
