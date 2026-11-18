package eu.kalafatic.dcode.config.model;

import java.util.Objects;

/**
 * One model published by a provider.
 *
 * @param id model identifier as accepted by the provider API (e.g. {@code qwen3-coder:32k})
 * @param name human-readable name
 * @param contextWindowTokens known context size, 0 when unknown
 */
public record ModelDefinition(
        String id,
        String name,
        String providerId,
        long contextWindowTokens,
        boolean available
) {
    public ModelDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerId, "providerId");
        name = name == null ? id : name;
    }
}
