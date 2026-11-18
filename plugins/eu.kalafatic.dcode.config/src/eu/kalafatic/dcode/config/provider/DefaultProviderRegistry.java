package eu.kalafatic.dcode.config.provider;

import eu.kalafatic.dcode.config.model.ModelDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory provider registry. */
public final class DefaultProviderRegistry implements ProviderRegistry {

    private final ConcurrentHashMap<String, LlmProvider> providers = new ConcurrentHashMap<>();

    @Override
    public void register(LlmProvider provider) {
        Objects.requireNonNull(provider, "provider");
        LlmProvider previous = providers.putIfAbsent(provider.providerId(), provider);
        if (previous != null) {
            throw new IllegalArgumentException("Provider already registered: "
                    + provider.providerId());
        }
    }

    @Override
    public void unregister(String providerId) {
        providers.remove(providerId);
    }

    @Override
    public List<LlmProvider> providers() {
        return List.copyOf(providers.values());
    }

    @Override
    public Optional<LlmProvider> find(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    @Override
    public List<ModelDefinition> listModels(String providerId) {
        return require(providerId).listModels();
    }

    @Override
    public List<ModelDefinition> listAllModels() {
        List<ModelDefinition> result = new ArrayList<>();
        for (LlmProvider provider : providers.values()) {
            try {
                result.addAll(provider.listModels());
            } catch (ProviderException e) {
                // an unreachable provider must not break discovery of the others
            }
        }
        return result;
    }

    @Override
    public ConnectionTestResult testConnection(String providerId) {
        return require(providerId).testConnection();
    }

    @Override
    public ChatResponse complete(String providerId, ChatRequest request) {
        return require(providerId).complete(request);
    }

    private LlmProvider require(String providerId) {
        LlmProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new ProviderException("Unknown provider: " + providerId);
        }
        return provider;
    }
}
