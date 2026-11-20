package eu.kalafatic.dcode.config.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Read-only snapshot of one runtime registration for display and API purposes.
 *
 * @param id runtime instance id
 * @param name display name
 * @param type runtime type
 * @param endpoint API endpoint
 * @param enabled whether the runtime participates in inference
 * @param defaultRuntime whether it is the default runtime
 * @param local whether the endpoint is local
 * @param capabilities adapter capabilities
 * @param status last known connectivity/health state
 * @param configuration non-sensitive configuration
 * @param modelCount number of discovered models (-1 when unknown)
 */
public record RuntimeInfo(
        String id,
        String name,
        RuntimeType type,
        String endpoint,
        boolean enabled,
        boolean defaultRuntime,
        boolean local,
        RuntimeCapabilities capabilities,
        RuntimeStatus status,
        Map<String, String> configuration,
        int modelCount
) {
    public RuntimeInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        name = name == null ? id : name;
        endpoint = endpoint == null ? "" : endpoint;
        capabilities = capabilities == null ? RuntimeCapabilities.none() : capabilities;
        status = status == null ? RuntimeStatus.unknown() : status;
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }

    public static RuntimeInfo of(RuntimeRegistration registration,
                                 RuntimeAdapter adapter) {
        return new RuntimeInfo(registration.id(), registration.name(),
                registration.type(), registration.endpoint(), registration.enabled(),
                registration.defaultRuntime(), adapter.isLocal(), adapter.capabilities(),
                RuntimeStatus.unknown(), registration.configuration(), -1);
    }
}
