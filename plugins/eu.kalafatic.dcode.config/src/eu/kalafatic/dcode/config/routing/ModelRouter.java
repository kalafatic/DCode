package eu.kalafatic.dcode.config.routing;

import eu.kalafatic.dcode.core.model.AgentRole;

import java.util.Map;
import java.util.Set;

/**
 * Role-based model routing contract: agent roles resolve to a provider/model pair without
 * duplicating provider configuration. Overrides are optional; resolution falls back to the
 * default model.
 */
public interface ModelRouter {

    /** Resolves the model for an agent role. */
    ModelSelection resolve(AgentRole role);

    /** Sets the default model used when no role override exists. */
    void setDefaultModel(String providerId, String modelId);

    ModelSelection defaultModel();

    /** Overrides the model for one role. */
    void setRoleModel(AgentRole role, String providerId, String modelId);

    void clearRoleModel(AgentRole role);

    Set<AgentRole> overriddenRoles();

    Map<AgentRole, ModelSelection> roleModels();
}
