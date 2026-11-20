package eu.kalafatic.dcode.config.provider;

import eu.kalafatic.dcode.config.model.ModelDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Provider registry contract: registration, discovery, connection tests and execution.
 * One registry per DCode runtime; no cross-product shared registries (family rule D7).
 */
public interface ProviderRegistry {

    void register(LlmProvider provider);

    void unregister(String providerId);

    List<LlmProvider> providers();

    Optional<LlmProvider> find(String providerId);

    /** Discovers models of one provider. */
    List<ModelDefinition> listModels(String providerId);

    /** Discovers models across all registered providers. */
    List<ModelDefinition> listAllModels();

    /** Live connection test of one provider. */
    ConnectionTestResult testConnection(String providerId);

    /** Executes one chat completion through the given provider. */
    ChatResponse complete(String providerId, ChatRequest request);

    /**
     * True when the provider participates in inference. Plain registries keep every
     * provider enabled; runtime registries honor the enabled flag of each registration.
     */
    default boolean isEnabled(String providerId) {
        return true;
    }
}
