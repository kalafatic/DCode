package eu.kalafatic.dcode.config.agent;

import java.util.List;
import java.util.Objects;

/**
 * One DCode agent: an execution persona bound to a coding session.
 *
 * <p>Agents are decoupled from runtimes and harnesses: they carry system
 * instructions, a role, tool permissions and restrictions plus optional preferred
 * runtime/model — never a hardwired provider.</p>
 *
 * @param id stable agent id
 * @param name display name
 * @param description one-line description
 * @param role agent role (maps to {@code AgentRole} where applicable)
 * @param systemInstructions instructions rendered as part of the harness system prompt
 * @param toolPermissions tools the agent may use (empty = all tools)
 * @param restrictions additional behavioral restrictions
 * @param preferredRuntimeId preferred runtime instance (empty = platform default)
 * @param preferredModel preferred model (empty = runtime default)
 * @param builtin true for the shipped agents (cannot be deleted)
 */
public record AgentDefinition(
        String id,
        String name,
        String description,
        String role,
        String systemInstructions,
        List<String> toolPermissions,
        List<String> restrictions,
        String preferredRuntimeId,
        String preferredModel,
        boolean builtin
) {
    public AgentDefinition {
        Objects.requireNonNull(id, "id");
        name = name == null ? id : name;
        description = description == null ? "" : description;
        role = role == null || role.isBlank() ? "MAIN" : role;
        systemInstructions = systemInstructions == null ? "" : systemInstructions;
        toolPermissions = toolPermissions == null ? List.of()
                : List.copyOf(toolPermissions);
        restrictions = restrictions == null ? List.of() : List.copyOf(restrictions);
        preferredRuntimeId = preferredRuntimeId == null ? "" : preferredRuntimeId;
        preferredModel = preferredModel == null ? "" : preferredModel;
    }
}
