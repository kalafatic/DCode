package eu.kalafatic.dcode.core.config;

import java.util.Optional;
import java.util.Set;

/**
 * Central configuration service contract.
 *
 * <p>One configuration authority per DCode runtime. Provider/agent/tool configuration in
 * later bundles implements and consumes this contract; credentials are handled through the
 * separate secret vault methods and are never part of exported configuration.</p>
 */
public interface ConfigurationService {

    CoreConfiguration getConfiguration();

    /** Replaces the configuration atomically. */
    void update(CoreConfiguration configuration);

    /** Returns the names of configured secret keys (never the values). */
    Set<String> secretKeys();

    /** Stores a secret. Implementations must protect values at rest and in transit. */
    void setSecret(String key, String value);

    /** Returns a secret by key. */
    Optional<String> getSecret(String key);

    /** Removes a secret. */
    void removeSecret(String key);
}
