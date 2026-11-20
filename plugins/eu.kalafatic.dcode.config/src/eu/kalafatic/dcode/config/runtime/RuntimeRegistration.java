package eu.kalafatic.dcode.config.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistable runtime registration: identity, type, endpoint and non-sensitive
 * configuration of one inference runtime instance.
 *
 * <p>Secrets (bearer tokens, API keys) are deliberately not part of this record; they
 * live in the credential vault under {@code runtime.<id>.*} keys and never appear in
 * persisted JSON.</p>
 *
 * @param id stable runtime instance id
 * @param name human-readable display name
 * @param type runtime type (selects the adapter)
 * @param endpoint base URL of the runtime API
 * @param enabled whether this runtime participates in inference
 * @param defaultRuntime whether this runtime is the default selection
 * @param configuration non-sensitive provider-specific configuration
 */
public record RuntimeRegistration(
        String id,
        String name,
        RuntimeType type,
        String endpoint,
        boolean enabled,
        boolean defaultRuntime,
        Map<String, String> configuration
) {
    public RuntimeRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        name = name == null || name.isBlank() ? id : name;
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint is required for runtime " + id);
        }
        configuration = configuration == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(configuration));
    }

    public static RuntimeRegistration of(String id, String name, RuntimeType type,
                                         String endpoint) {
        return new RuntimeRegistration(id, name, type, endpoint, true, false, Map.of());
    }

    public static RuntimeRegistration of(RuntimeType type, String endpoint) {
        return new RuntimeRegistration(UUID.randomUUID().toString(),
                type.displayName(), type, endpoint, true, false, Map.of());
    }

    public RuntimeRegistration withName(String newName) {
        return new RuntimeRegistration(id, newName, type, endpoint, enabled,
                defaultRuntime, configuration);
    }

    public RuntimeRegistration withEndpoint(String newEndpoint) {
        return new RuntimeRegistration(id, name, type, newEndpoint, enabled,
                defaultRuntime, configuration);
    }

    public RuntimeRegistration withEnabled(boolean value) {
        return new RuntimeRegistration(id, name, type, endpoint, value,
                defaultRuntime, configuration);
    }

    public RuntimeRegistration withDefault(boolean value) {
        return new RuntimeRegistration(id, name, type, endpoint, enabled, value,
                configuration);
    }

    public RuntimeRegistration withConfiguration(Map<String, String> newConfiguration) {
        return new RuntimeRegistration(id, name, type, endpoint, enabled,
                defaultRuntime, newConfiguration);
    }
}
