package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration of an agent role instance: identity, role, instructions and routing keys.
 * Roles are configurable; the harness resolves definitions by role at execution time.
 */
public record AgentDefinition(
        String id,
        AgentRole role,
        String name,
        String description,
        String systemInstructions,
        String modelRole,
        Set<String> capabilities
) {
    public AgentDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(name, "name");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (modelRole == null) {
            modelRole = "coding";
        }
    }
}
