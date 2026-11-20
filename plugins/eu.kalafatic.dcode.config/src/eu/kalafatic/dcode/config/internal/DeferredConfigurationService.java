package eu.kalafatic.dcode.config.internal;

import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.service.DynamicServiceLookup;

import java.util.Optional;
import java.util.Set;

/**
 * Configuration service facade that resolves the best-ranked registered
 * {@link ConfigurationService} at call time instead of once at startup.
 *
 * <p>The durable (encrypted) configuration service registers with a higher ranking
 * after bundle startup; binding it eagerly would silently keep the in-memory store.
 * Credentials written through the vault are therefore always persisted by the best
 * implementation available at the moment of the call.</p>
 */
public final class DeferredConfigurationService implements ConfigurationService {

    private final DynamicServiceLookup services;

    public DeferredConfigurationService(DynamicServiceLookup services) {
        this.services = services;
    }

    private ConfigurationService resolve() {
        ConfigurationService service = services.get(ConfigurationService.class);
        if (service == null) {
            throw new IllegalStateException("No ConfigurationService available");
        }
        return service;
    }

    @Override
    public CoreConfiguration getConfiguration() {
        return resolve().getConfiguration();
    }

    @Override
    public void update(CoreConfiguration configuration) {
        resolve().update(configuration);
    }

    @Override
    public Set<String> secretKeys() {
        return resolve().secretKeys();
    }

    @Override
    public void setSecret(String key, String value) {
        resolve().setSecret(key, value);
    }

    @Override
    public Optional<String> getSecret(String key) {
        return resolve().getSecret(key);
    }

    @Override
    public void removeSecret(String key) {
        resolve().removeSecret(key);
    }
}
