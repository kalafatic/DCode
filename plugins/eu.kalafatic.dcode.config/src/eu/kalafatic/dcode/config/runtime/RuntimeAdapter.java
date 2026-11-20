package eu.kalafatic.dcode.config.runtime;

import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.LlmProvider;
import eu.kalafatic.dcode.config.provider.ProviderException;

/**
 * Inference runtime adapter contract.
 *
 * <p>Every supported runtime integrates through an adapter implementing this contract.
 * Adapters also satisfy the narrower {@link LlmProvider} SPI (provider id = runtime
 * instance id), so the coding harness never needs to branch on a concrete runtime type.</p>
 *
 * <p>Optional operations (load/unload/pull/runtime status) are exposed only when the
 * adapter reports the matching capability; the default implementations throw
 * {@link ProviderException} instead of silently pretending support.</p>
 */
public interface RuntimeAdapter extends LlmProvider {

    /** Stable runtime instance id (also the provider id seen by the harness). */
    String id();

    /** Runtime type (selects the adapter family). */
    RuntimeType type();

    /** API endpoint of this runtime instance. */
    String endpoint();

    /** Capabilities of this adapter (drives what the UI and API expose). */
    default RuntimeCapabilities capabilities() {
        return RuntimeCapabilities.none();
    }

    @Override
    default String providerId() {
        return id();
    }

    @Override
    default String baseUrl() {
        return endpoint();
    }

    /**
     * Streams one chat completion. The default implementation degrades to a single
     * blocking completion (one token, no cancellation); adapters with streaming
     * capability override it.
     */
    default StreamHandle stream(ChatRequest request, StreamListener listener) {
        try {
            ChatResponse response = complete(request);
            if (!response.content().isBlank()) {
                listener.onToken(response.content());
            }
            listener.onComplete(StreamResult.completed(response));
        } catch (ProviderException e) {
            listener.onError(e);
        }
        return StreamHandle.noop();
    }

    /** Explicitly loads a model (requires {@link RuntimeCapabilities#modelLoad()}). */
    default ModelDefinition loadModel(String modelId) {
        throw new ProviderException("Model loading is not supported by runtime " + id());
    }

    /** Explicitly unloads a model (requires {@link RuntimeCapabilities#modelUnload()}). */
    default ModelDefinition unloadModel(String modelId) {
        throw new ProviderException("Model unloading is not supported by runtime " + id());
    }

    /** Pulls/downloads a model (requires {@link RuntimeCapabilities#modelPull()}). */
    default ModelDefinition pullModel(String modelId) {
        throw new ProviderException("Model pull is not supported by runtime " + id());
    }

    /** Runtime-level status where exposed (requires {@link RuntimeCapabilities#runtimeStatus()}). */
    default RuntimeStatus runtimeStatus() {
        return RuntimeStatus.unknown();
    }
}
