package eu.kalafatic.dcode.config.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Capabilities of one runtime adapter.
 *
 * <p>Capability discovery keeps provider-specific functionality available without forcing
 * every runtime to implement operations it does not support. Operations must only be
 * exposed when the corresponding capability is present; providers without load/unload
 * support do not falsely advertise it.</p>
 *
 * @param modelDiscovery the runtime publishes a model list
 * @param streaming responses can be streamed token by token
 * @param cancellation in-flight requests can be cancelled
 * @param modelLoad models can be explicitly loaded (where supported)
 * @param modelUnload models can be explicitly unloaded (where supported)
 * @param modelPull models can be pulled/downloaded (where supported)
 * @param health a dedicated health endpoint exists
 * @param runtimeStatus a runtime status endpoint exists
 * @param reported provider-reported capability ids (e.g. ENR capability descriptors)
 */
public record RuntimeCapabilities(
        boolean modelDiscovery,
        boolean streaming,
        boolean cancellation,
        boolean modelLoad,
        boolean modelUnload,
        boolean modelPull,
        boolean health,
        boolean runtimeStatus,
        Set<String> reported
) {
    public RuntimeCapabilities {
        reported = reported == null ? Set.of() : Set.copyOf(reported);
    }

    public static RuntimeCapabilities none() {
        return new RuntimeCapabilities(false, false, false, false, false, false,
                false, false, Set.of());
    }

    public static RuntimeCapabilities all() {
        return new RuntimeCapabilities(true, true, true, true, true, true,
                true, true, Set.of());
    }

    public RuntimeCapabilities withModelDiscovery(boolean value) {
        return new RuntimeCapabilities(value, streaming, cancellation, modelLoad,
                modelUnload, modelPull, health, runtimeStatus, reported);
    }

    public RuntimeCapabilities withStreaming(boolean value) {
        return new RuntimeCapabilities(modelDiscovery, value, cancellation, modelLoad,
                modelUnload, modelPull, health, runtimeStatus, reported);
    }

    public RuntimeCapabilities withModelLoad(boolean value) {
        return new RuntimeCapabilities(modelDiscovery, streaming, cancellation, value,
                modelUnload, modelPull, health, runtimeStatus, reported);
    }

    public RuntimeCapabilities withModelUnload(boolean value) {
        return new RuntimeCapabilities(modelDiscovery, streaming, cancellation, modelLoad,
                value, modelPull, health, runtimeStatus, reported);
    }

    public RuntimeCapabilities withModelPull(boolean value) {
        return new RuntimeCapabilities(modelDiscovery, streaming, cancellation, modelLoad,
                modelUnload, value, health, runtimeStatus, reported);
    }

    public RuntimeCapabilities withReported(Set<String> capabilityIds) {
        return new RuntimeCapabilities(modelDiscovery, streaming, cancellation, modelLoad,
                modelUnload, modelPull, health, runtimeStatus,
                capabilityIds == null ? reported : Set.copyOf(capabilityIds));
    }

    /** Serializes this capability set for persistence. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modelDiscovery", modelDiscovery);
        map.put("streaming", streaming);
        map.put("cancellation", cancellation);
        map.put("modelLoad", modelLoad);
        map.put("modelUnload", modelUnload);
        map.put("modelPull", modelPull);
        map.put("health", health);
        map.put("runtimeStatus", runtimeStatus);
        map.put("reported", new LinkedHashSet<>(reported));
        return map;
    }

    /** Deserializes a capability set from persistence. */
    public static RuntimeCapabilities fromMap(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        Set<String> reported = new LinkedHashSet<>();
        Object reportedValue = map.get("reported");
        if (reportedValue instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                reported.add(String.valueOf(item));
            }
        }
        return new RuntimeCapabilities(
                bool(map, "modelDiscovery"), bool(map, "streaming"),
                bool(map, "cancellation"), bool(map, "modelLoad"),
                bool(map, "modelUnload"), bool(map, "modelPull"),
                bool(map, "health"), bool(map, "runtimeStatus"), reported);
    }

    private static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b && b;
    }
}
