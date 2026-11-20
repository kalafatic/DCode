package eu.kalafatic.dcode.config.runtime;

import eu.kalafatic.dcode.config.provider.EnrProvider;
import eu.kalafatic.dcode.config.provider.OllamaProvider;
import eu.kalafatic.dcode.config.provider.OpenAiCompatibleProvider;

import java.util.function.Supplier;

/**
 * Creates runtime adapters from registrations. Credentials are injected as suppliers
 * bound to the credential vault, so secret values are never held by this factory.
 */
public final class RuntimeAdapterFactory {

    /**
     * Creates an adapter for the given registration.
     *
     * @param authTokenSupplier bearer-token source (ENR, OpenAI-compatible)
     * @param apiKeySupplier API key source (OpenAI-compatible)
     */
    public RuntimeAdapter create(RuntimeRegistration registration,
                                 Supplier<String> authTokenSupplier,
                                 Supplier<String> apiKeySupplier) {
        return switch (registration.type()) {
            case ENR -> new EnrProvider(registration.id(), registration.name(),
                    registration.endpoint(), null, authTokenSupplier);
            case OLLAMA -> new OllamaProvider(registration.id(), registration.name(),
                    registration.endpoint(), null);
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleProvider(registration.id(),
                    registration.name(), registration.endpoint(), apiKeySupplier, null);
        };
    }
}
