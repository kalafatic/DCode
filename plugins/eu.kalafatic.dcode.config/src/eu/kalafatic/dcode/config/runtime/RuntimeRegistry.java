package eu.kalafatic.dcode.config.runtime;

import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.ConnectionTestResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime registry contract: the single authoritative registry of inference runtime
 * registrations. One registry per DCode runtime; no separate per-provider registries.
 *
 * <p>Each registration has its own identity and configuration; multiple instances of one
 * runtime type are first-class. Models are always associated with the runtime instance
 * that serves them.</p>
 */
public interface RuntimeRegistry {

    /** Snapshot of all runtime registrations including last known status. */
    List<RuntimeInfo> runtimes();

    /** Snapshot of one runtime registration. */
    Optional<RuntimeInfo> info(String id);

    /** Registers a new runtime. Rejects duplicate ids. */
    void register(RuntimeRegistration registration);

    /** Removes a runtime registration. Never deletes data of the external runtime. */
    void unregister(String id);

    /** Replaces the registration (same id). */
    void update(RuntimeRegistration registration);

    /** Enables or disables a runtime. */
    void setEnabled(String id, boolean enabled);

    /** Marks a runtime as default (optionally selecting a default model). */
    void setDefault(String id, String defaultModelId);

    /** The default runtime registration, when configured. */
    Optional<RuntimeInfo> defaultRuntime();

    /** The default model of the default runtime, when selected. */
    default Optional<String> defaultModel() {
        return Optional.empty();
    }

    /** Discovers the models of one runtime. */
    List<ModelDefinition> listModels(String id);

    /** Discovers models across all enabled runtimes. */
    List<ModelDefinition> listAllModels();

    /** Live connection test of one runtime; updates the cached status. */
    ConnectionTestResult testConnection(String id);

    /** Executes one blocking chat completion through the runtime. */
    ChatResponse complete(String id, ChatRequest request);

    /** Streams one chat completion through the runtime. */
    StreamHandle stream(String id, ChatRequest request, StreamListener listener);

    /** Loads a model through a supporting runtime. */
    ModelDefinition loadModel(String id, String modelId);

    /** Unloads a model through a supporting runtime. */
    ModelDefinition unloadModel(String id, String modelId);

    /** Pulls a model through a supporting runtime. */
    ModelDefinition pullModel(String id, String modelId);

    /** Runtime-level status where the adapter exposes it. */
    RuntimeStatus runtimeStatus(String id);

    /** True when the runtime is registered and enabled. */
    boolean isEnabled(String id);

    /** True when a runtime with the given id is registered (enabled or not). */
    boolean isRegistered(String id);

    /** Stored credentials state of a runtime (names only, never values). */
    Map<String, String> credentialState(String id);
}
