package eu.kalafatic.dcode.core.config;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory configuration service. Used for bootstrap and tests; the durable implementation
 * (filesystem + encrypted secrets) is provided by the persistence bundle.
 */
public final class InMemoryConfigurationService implements ConfigurationService {

    private final AtomicReference<CoreConfiguration> configuration =
            new AtomicReference<>();
    private final ConcurrentHashMap<String, String> secrets = new ConcurrentHashMap<>();

    public InMemoryConfigurationService(CoreConfiguration initial) {
        configuration.set(initial);
    }

    @Override
    public CoreConfiguration getConfiguration() {
        return configuration.get();
    }

    @Override
    public void update(CoreConfiguration config) {
        configuration.set(config);
    }

    @Override
    public Set<String> secretKeys() {
        return Set.copyOf(secrets.keySet());
    }

    @Override
    public void setSecret(String key, String value) {
        secrets.put(key, value);
    }

    @Override
    public Optional<String> getSecret(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    @Override
    public void removeSecret(String key) {
        secrets.remove(key);
    }
}
