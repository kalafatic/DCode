package eu.kalafatic.dcode.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Identity of the backend behind a DCode API endpoint, as reported by
 * {@code GET /api/v1/health}. Used to detect endpoint/compatibility mismatches
 * (e.g. connecting to an ENR or TRYST endpoint instead of a DCode server).
 *
 * @param application the application reported by the backend (e.g. {@code DCODE})
 * @param version backend version string
 * @param ready whether the backend reports readiness
 * @param status health status ({@code UP} / {@code DEGRADED} / ...)
 * @param stages lifecycle stage states reported by the backend
 */
public record BackendIdentity(
        String application,
        String version,
        boolean ready,
        String status,
        Map<String, String> stages
) {
    public BackendIdentity {
        application = application == null ? "" : application;
        version = version == null ? "" : version;
        status = status == null ? "" : status;
        stages = stages == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(stages));
    }

    /** The DCode API contract identifies itself with application {@code DCODE}. */
    public boolean isDCode() {
        return "DCODE".equalsIgnoreCase(application);
    }

    /** Parses the backend health payload. Returns an empty identity when unrecognizable. */
    public static BackendIdentity parse(JsonNode health) {
        Objects.requireNonNull(health, "health");
        String application = health.path("application").asText("");
        if (application.isBlank()) {
            // Family-internal backends may omit the field; try common aliases.
            application = health.path("name").asText("");
        }
        Map<String, String> stages = new LinkedHashMap<>();
        JsonNode stagesNode = health.path("stages");
        if (stagesNode.isObject()) {
            stagesNode.fields().forEachRemaining(entry ->
                    stages.put(entry.getKey(), entry.getValue().asText("")));
        }
        return new BackendIdentity(application,
                health.path("version").asText(""),
                health.path("ready").asBoolean(false),
                health.path("status").asText(""),
                stages);
    }

    public String describe() {
        if (application.isBlank()) {
            return "unrecognized HTTP service";
        }
        return application + (version.isBlank() ? "" : " " + version)
                + " (status=" + (status.isBlank() ? "?" : status)
                + ", ready=" + ready + ")";
    }
}
