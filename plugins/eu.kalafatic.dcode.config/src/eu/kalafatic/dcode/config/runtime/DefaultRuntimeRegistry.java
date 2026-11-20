package eu.kalafatic.dcode.config.runtime;

import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.ConnectionTestResult;
import eu.kalafatic.dcode.config.provider.LlmProvider;
import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory runtime registry.
 *
 * <p>This is the single authoritative registry for runtime registrations. It also
 * implements the narrower {@link ProviderRegistry} contract so the coding harness keeps
 * using one provider abstraction: the provider id equals the runtime instance id.</p>
 *
 * <p>Plain {@link LlmProvider}s registered through the {@link ProviderRegistry} view are
 * wrapped into minimal adapters with no optional capabilities.</p>
 */
public final class DefaultRuntimeRegistry implements RuntimeRegistry, ProviderRegistry {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicReference<String> defaultRuntimeId = new AtomicReference<>();
    private final AtomicReference<String> defaultModelId = new AtomicReference<>();

    private record Entry(RuntimeRegistration registration, RuntimeAdapter adapter,
                         AtomicReference<RuntimeStatus> status,
                         AtomicInteger modelCount) {
    }

    @Override
    public List<RuntimeInfo> runtimes() {
        List<RuntimeInfo> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            result.add(infoOf(entry));
        }
        result.sort(java.util.Comparator.comparing(RuntimeInfo::name));
        return List.copyOf(result);
    }

    @Override
    public Optional<RuntimeInfo> info(String id) {
        Entry entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(infoOf(entry));
    }

    private RuntimeInfo infoOf(Entry entry) {
        return new RuntimeInfo(entry.registration.id(), entry.registration.name(),
                entry.registration.type(), entry.registration.endpoint(),
                entry.registration.enabled(), entry.registration.defaultRuntime(),
                entry.adapter.isLocal(), entry.adapter.capabilities(),
                entry.status.get(), entry.registration.configuration(),
                entry.modelCount.get());
    }

    @Override
    public void register(RuntimeRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        throw new ProviderException("No adapter factory bound for runtime type "
                + registration.type() + " (" + registration.id()
                + "): register with an explicit adapter");
    }

    /** Registers a runtime with an explicit adapter instance. */
    public void register(RuntimeRegistration registration, RuntimeAdapter adapter) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(adapter, "adapter");
        registerEntry(registration, adapter);
    }

    private void registerEntry(RuntimeRegistration registration, RuntimeAdapter adapter) {
        Entry previous = entries.putIfAbsent(registration.id(),
                new Entry(registration, adapter, new AtomicReference<>(RuntimeStatus.unknown()),
                        new AtomicInteger(-1)));
        if (previous != null) {
            throw new IllegalArgumentException("Runtime already registered: "
                    + registration.id());
        }
        if (registration.defaultRuntime()) {
            defaultRuntimeId.set(registration.id());
        }
    }

    @Override
    public void unregister(String id) {
        Entry removed = entries.remove(id);
        if (removed != null && defaultRuntimeId.get() != null
                && defaultRuntimeId.get().equals(id)) {
            defaultRuntimeId.set(null);
        }
    }

    @Override
    public void update(RuntimeRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        Entry existing = entries.get(registration.id());
        if (existing == null) {
            throw new ProviderException("Unknown runtime: " + registration.id());
        }
        update(registration, existing.adapter());
    }

    /** Replaces the registration and the adapter (endpoint/config changes take effect). */
    public void update(RuntimeRegistration registration, RuntimeAdapter adapter) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(adapter, "adapter");
        Entry existing = entries.get(registration.id());
        if (existing == null) {
            throw new ProviderException("Unknown runtime: " + registration.id());
        }
        entries.put(registration.id(), new Entry(registration, adapter, existing.status(),
                existing.modelCount()));
        if (registration.defaultRuntime()) {
            defaultRuntimeId.set(registration.id());
        } else if (registration.id().equals(defaultRuntimeId.get())) {
            defaultRuntimeId.set(null);
        }
    }

    @Override
    public void setEnabled(String id, boolean enabled) {
        Entry entry = requireEntry(id);
        update(entry.registration.withEnabled(enabled));
    }

    @Override
    public void setDefault(String id, String defaultModel) {
        requireEntry(id);
        defaultRuntimeId.set(id);
        if (defaultModel != null && !defaultModel.isBlank()) {
            defaultModelId.set(defaultModel);
        }
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            boolean shouldBeDefault = entry.getKey().equals(id);
            if (entry.getValue().registration.defaultRuntime() != shouldBeDefault) {
                update(entry.getValue().registration.withDefault(shouldBeDefault));
            }
        }
    }

    @Override
    public Optional<RuntimeInfo> defaultRuntime() {
        String id = defaultRuntimeId.get();
        return id == null ? Optional.empty() : info(id);
    }

    /** The default model id of the default runtime, when selected. */
    public Optional<String> defaultModel() {
        return Optional.ofNullable(defaultModelId.get());
    }

    @Override
    public List<ModelDefinition> listModels(String id) {
        return requireEntry(id).adapter().listModels();
    }

    @Override
    public List<ModelDefinition> listAllModels() {
        List<ModelDefinition> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (!entry.registration.enabled()) {
                continue;
            }
            try {
                result.addAll(entry.adapter().listModels());
            } catch (ProviderException e) {
                // an unreachable runtime must not break discovery of the others
            }
        }
        return List.copyOf(result);
    }

    @Override
    public ConnectionTestResult testConnection(String id) {
        Entry entry = requireEntry(id);
        ConnectionTestResult result = entry.adapter().testConnection();
        entry.status().set(result.success()
                ? RuntimeStatus.ok(result.message())
                : RuntimeStatus.failed(result.message()));
        return result;
    }

    @Override
    public ChatResponse complete(String id, ChatRequest request) {
        return requireEntry(id).adapter().complete(request);
    }

    @Override
    public StreamHandle stream(String id, ChatRequest request, StreamListener listener) {
        return requireEntry(id).adapter().stream(request, listener);
    }

    @Override
    public ModelDefinition loadModel(String id, String modelId) {
        return requireEntry(id).adapter().loadModel(modelId);
    }

    @Override
    public ModelDefinition unloadModel(String id, String modelId) {
        return requireEntry(id).adapter().unloadModel(modelId);
    }

    @Override
    public ModelDefinition pullModel(String id, String modelId) {
        return requireEntry(id).adapter().pullModel(modelId);
    }

    @Override
    public RuntimeStatus runtimeStatus(String id) {
        return requireEntry(id).adapter().runtimeStatus();
    }

    @Override
    public boolean isEnabled(String id) {
        Entry entry = entries.get(id);
        return entry != null && entry.registration.enabled();
    }

    @Override
    public boolean isRegistered(String id) {
        return entries.containsKey(id);
    }

    @Override
    public Map<String, String> credentialState(String id) {
        return Map.of();
    }

    // ---- ProviderRegistry bridge ----

    @Override
    public void register(LlmProvider provider) {
        Objects.requireNonNull(provider, "provider");
        RuntimeAdapter adapter = provider instanceof RuntimeAdapter runtimeAdapter
                ? runtimeAdapter
                : PlainProviderAdapter.wrap(provider);
        RuntimeType type = adapter.type();
        registerEntry(RuntimeRegistration.of(provider.providerId(), provider.displayName(),
                type, provider.baseUrl()), adapter);
    }

    @Override
    public List<LlmProvider> providers() {
        List<LlmProvider> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            result.add(entry.adapter());
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<LlmProvider> find(String providerId) {
        Entry entry = entries.get(providerId);
        return entry == null ? Optional.empty()
                : Optional.of(entry.adapter());
    }

    private Entry requireEntry(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw new ProviderException("Unknown runtime: " + id);
        }
        return entry;
    }

    /** Records a runtime status snapshot (used by management APIs after probing). */
    public void recordStatus(String id, RuntimeStatus status) {
        Entry entry = entries.get(id);
        if (entry != null) {
            entry.status().set(status);
        }
    }

    /** Records the discovered model count of a runtime (-1 when unknown). */
    public void recordModelCount(String id, int count) {
        Entry entry = entries.get(id);
        if (entry != null) {
            entry.modelCount().set(count);
        }
    }

    /** Wraps a plain LlmProvider into a minimal adapter without optional capabilities. */
    private static final class PlainProviderAdapter implements RuntimeAdapter {

        private final LlmProvider delegate;

        private PlainProviderAdapter(LlmProvider delegate) {
            this.delegate = delegate;
        }

        static PlainProviderAdapter wrap(LlmProvider provider) {
            return new PlainProviderAdapter(provider);
        }

        @Override
        public String id() {
            return delegate.providerId();
        }

        @Override
        public RuntimeType type() {
            return RuntimeType.OPENAI_COMPATIBLE;
        }

        @Override
        public String endpoint() {
            return delegate.baseUrl();
        }

        @Override
        public String displayName() {
            return delegate.displayName();
        }

        @Override
        public boolean isLocal() {
            return delegate.isLocal();
        }

        @Override
        public List<ModelDefinition> listModels() {
            return delegate.listModels();
        }

        @Override
        public ConnectionTestResult testConnection() {
            return delegate.testConnection();
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            return delegate.complete(request);
        }

        @Override
        public void updateConfiguration(Map<String, String> configuration) {
            delegate.updateConfiguration(configuration);
        }

        @Override
        public Map<String, String> getConfiguration() {
            return delegate.getConfiguration();
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return RuntimeCapabilities.none()
                    .withModelDiscovery(true)
                    .withStreaming(false);
        }
    }
}
