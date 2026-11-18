package eu.kalafatic.dcode.config.routing;

import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.core.model.AgentRole;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe role-based model router. Roles without an explicit override resolve to the
 * default model. Resolution never silently invents a provider: an unset default is an
 * error.
 */
public final class DefaultModelRouter implements ModelRouter {

    private final AtomicReference<ModelSelection> defaultModel = new AtomicReference<>();
    private final EnumMap<AgentRole, ModelSelection> roleModels = new EnumMap<>(AgentRole.class);

    @Override
    public synchronized ModelSelection resolve(AgentRole role) {
        Objects.requireNonNull(role, "role");
        ModelSelection override = roleModels.get(role);
        if (override != null) {
            return override;
        }
        ModelSelection fallback = defaultModel.get();
        if (fallback == null) {
            throw new ProviderException("No model configured: neither a default model nor a "
                    + "role override for " + role + " exists");
        }
        return fallback;
    }

    @Override
    public void setDefaultModel(String providerId, String modelId) {
        defaultModel.set(requireSelection(providerId, modelId));
    }

    @Override
    public ModelSelection defaultModel() {
        return defaultModel.get();
    }

    @Override
    public synchronized void setRoleModel(AgentRole role, String providerId, String modelId) {
        roleModels.put(Objects.requireNonNull(role, "role"),
                requireSelection(providerId, modelId));
    }

    @Override
    public synchronized void clearRoleModel(AgentRole role) {
        roleModels.remove(role);
    }

    @Override
    public synchronized Set<AgentRole> overriddenRoles() {
        return Set.copyOf(roleModels.keySet());
    }

    @Override
    public synchronized Map<AgentRole, ModelSelection> roleModels() {
        return Map.copyOf(roleModels);
    }

    private static ModelSelection requireSelection(String providerId, String modelId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
        return new ModelSelection(providerId, modelId);
    }
}
