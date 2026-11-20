package eu.kalafatic.dcode.config.runtime;

import java.util.List;
import java.util.Map;

/**
 * Persisted snapshot of the runtime registry: registrations plus default selection.
 * Secrets are never part of this record; they live in the credential vault.
 */
public record RuntimeStoreData(
        String defaultRuntimeId,
        String defaultModelId,
        List<RuntimeRegistration> registrations
) {
    public RuntimeStoreData {
        registrations = registrations == null ? List.of() : List.copyOf(registrations);
    }

    public static RuntimeStoreData empty() {
        return new RuntimeStoreData(null, null, List.of());
    }
}
