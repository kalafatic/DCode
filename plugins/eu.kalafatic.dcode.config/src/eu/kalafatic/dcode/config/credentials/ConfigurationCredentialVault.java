package eu.kalafatic.dcode.config.credentials;

import eu.kalafatic.dcode.core.config.ConfigurationService;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Credential vault backed by the core {@link ConfigurationService} secret store.
 */
public final class ConfigurationCredentialVault implements CredentialVault {

    private final ConfigurationService configurationService;
    private final String namespace;

    public ConfigurationCredentialVault(ConfigurationService configurationService) {
        this(configurationService, "");
    }

    public ConfigurationCredentialVault(ConfigurationService configurationService,
                                        String namespace) {
        this.configurationService = Objects.requireNonNull(configurationService,
                "configurationService");
        this.namespace = namespace == null ? "" : namespace;
    }

    @Override
    public void put(String key, String secret) {
        Objects.requireNonNull(key, "key");
        configurationService.setSecret(namespace + key, secret);
    }

    @Override
    public Optional<String> get(String key) {
        return configurationService.getSecret(namespace + key);
    }

    @Override
    public void remove(String key) {
        configurationService.removeSecret(namespace + key);
    }

    @Override
    public boolean has(String key) {
        return configurationService.getSecret(namespace + key).isPresent();
    }

    @Override
    public Set<String> keys() {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String key : configurationService.secretKeys()) {
            if (key.startsWith(namespace)) {
                result.add(key.substring(namespace.length()));
            }
        }
        return Set.copyOf(result);
    }
}
