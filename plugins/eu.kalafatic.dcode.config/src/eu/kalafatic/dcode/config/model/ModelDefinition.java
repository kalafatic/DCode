package eu.kalafatic.dcode.config.model;

import java.util.Objects;
import java.util.Set;

/**
 * One model published by an inference runtime.
 *
 * <p>The model identity always includes its runtime context ({@code providerId} is the
 * id of the runtime registration serving the model). The same model name served by two
 * runtimes yields two distinct definitions; they are never merged into one entry.</p>
 *
 * @param id model identifier as accepted by the runtime API (e.g. {@code qwen3-coder:32k})
 * @param name human-readable name
 * @param providerId id of the runtime registration serving this model
 * @param contextWindowTokens known context size, 0 when unknown
 * @param available whether the model can be used for inference right now
 * @param sizeBytes on-disk size in bytes, 0 when unknown
 * @param quantization quantization level (e.g. {@code Q4_K_M}), empty when unknown
 * @param loaded whether the model is currently loaded into memory (where supported)
 * @param capabilities capabilities reported by the runtime (e.g. {@code completion},
 *        {@code tools}, {@code vision}); empty when unknown
 */
public record ModelDefinition(
        String id,
        String name,
        String providerId,
        long contextWindowTokens,
        boolean available,
        long sizeBytes,
        String quantization,
        boolean loaded,
        Set<String> capabilities
) {
    public ModelDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerId, "providerId");
        name = name == null ? id : name;
        quantization = quantization == null ? "" : quantization;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (sizeBytes < 0) {
            sizeBytes = 0;
        }
        if (contextWindowTokens < 0) {
            contextWindowTokens = 0;
        }
    }

    /** Convenience constructor for models without extended metadata. */
    public ModelDefinition(String id, String name, String providerId,
                           long contextWindowTokens, boolean available) {
        this(id, name, providerId, contextWindowTokens, available, 0, "", false, Set.of());
    }

    /** Returns a copy of this definition with the runtime serving it renamed. */
    public ModelDefinition withProviderId(String newProviderId) {
        return new ModelDefinition(id, name, newProviderId, contextWindowTokens, available,
                sizeBytes, quantization, loaded, capabilities);
    }
}
