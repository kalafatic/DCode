package eu.kalafatic.dcode.harness.tool;

import java.util.Objects;

/**
 * Static description of one tool: identity, description, JSON-schema of arguments,
 * read-only flag and scope. Descriptions are exposed to agents and APIs; they must be
 * accurate — an agent decides what to call based on them.
 */
public record ToolDefinition(
        String name,
        String description,
        String parametersJson,
        boolean readOnly,
        ToolScope scope
) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(scope, "scope");
        parametersJson = parametersJson == null ? "{\"type\":\"object\",\"properties\":{}}" : parametersJson;
    }
}
