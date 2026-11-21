package eu.kalafatic.dcode.config.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default profile registry: shipped built-in profiles plus persisted custom profiles.
 * The default selection persists and survives restarts.
 */
public final class DefaultProfileRegistry implements ProfileRegistry {

    private static final List<ConversationProfile> BUILT_INS = List.of(
            ConversationProfile.builtin("default", "Default",
                    "Standard coding-assistant behavior",
                    "You are a senior software engineer working in the selected "
                            + "workspace. Prefer minimal, idiomatic and testable "
                            + "changes; verify before modifying; report what you did "
                            + "and why."),
            ConversationProfile.builtin("concise", "Concise",
                    "Short, focused responses",
                    "Be concise. Answer directly, prefer code over prose, and keep "
                            + "explanations minimal."),
            ConversationProfile.builtin("explain", "Explaining",
                    "Pedagogical, thorough explanations",
                    "Explain your reasoning step by step. Prefer thoroughness over "
                            + "brevity, and teach the underlying concepts."));

    private final JsonFileProfileStore store;
    private final Map<String, ConversationProfile> customProfiles = new LinkedHashMap<>();
    private final AtomicReference<String> defaultProfileId = new AtomicReference<>();

    public DefaultProfileRegistry(JsonFileProfileStore store) {
        this.store = store;
        JsonFileProfileStore.ProfileStoreData data = store.load();
        for (ConversationProfile profile : data.customProfiles()) {
            customProfiles.put(profile.id(), profile);
        }
        defaultProfileId.set(data.defaultProfileId() != null ? data.defaultProfileId()
                : BUILT_INS.get(0).id());
    }

    @Override
    public List<ConversationProfile> profiles() {
        List<ConversationProfile> result = new ArrayList<>(BUILT_INS);
        result.addAll(customProfiles.values());
        return List.copyOf(result);
    }

    @Override
    public Optional<ConversationProfile> find(String id) {
        return BUILT_INS.stream().filter(profile -> profile.id().equals(id)).findFirst()
                .or(() -> Optional.ofNullable(customProfiles.get(id)));
    }

    @Override
    public ConversationProfile register(ConversationProfile profile) {
        if (profile.builtin()) {
            throw new IllegalArgumentException("Built-in profiles are immutable");
        }
        customProfiles.put(profile.id(), profile);
        persist();
        return profile;
    }

    @Override
    public void unregister(String id) {
        ConversationProfile existing = customProfiles.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown custom profile: " + id);
        }
        customProfiles.remove(id);
        if (id.equals(defaultProfileId.get())) {
            defaultProfileId.set(BUILT_INS.get(0).id());
        }
        persist();
    }

    @Override
    public Optional<ConversationProfile> defaultProfile() {
        return find(defaultProfileId.get());
    }

    @Override
    public void setDefaultProfile(String id) {
        if (find(id).isEmpty()) {
            throw new IllegalArgumentException("Unknown profile: " + id);
        }
        defaultProfileId.set(id);
        persist();
    }

    private void persist() {
        store.save(new JsonFileProfileStore.ProfileStoreData(defaultProfileId.get(),
                List.copyOf(customProfiles.values())));
    }
}
